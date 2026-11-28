package ee.schimke.composeai.simulator.intellij

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceState as AdbDeviceState
import com.android.sdklib.deviceprovisioner.DeviceAction
import com.android.sdklib.deviceprovisioner.DeviceActionException
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceType
import com.intellij.openapi.Disposable
import java.awt.Component
import java.awt.Graphics
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SimulatorDeviceProvisionerTest {

  private object StubIcon : Icon {
    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = Unit

    override fun getIconWidth(): Int = 16

    override fun getIconHeight(): Int = 16
  }

  private val testUi =
    SimulatorProvisionerUi(
      phoneIcon = StubIcon,
      wearIcon = StubIcon,
      startPresentation = DeviceAction.Presentation("Start", StubIcon, enabled = true),
      stopPresentation = DeviceAction.Presentation("Stop", StubIcon, enabled = false),
    )

  private class FakeConnectedDevice(
    serial: String,
    initialState: AdbDeviceState = AdbDeviceState.ONLINE,
  ) : ConnectedDevice {
    val flow = MutableStateFlow(DeviceInfo(serial, initialState))

    override val session: AdbSession
      get() = error("unused in unit test")

    override val cache: CoroutineScopeCache
      get() = error("unused in unit test")

    override val deviceInfoFlow: StateFlow<DeviceInfo> = flow
  }

  private class FakeSimulatorController(
    var failStart: Boolean = false,
    var onStart: ((SimulatorProfile, SimulatorDeviceInfo) -> Unit)? = null,
  ) : SimulatorController {
    val running = ConcurrentHashMap<SimulatorProfile, SimulatorDeviceInfo>()
    val startedProfiles = mutableListOf<SimulatorProfile>()
    val stoppedProfiles = mutableListOf<SimulatorProfile>()
    private val listeners = mutableListOf<() -> Unit>()

    override fun addChangeListener(listener: () -> Unit): Disposable {
      listeners.add(listener)
      return Disposable { listeners.remove(listener) }
    }

    override fun runningInfo(profile: SimulatorProfile): SimulatorDeviceInfo? = running[profile]

    override fun findProfileBySerial(serial: String): SimulatorProfile? =
      running.entries.firstOrNull { it.value.serial == serial }?.key

    override fun start(profile: SimulatorProfile, projectBasePath: String?): SimulatorDeviceInfo {
      if (failStart) throw IllegalStateException("compose-preview not found")
      startedProfiles.add(profile)
      val port = if (profile == SimulatorProfile.PHONE) 5554 else 5556
      val info =
        SimulatorDeviceInfo(
          serial = "emulator-$port",
          adbPort = port + 1,
          consolePort = port,
          grpcPort = 8554,
          avdName = profile.id,
          formFactor = profile.formFactorArg ?: "phone",
        )
      running[profile] = info
      notifyListeners()
      onStart?.invoke(profile, info)
      return info
    }

    override fun stop(profile: SimulatorProfile) {
      stoppedProfiles.add(profile)
      running.remove(profile)
      notifyListeners()
    }

    fun notifyListeners() {
      listeners.toList().forEach { it() }
    }
  }

  @Test
  fun exposesPhoneAndWearHandlesBeforeLaunch(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = null,
          controller = FakeSimulatorController(),
          ui = testUi,
          ioDispatcher = Dispatchers.Unconfined,
        )

      assertEquals(200, plugin.priority)
      val handles = plugin.devices.value
      assertEquals(2, handles.size)

      val phone = handles.first { it.state.properties.deviceType == DeviceType.HANDHELD }
      val wear = handles.first { it.state.properties.deviceType == DeviceType.WEAR }

      assertEquals("Compose Preview Simulator (Phone)", phone.state.properties.title)
      assertEquals("Compose Preview Simulator (Wear)", wear.state.properties.title)
      assertEquals(true, phone.state.properties.isVirtual)
      assertEquals(true, wear.state.properties.isVirtual)
      assertIs<DeviceState.Disconnected>(phone.state)
      assertIs<DeviceState.Disconnected>(wear.state)
      assertTrue(phone.activationAction?.presentation?.value?.enabled == true)
      assertFalse(phone.deactivationAction?.presentation?.value?.enabled == true)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun claimIgnoresUnknownEmulatorsAndDevices(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = null,
          controller = FakeSimulatorController(),
          ui = testUi,
          ioDispatcher = Dispatchers.Unconfined,
        )

      assertNull(plugin.claim(FakeConnectedDevice("emulator-5554")))
      assertNull(plugin.claim(FakeConnectedDevice("R5CR123456A")))
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun activateStartsSimulatorClaimsDeviceAndDeactivateStopsIt(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val controller = FakeSimulatorController()
      val plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = "/tmp/sample-app",
          controller = controller,
          ui = testUi,
          ioDispatcher = Dispatchers.Default,
          connectTimeoutMillis = 5_000L,
        )
      val phone =
        plugin.devices.value.first { it.state.properties.deviceType == DeviceType.HANDHELD }
      val fakeDevice = FakeConnectedDevice("emulator-5554", AdbDeviceState.ONLINE)

      val activateJob = async { phone.activationAction!!.activate() }
      // Wait briefly until controller has started the simulator, then deliver the ADB device.
      repeat(50) {
        if (controller.runningInfo(SimulatorProfile.PHONE) != null) return@repeat
        delay(20)
      }
      val claimed = plugin.claim(fakeDevice)
      assertNotNull(claimed)
      assertEquals(phone.id, claimed.id)
      activateJob.await()

      val connected = assertIs<DeviceState.Connected>(phone.stateFlow.value)
      assertEquals(fakeDevice, connected.connectedDevice)
      assertTrue(connected.isOnline())
      assertEquals("emulator-5554", connected.properties.disambiguator)
      assertEquals(listOf(SimulatorProfile.PHONE), controller.startedProfiles)

      phone.deactivationAction!!.deactivate()
      assertEquals(listOf(SimulatorProfile.PHONE), controller.stoppedProfiles)
      assertIs<DeviceState.Disconnected>(phone.stateFlow.value)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun adbDisconnectReturnsHandleToDisconnected(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val controller = FakeSimulatorController()
      val plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = null,
          controller = controller,
          ui = testUi,
          ioDispatcher = Dispatchers.Unconfined,
        )
      controller.start(SimulatorProfile.WEAR, null)
      val fakeDevice = FakeConnectedDevice("emulator-5556", AdbDeviceState.ONLINE)

      val claimed = assertNotNull(plugin.claim(fakeDevice))
      assertIs<DeviceState.Connected>(claimed.stateFlow.value)

      fakeDevice.flow.value = DeviceInfo("emulator-5556", AdbDeviceState.DISCONNECTED)
      assertIs<DeviceState.Disconnected>(claimed.stateFlow.value)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun failedStartThrowsDeviceActionExceptionAndRecordsError(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val controller = FakeSimulatorController(failStart = true)
      val plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = null,
          controller = controller,
          ui = testUi,
          ioDispatcher = Dispatchers.Unconfined,
        )
      val phone =
        plugin.devices.value.first { it.state.properties.deviceType == DeviceType.HANDHELD }

      assertFailsWith<DeviceActionException> { phone.activationAction!!.activate() }
      val disconnected = assertIs<DeviceState.Disconnected>(phone.stateFlow.value)
      assertNotNull(disconnected.error)
      assertTrue(disconnected.error!!.message.contains("compose-preview not found"))
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun cancelDuringConnectWaitStopsProcessAndResetsTransitioningState(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val controller = FakeSimulatorController()
      val plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = null,
          controller = controller,
          ui = testUi,
          ioDispatcher = Dispatchers.Default,
          connectTimeoutMillis = 10_000L,
        )
      val phone =
        plugin.devices.value.first { it.state.properties.deviceType == DeviceType.HANDHELD }

      val activateJob = async { phone.activationAction!!.activate() }
      withTimeout(2_000L) {
        phone.stateFlow.first {
          it is DeviceState.Disconnected && it.isTransitioning && it.status.contains("Connecting")
        }
      }
      assertNotNull(controller.runningInfo(SimulatorProfile.PHONE))
      activateJob.cancelAndJoin()

      assertEquals(listOf(SimulatorProfile.PHONE), controller.stoppedProfiles)
      val state = assertIs<DeviceState.Disconnected>(phone.stateFlow.value)
      assertFalse(state.isTransitioning)
      val presentation =
        withTimeout(2_000L) { phone.activationAction!!.presentation.first { it.enabled } }
      assertTrue(presentation.enabled)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun activatePreservesClaimThatArrivesDuringStart(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val fakeDevice = FakeConnectedDevice("emulator-5554", AdbDeviceState.ONLINE)
      lateinit var plugin: SimulatorDeviceProvisionerPlugin
      val controller =
        FakeSimulatorController(
          onStart = { _, _ -> runBlocking { assertNotNull(plugin.claim(fakeDevice)) } },
        )
      plugin =
        SimulatorDeviceProvisionerPlugin(
          scope = scope,
          projectBasePath = null,
          controller = controller,
          ui = testUi,
          ioDispatcher = Dispatchers.Unconfined,
          connectTimeoutMillis = 1_000L,
        )
      val phone =
        plugin.devices.value.first { it.state.properties.deviceType == DeviceType.HANDHELD }

      phone.activationAction!!.activate()

      val connected = assertIs<DeviceState.Connected>(phone.stateFlow.value)
      assertEquals(fakeDevice, connected.connectedDevice)
      assertTrue(connected.isOnline())
    } finally {
      scope.cancel()
    }
  }
}
