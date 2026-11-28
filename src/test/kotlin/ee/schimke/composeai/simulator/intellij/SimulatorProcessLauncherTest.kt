package ee.schimke.composeai.simulator.intellij

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorProcessLauncherTest {

  @Test
  fun parsesDeviceHostReadyEvent() {
    val line =
      """{"event":"device","serial":"emulator-5554","adbPort":5555,"consolePort":5554,"grpcPort":8554,"avd":"Compose_Simulator_Phone_API_35","formFactor":"phone"}"""
    val parsed = SimulatorDeviceEvents.parseDeviceEvent(line)
    assertNotNull(parsed)
    assertEquals("emulator-5554", parsed.serial)
    assertEquals(5555, parsed.adbPort)
    assertEquals(5554, parsed.consolePort)
    assertEquals(8554, parsed.grpcPort)
    assertEquals("Compose_Simulator_Phone_API_35", parsed.avdName)
    assertEquals("phone", parsed.formFactor)
  }

  @Test
  fun ignoresNonDeviceLinesAndOtherEvents() {
    assertNull(SimulatorDeviceEvents.parseDeviceEvent(""))
    assertNull(SimulatorDeviceEvents.parseDeviceEvent("not json"))
    assertNull(SimulatorDeviceEvents.parseDeviceEvent("""{"event":"ready","info":{}}"""))
    assertNull(SimulatorDeviceEvents.parseDeviceEvent("""{"event":"device","serial":""}"""))
  }

  @Test
  fun buildsPhoneAndWearLaunchCommands() {
    assertEquals(
      listOf("compose-preview", "android-simulator", "--adb", "--control", "stdio"),
      SimulatorDeviceEvents.buildLaunchCommand("", SimulatorProfile.PHONE),
    )
    assertEquals(
      listOf(
        "/opt/bin/compose-preview",
        "android-simulator",
        "--adb",
        "--control",
        "stdio",
        "--form-factor",
        "wear",
        "--sdk",
        "35",
      ),
      SimulatorDeviceEvents.buildLaunchCommand(
        "/opt/bin/compose-preview",
        SimulatorProfile.WEAR,
        apiLevel = 35,
      ),
    )
  }

  @Test
  fun startsProcessWaitsForDeviceEventAndStopsWhenStdinCloses() {
    val dir = Files.createTempDirectory("sim-proc-test").toFile()
    val script = File(dir, "fake-sim.sh")
    script.writeText(
      """
      #!/usr/bin/env bash
      set -euo pipefail
      echo '{"event":"device","serial":"emulator-5560","adbPort":5561,"consolePort":5560,"grpcPort":8560,"avd":"Sim_Phone","formFactor":"phone"}'
      # Stay alive until stdin closes (matching DeviceHost.bridgeStdin).
      cat > /dev/null
      """
        .trimIndent() + "\n",
    )
    script.setExecutable(true)

    val exited = CountDownLatch(1)
    val running =
      RunningSimulatorProcess.start(
        command = listOf(script.absolutePath),
        startupTimeoutSeconds = 10,
        onExit = { exited.countDown() },
      )
    assertEquals("emulator-5560", running.deviceInfo.serial)
    assertEquals(8560, running.deviceInfo.grpcPort)
    assertTrue(running.isAlive)

    running.stop()
    assertTrue(exited.await(5, TimeUnit.SECONDS))
    assertFalse(running.isAlive)
    dir.deleteRecursively()
  }

  @Test
  fun failsFastWhenProcessExitsWithoutDeviceEvent() {
    val dir = Files.createTempDirectory("sim-proc-fail").toFile()
    val script = File(dir, "fail-sim.sh")
    script.writeText(
      """
      #!/usr/bin/env bash
      echo "missing Java 21" >&2
      exit 1
      """
        .trimIndent() + "\n",
    )
    script.setExecutable(true)

    val error =
      assertFailsWith<IllegalStateException> {
        RunningSimulatorProcess.start(
          command = listOf(script.absolutePath),
          startupTimeoutSeconds = 5,
        )
      }
    assertTrue(error.message?.contains("missing Java 21") == true, error.message)
    dir.deleteRecursively()
  }

  @Test
  fun stopAllCancelsInFlightLaunchAndStopsReadySimulator() {
    val dir = Files.createTempDirectory("sim-stop-all").toFile()
    val phoneScript = File(dir, "phone-sim.sh")
    phoneScript.writeText(
      """
      #!/usr/bin/env bash
      set -euo pipefail
      echo '{"event":"device","serial":"emulator-5562","adbPort":5563,"consolePort":5562,"grpcPort":8562,"avd":"Sim_Phone","formFactor":"phone"}'
      cat > /dev/null
      """
        .trimIndent() + "\n",
    )
    phoneScript.setExecutable(true)

    val wearScript = File(dir, "wear-slow-sim.sh")
    wearScript.writeText(
      """
      #!/usr/bin/env bash
      set -euo pipefail
      # Block before emitting the device event until stdin closes.
      cat > /dev/null
      """
        .trimIndent() + "\n",
    )
    wearScript.setExecutable(true)

    val service = SimulatorManagerService { profile ->
      when (profile) {
        SimulatorProfile.PHONE -> listOf(phoneScript.absolutePath)
        SimulatorProfile.WEAR -> listOf(wearScript.absolutePath)
      }
    }
    try {
      val phoneInfo = service.start(SimulatorProfile.PHONE)
      assertEquals("emulator-5562", phoneInfo.serial)

      val wearFinished = CountDownLatch(1)
      var wearError: Throwable? = null
      val wearThread = Thread {
        try {
          service.start(SimulatorProfile.WEAR)
        } catch (t: Throwable) {
          wearError = t
        } finally {
          wearFinished.countDown()
        }
      }
      wearThread.start()

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (!service.isActiveOrStarting(SimulatorProfile.WEAR) && System.nanoTime() < deadline) {
        Thread.sleep(20)
      }
      assertTrue(service.isActiveOrStarting(SimulatorProfile.WEAR))

      service.stopAll()
      assertTrue(wearFinished.await(5, TimeUnit.SECONDS))
      assertNotNull(wearError)
      assertNull(service.runningInfo(SimulatorProfile.PHONE))
      assertNull(service.runningInfo(SimulatorProfile.WEAR))
      assertFalse(service.isActiveOrStarting(SimulatorProfile.PHONE))
      assertFalse(service.isActiveOrStarting(SimulatorProfile.WEAR))
    } finally {
      service.dispose()
      dir.deleteRecursively()
    }
  }
}
