package ee.schimke.composeai.simulator.intellij

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import ee.schimke.composeai.uibuilder.intellij.UiBuilderSettings
import ee.schimke.composeai.uibuilder.intellij.notifyUiBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-scoped lifecycle manager for `compose-preview android-simulator --adb` processes.
 *
 * Shared by the Android Studio `DeviceProvisionerPlugin` (when `org.jetbrains.android` is loaded)
 * and the IDE Tools menu actions, guaranteeing every spawned simulator is stopped cleanly when the
 * IDE closes.
 */
/** Seam over [SimulatorManagerService] so the `DeviceProvisionerPlugin` can be unit-tested. */
internal interface SimulatorController {
  fun addChangeListener(listener: () -> Unit): Disposable

  fun runningInfo(profile: SimulatorProfile): SimulatorDeviceInfo?

  fun findProfileBySerial(serial: String): SimulatorProfile?

  fun start(profile: SimulatorProfile, projectBasePath: String? = null): SimulatorDeviceInfo

  fun stop(profile: SimulatorProfile)
}

internal class SimulatorManagerService
internal constructor(
  private val commandBuilder: (SimulatorProfile) -> List<String>,
) : Disposable, SimulatorController {
  @Suppress("unused")
  constructor() :
    this(
      { profile ->
        SimulatorDeviceEvents.buildLaunchCommand(
          UiBuilderSettings.resolveComposePreviewCli(),
          profile,
        )
      },
    )

  private val logger = Logger.getInstance(SimulatorManagerService::class.java)
  private val disposed = AtomicBoolean(false)
  private val running = ConcurrentHashMap<SimulatorProfile, RunningSimulatorProcess>()
  private val inFlightProcesses = ConcurrentHashMap<SimulatorProfile, Process>()
  private val stopEpochs = ConcurrentHashMap<SimulatorProfile, AtomicLong>()
  private val profileLocks = ConcurrentHashMap<SimulatorProfile, Any>()
  private val listeners = ConcurrentHashMap.newKeySet<() -> Unit>()

  override fun addChangeListener(listener: () -> Unit): Disposable {
    listeners.add(listener)
    return Disposable { listeners.remove(listener) }
  }

  override fun runningInfo(profile: SimulatorProfile): SimulatorDeviceInfo? {
    val proc = running[profile] ?: return null
    if (!proc.isAlive) {
      running.remove(profile, proc)
      return null
    }
    return proc.deviceInfo
  }

  internal fun isActiveOrStarting(profile: SimulatorProfile): Boolean =
    runningInfo(profile) != null || inFlightProcesses[profile]?.isAlive == true

  override fun findProfileBySerial(serial: String): SimulatorProfile? =
    running.entries
      .firstOrNull { (_, proc) -> proc.isAlive && proc.deviceInfo.serial == serial }
      ?.key

  override fun start(profile: SimulatorProfile, projectBasePath: String?): SimulatorDeviceInfo {
    val lock = profileLocks.computeIfAbsent(profile) { Any() }
    return synchronized(lock) {
      if (disposed.get()) {
        throw IllegalStateException("Compose Preview Simulator service is shutting down.")
      }
      runningInfo(profile)?.let {
        return@synchronized it
      }
      val epoch = stopEpoch(profile).get()
      val command = commandBuilder(profile)
      logger.info("Starting ${profile.displayName}: ${command.joinToString(" ")}")
      val startedRef = AtomicReference<RunningSimulatorProcess?>(null)
      val rawProcessRef = AtomicReference<Process?>(null)
      val started =
        try {
          RunningSimulatorProcess.start(
            command = command,
            workingDirectory = projectBasePath?.let(::File),
            onProcessStarted = { proc ->
              rawProcessRef.set(proc)
              inFlightProcesses[profile] = proc
              notifyListeners()
              if (stopEpoch(profile).get() != epoch || disposed.get()) {
                inFlightProcesses.remove(profile, proc)
                RunningSimulatorProcess.stopProcess(proc)
              }
            },
            onLog = { line -> logger.info("[${profile.id}] $line") },
            onExit = { code ->
              logger.info("${profile.displayName} exited with code $code")
              startedRef.get()?.let { exited -> running.remove(profile, exited) }
              notifyListeners()
            },
          )
        } finally {
          rawProcessRef.get()?.let { inFlightProcesses.remove(profile, it) }
        }
      if (stopEpoch(profile).get() != epoch || disposed.get()) {
        started.stop()
        notifyListeners()
        throw IllegalStateException("${profile.displayName} was stopped while starting.")
      }
      startedRef.set(started)
      val previous = running.put(profile, started)
      if (previous != null && previous !== started) {
        previous.stop()
      }
      if (!started.isAlive) {
        running.remove(profile, started)
      }
      notifyListeners()
      started.deviceInfo
    }
  }

  override fun stop(profile: SimulatorProfile) {
    stopEpoch(profile).incrementAndGet()
    val inFlight = inFlightProcesses.remove(profile)
    if (inFlight != null) {
      RunningSimulatorProcess.stopProcess(inFlight)
    }
    val lock = profileLocks.computeIfAbsent(profile) { Any() }
    val proc = synchronized(lock) { running.remove(profile) }
    proc?.stop()
    if (inFlight != null || proc != null) {
      notifyListeners()
    }
  }

  fun stopAll() {
    for (profile in SimulatorProfile.entries) {
      stopEpoch(profile).incrementAndGet()
      inFlightProcesses.remove(profile)?.let { RunningSimulatorProcess.stopProcess(it) }
    }
    for (profile in SimulatorProfile.entries) {
      stop(profile)
    }
  }

  override fun dispose() {
    disposed.set(true)
    stopAll()
  }

  private fun stopEpoch(profile: SimulatorProfile): AtomicLong =
    stopEpochs.computeIfAbsent(profile) { AtomicLong(0L) }

  private fun notifyListeners() {
    for (listener in listeners) {
      runCatching(listener)
    }
  }

  companion object {
    fun getInstance(): SimulatorManagerService =
      ApplicationManager.getApplication().getService(SimulatorManagerService::class.java)
  }
}

