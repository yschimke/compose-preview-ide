package ee.schimke.composeai.simulator.intellij

import com.android.adblib.ConnectedDevice
import com.android.adblib.DeviceState as AdbDeviceState
import com.android.sdklib.AndroidVersion
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceError
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.sdklib.deviceprovisioner.Resolution
import com.android.sdklib.devices.Abi
import com.android.tools.idea.deviceprovisioner.DeviceProvisionerFactory
import com.android.tools.idea.deviceprovisioner.StudioDefaultDeviceActionPresentation
import com.intellij.openapi.project.Project
import icons.StudioIcons
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers Compose Preview Simulator virtual devices with Android Studio's Device Provisioner so
 * **Compose Preview Simulator (Phone)** and **Compose Preview Simulator (Wear)** appear in Device
 * Manager and the Run ▶ target dropdown before any simulator process is started.
 *
 * Registered only when the optional `org.jetbrains.android` plugin is present (see
 * `META-INF/compose-preview-simulator-android.xml`).
 */
internal class SimulatorDeviceProvisionerFactory : DeviceProvisionerFactory {
  override val isEnabled: Boolean = true

  override fun create(coroutineScope: CoroutineScope, project: Project): DeviceProvisionerPlugin =
    SimulatorDeviceProvisionerPlugin(
      scope = coroutineScope,
      projectBasePath = project.basePath,
      controller = SimulatorManagerService.getInstance(),
    )
}

internal data class SimulatorProvisionerUi(
  val phoneIcon: Icon?,
  val wearIcon: Icon?,
  val startPresentation: DeviceAction.Presentation,
  val stopPresentation: DeviceAction.Presentation,
) {
  companion object {
    fun studioDefaults(): SimulatorProvisionerUi =
      SimulatorProvisionerUi(
        phoneIcon = StudioIcons.DeviceExplorer.VIRTUAL_DEVICE_PHONE,
        wearIcon = StudioIcons.DeviceExplorer.VIRTUAL_DEVICE_WEAR,
        startPresentation = StudioDefaultDeviceActionPresentation.activationAction,
        stopPresentation = StudioDefaultDeviceActionPresentation.deactivationAction,
      )
  }
}

internal class SimulatorDeviceProvisionerPlugin(
  private val scope: CoroutineScope,
  private val projectBasePath: String?,
  private val controller: SimulatorController,
  private val ui: SimulatorProvisionerUi = SimulatorProvisionerUi.studioDefaults(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val connectTimeoutMillis: Long = 20_000L,
) : DeviceProvisionerPlugin {

  /**
   * Higher than `LocalEmulatorProvisionerPlugin` (100) so `emulator-<port>` serials spawned by
   * [SimulatorManagerService] are claimed by our handles instead of showing up as generic unknown
   * AVDs; [claim] returns `null` for every other serial so regular Android emulators are untouched.
   */
  override val priority: Int = PRIORITY

  private val handlesByProfile: Map<SimulatorProfile, SimulatorDeviceHandle> =
    SimulatorProfile.entries.associateWith { profile ->
      SimulatorDeviceHandle(
        profile = profile,
        scope = scope,
        projectBasePath = projectBasePath,
        controller = controller,
        ui = ui,
        ioDispatcher = ioDispatcher,
        connectTimeoutMillis = connectTimeoutMillis,
      )
    }

  private val _devices = MutableStateFlow<List<DeviceHandle>>(handlesByProfile.values.toList())
  override val devices: StateFlow<List<DeviceHandle>> = _devices.asStateFlow()

  init {
    val listener = controller.addChangeListener {
      for (handle in handlesByProfile.values) {
        handle.onControllerStateChanged()
      }
    }
    scope.coroutineContext[Job]?.invokeOnCompletion { listener.dispose() }
  }

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    val serial = device.deviceInfoFlow.value.serialNumber
    val profile = controller.findProfileBySerial(serial) ?: return null
    val handle = handlesByProfile[profile] ?: return null
    handle.attach(device)
    return handle
  }

  internal companion object {
    const val PLUGIN_ID: String = "ComposePreviewSimulator"
    const val PRIORITY: Int = 200
  }
}

private data class SimulatorDeviceError(
  override val severity: DeviceError.Severity,
  override val message: String,
) : DeviceError

