package ee.schimke.composeai.simulator.intellij

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Form factor profiles exposed as virtual devices in Android Studio's Device Manager. */
internal enum class SimulatorProfile(
  val id: String,
  val displayName: String,
  val formFactorArg: String?,
  val model: String,
  val manufacturer: String,
  val isWear: Boolean,
  val widthPx: Int,
  val heightPx: Int,
  val densityDpi: Int,
  val defaultApiLevel: Int = 35,
  val defaultAndroidRelease: String = "15",
) {
  PHONE(
    id = "compose-preview-simulator-phone",
    displayName = "Compose Preview Simulator (Phone)",
    formFactorArg = null,
    model = "Simulator (Phone)",
    manufacturer = "Compose Preview",
    isWear = false,
    widthPx = 1080,
    heightPx = 2400,
    densityDpi = 420,
  ),
  WEAR(
    id = "compose-preview-simulator-wear",
    displayName = "Compose Preview Simulator (Wear)",
    formFactorArg = "wear",
    model = "Simulator (Wear)",
    manufacturer = "Compose Preview",
    isWear = true,
    widthPx = 454,
    heightPx = 454,
    densityDpi = 320,
  ),
}

/**
 * Metadata emitted by `compose-preview android-simulator --adb --control stdio` once the virtual
 * ADB/console/gRPC ports are listening and registered with the ADB server.
 */
internal data class SimulatorDeviceInfo(
  val serial: String,
  val adbPort: Int,
  val consolePort: Int,
  val grpcPort: Int?,
  val avdName: String?,
  val formFactor: String,
)

/** Parses stdout/stderr events from `compose-preview android-simulator --adb --control stdio`. */
internal object SimulatorDeviceEvents {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Parses a `{"event":"device","serial":"emulator-5554",...}` JSON line emitted by `DeviceHost`.
   * Returns null for non-JSON lines or other event types.
   */
  internal fun parseDeviceEvent(line: String): SimulatorDeviceInfo? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{") || !trimmed.contains("\"device\"")) return null
    val obj =
      runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
    if (obj.string("event") != "device") return null
    val serial = obj.string("serial")?.takeIf { it.isNotBlank() } ?: return null
    val adbPort = obj.int("adbPort") ?: return null
    val consolePort = obj.int("consolePort") ?: (adbPort - 1)
    return SimulatorDeviceInfo(
      serial = serial,
      adbPort = adbPort,
      consolePort = consolePort,
      grpcPort = obj.int("grpcPort"),
      avdName = obj.string("avd"),
      formFactor = obj.string("formFactor") ?: "phone",
    )
  }

  /** Builds the CLI argv for launching [profile] as an ADB/gRPC device. */
  internal fun buildLaunchCommand(
    cliExecutable: String,
    profile: SimulatorProfile,
    apiLevel: Int? = null,
  ): List<String> = buildList {
    add(cliExecutable.ifBlank { "compose-preview" })
    add("android-simulator")
    add("--adb")
    add("--control")
    add("stdio")
    profile.formFactorArg?.let {
      add("--form-factor")
      add(it)
    }
    apiLevel?.let {
      add("--sdk")
      add(it.toString())
    }
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * Owns one running `compose-preview android-simulator --adb --control stdio` child process.
 *
 * Closing stdin signals `DeviceHost` to shut down the simulator and clean up its discovery file;
 * [stop] falls back to `destroy()` / `destroyForcibly()` if the process does not exit promptly.
 */
internal class RunningSimulatorProcess
private constructor(
  private val process: Process,
  val deviceInfo: SimulatorDeviceInfo,
) {
  private val stopped = AtomicBoolean(false)

  val isAlive: Boolean
    get() = process.isAlive

  fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    runCatching { process.outputStream.close() }
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroy()
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
      }
    }
  }

  internal companion object {
    /**
     * Starts [command] and waits up to [startupTimeoutSeconds] for `DeviceHost`'s `device` event
     * carrying the `emulator-<port>` serial.
     */
    internal fun start(
      command: List<String>,
      workingDirectory: File? = null,
      startupTimeoutSeconds: Long = 90,
      onLog: (String) -> Unit = {},
      onExit: (Int) -> Unit = {},
    ): RunningSimulatorProcess {
      val pb = ProcessBuilder(command)
      if (workingDirectory != null && workingDirectory.isDirectory) {
        pb.directory(workingDirectory)
      }
      val process = pb.start()
      val ready = CompletableFuture<SimulatorDeviceInfo>()
      val recentStderr = ArrayDeque<String>()

      val stderrThread =
        Thread(
            {
              BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                lines.forEach { line ->
                  synchronized(recentStderr) {
                    if (recentStderr.size >= 40) recentStderr.removeFirst()
                    recentStderr.addLast(line)
                  }
                  onLog(line)
                }
              }
            },
            "compose-simulator-stderr",
          )
          .apply {
            isDaemon = true
            start()
          }

      val stdoutThread =
        Thread(
            {
              BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                  if (!ready.isDone) {
                    SimulatorDeviceEvents.parseDeviceEvent(line)?.let { ready.complete(it) }
                  }
                  onLog(line)
                }
              }
              val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
              if (!ready.isDone) {
                val tail =
                  synchronized(recentStderr) { recentStderr.takeLast(10).joinToString("\n") }
                ready.completeExceptionally(
                  IllegalStateException(
                    buildString {
                      append("compose-preview android-simulator exited ($exitCode) before ")
                      append("announcing an ADB device.")
                      if (tail.isNotBlank()) append("\n").append(tail)
                    },
                  ),
                )
              }
              onExit(exitCode)
            },
            "compose-simulator-stdout",
          )
          .apply {
            isDaemon = true
            start()
          }

      val info =
        try {
          ready.get(startupTimeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
          runCatching { process.outputStream.close() }
          runCatching { process.destroyForcibly() }
          stderrThread.interrupt()
          stdoutThread.interrupt()
          throw IllegalStateException(
            "Timed out or failed starting Compose Preview Simulator: ${e.message}",
            e,
          )
        }

      return RunningSimulatorProcess(process, info)
    }
  }
}