internal abstract class StartSimulatorAction(private val profile: SimulatorProfile) : AnAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project
    object : Task.Backgroundable(project, "Starting ${profile.displayName}…", true) {
        override fun run(indicator: ProgressIndicator) {
          indicator.isIndeterminate = true
          try {
            val info = SimulatorManagerService.getInstance().start(profile, project?.basePath)
            if (project != null) {
              notifyUiBuilder(
                project,
                "${profile.displayName} is ready on ${info.serial} (adb:${info.adbPort}). " +
                  "Select it in Android Studio's Device Manager / Run dropdown.",
              )
            }
          } catch (e: Exception) {
            if (project != null) {
              notifyUiBuilder(
                project,
                "Could not start ${profile.displayName}: ${e.message}",
                NotificationType.ERROR,
              )
            }
          }
        }
      }
      .queue()
  }
}

internal class StartPhoneSimulatorAction : StartSimulatorAction(SimulatorProfile.PHONE)

internal class StartWearSimulatorAction : StartSimulatorAction(SimulatorProfile.WEAR)

internal class StopSimulatorsAction : AnAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val svc = SimulatorManagerService.getInstance()
    event.presentation.isEnabled = SimulatorProfile.entries.any { svc.isActiveOrStarting(it) }
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project: Project? = event.project
    object : Task.Backgroundable(project, "Stopping Compose Preview Simulators…", false) {
        override fun run(indicator: ProgressIndicator) {
          SimulatorManagerService.getInstance().stopAll()
          if (project != null) {
            notifyUiBuilder(project, "Stopped running Compose Preview Simulators.")
          }
        }
      }
      .queue()
  }
}