internal class SimulatorDeviceHandle(
  val profile: SimulatorProfile,
  override val scope: CoroutineScope,
  private val projectBasePath: String?,
  private val controller: SimulatorController,
  private val ui: SimulatorProvisionerUi,
  private val ioDispatcher: CoroutineDispatcher,
  private val connectTimeoutMillis: Long,
) : DeviceHandle {

  override val id: DeviceId =
    DeviceId(SimulatorDeviceProvisionerPlugin.PLUGIN_ID, false, profile.id)

  private val baseProperties: DeviceProperties = buildProperties(disambiguator = null)

  private val _stateFlow =
    MutableStateFlow<DeviceState>(DeviceState.Disconnected(baseProperties, status = "Offline"))
  override val stateFlow: StateFlow<DeviceState> = _stateFlow.asStateFlow()

  private val attachedJob = AtomicReference<Job?>(null)

  override val activationAction: ActivationAction =
    object : ActivationAction {
      override val presentation: StateFlow<DeviceAction.Presentation> =
        _stateFlow
          .map { state ->
            ui.startPresentation.copy(
              enabled = state is DeviceState.Disconnected && !state.isTransitioning,
            )
          }
          .stateIn(
            scope,
            SharingStarted.Eagerly,
            ui.startPresentation.copy(enabled = true),
          )

      override suspend fun activate() {
        if (_stateFlow.value is DeviceState.Connected) return
        _stateFlow.value =
          DeviceState.Disconnected(
            properties = baseProperties,
            isTransitioning = true,
            status = "Starting ${profile.displayName}…",
          )
        val info =
          try {
            withContext(ioDispatcher) { controller.start(profile, projectBasePath) }
          } catch (e: CancellationException) {
            _stateFlow.value = DeviceState.Disconnected(baseProperties, status = "Offline")
            throw e
          } catch (e: Exception) {
            val detail = e.message ?: "Failed to start ${profile.displayName}"
            _stateFlow.value =
              DeviceState.Disconnected(
                properties = baseProperties,
                isTransitioning = false,
                status = "Offline",
                error = SimulatorDeviceError(DeviceError.Severity.ERROR, detail),
              )
            throw DeviceActionException(detail, e)
          }

        if (_stateFlow.value !is DeviceState.Connected) {
          _stateFlow.value =
            DeviceState.Disconnected(
              properties = buildProperties(disambiguator = info.serial),
              isTransitioning = true,
              status = "Connecting to ${info.serial}…",
            )
        }

        val connected =
          withTimeoutOrNull(connectTimeoutMillis) {
            _stateFlow.first { it is DeviceState.Connected }
          }
        if (connected == null) {
          val message =
            "${profile.displayName} started on ${info.serial}, but ADB did not detect it " +
              "within ${connectTimeoutMillis / 1000}s."
          _stateFlow.value =
            DeviceState.Disconnected(
              properties = buildProperties(disambiguator = info.serial),
              isTransitioning = false,
              status = "Waiting for ADB (${info.serial})",
              error = SimulatorDeviceError(DeviceError.Severity.WARNING, message),
            )
          throw DeviceActionException(message)
        }
      }
    }

  override val deactivationAction: DeactivationAction =
    object : DeactivationAction {
      override val presentation: StateFlow<DeviceAction.Presentation> =
        _stateFlow
          .map { state ->
            val active = state is DeviceState.Connected || controller.runningInfo(profile) != null
            ui.stopPresentation.copy(enabled = active && !state.isTransitioning)
          }
          .stateIn(
            scope,
            SharingStarted.Eagerly,
            ui.stopPresentation.copy(enabled = false),
          )

      override suspend fun deactivate() {
        val current = _stateFlow.value
        _stateFlow.value =
          if (current is DeviceState.Connected) {
            current.copy(isTransitioning = true, status = "Stopping…")
          } else {
            DeviceState.Disconnected(
              properties = baseProperties,
              isTransitioning = true,
              status = "Stopping…",
            )
          }
        try {
          withContext(ioDispatcher) { controller.stop(profile) }
        } finally {
          detach()
        }
      }
    }

  internal fun attach(device: ConnectedDevice) {
    val serial = device.deviceInfoFlow.value.serialNumber
    val connectedProps = buildProperties(disambiguator = serial)
    val initialAdbState = device.deviceInfoFlow.value.deviceState
    _stateFlow.value = DeviceState.Connected(connectedProps, device, initialAdbState)

    val newJob = scope.launch {
      device.deviceInfoFlow.collect { info ->
        when (info.deviceState) {
          AdbDeviceState.DISCONNECTED,
          AdbDeviceState.DETACHED -> {
            if (_stateFlow.value.connectedDevice === device) {
              _stateFlow.value = DeviceState.Disconnected(baseProperties, status = "Offline")
            }
          }
          else -> {
            if (_stateFlow.value.connectedDevice === device) {
              _stateFlow.value = DeviceState.Connected(connectedProps, device, info.deviceState)
            }
          }
        }
      }
    }
    attachedJob.getAndSet(newJob)?.cancel()
  }

  internal fun onControllerStateChanged() {
    if (controller.runningInfo(profile) == null && _stateFlow.value is DeviceState.Connected) {
      detach()
    }
  }

  private fun detach() {
    attachedJob.getAndSet(null)?.cancel()
    _stateFlow.value = DeviceState.Disconnected(baseProperties, status = "Offline")
  }

  private fun buildProperties(disambiguator: String?): DeviceProperties = DeviceProperties.build {
    manufacturer = profile.manufacturer
    model = profile.model
    deviceType = if (profile.isWear) DeviceType.WEAR else DeviceType.HANDHELD
    androidVersion = AndroidVersion(profile.defaultApiLevel, 0)
    androidRelease = profile.defaultAndroidRelease
    abiList = listOf(Abi.ARM64_V8A, Abi.X86_64)
    resolution = Resolution(profile.widthPx, profile.heightPx)
    density = profile.densityDpi
    isVirtual = true
    isDebuggable = true
    icon = if (profile.isWear) ui.wearIcon else ui.phoneIcon
    this.disambiguator = disambiguator
    populateDeviceInfoProto(
      pluginId = SimulatorDeviceProvisionerPlugin.PLUGIN_ID,
      serialNumber = disambiguator,
      properties = emptyMap(),
      connectionId = disambiguator ?: profile.id,
    )
  }
}
