package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DeviceControlToolProviderTest {
    private fun controlConfig(
        root: java.nio.file.Path,
        capabilities: Set<Capability> = emptySet(),
    ): DroidAgentConfig {
        val base = DroidAgentConfig.default()
        return base.copy(
            safety =
                base.safety.copy(
                    allowCapabilities = capabilities,
                    adbPath = fakeAdb(root),
                    emulatorPath = fakeEmulator(root),
                ),
        )
    }

    private fun dispatcher(
        root: java.nio.file.Path,
        config: DroidAgentConfig,
    ): DroidAgentMcpDispatcher =
        DroidAgentMcpDispatcher(
            config = config,
            projectRoot = root,
            exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_CONTROL),
        )

    @Test
    fun `device-control tools are listed only when the group is exposed`() {
        val root = Files.createTempDirectory("dak-control-list")
        val dispatcher = dispatcher(root, controlConfig(root))

        val names = dispatcher.listTools().map { it.name }
        assertTrue(names.contains("android_emulator_list_avds"))
        assertTrue(names.contains("android_emulator_start"))
        assertTrue(names.contains("android_app_uninstall"))
        assertTrue(names.contains("android_input_tap"))
        assertTrue(names.contains("android_permission_grant"))
        assertTrue(names.contains("android_file_pull"))
        assertTrue(names.contains("android_run_flow"))
    }

    @Test
    fun `device-control tools are hidden when the group is not exposed`() {
        val root = Files.createTempDirectory("dak-control-hidden")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val names = dispatcher.listTools().map { it.name }
        assertTrue(!names.contains("android_input_tap"))
        assertTrue(!names.contains("android_app_uninstall"))
    }

    @Test
    fun `input tap is blocked when device_input capability is not enabled`() {
        val root = Files.createTempDirectory("dak-tap-cap")
        val dispatcher = dispatcher(root, controlConfig(root))

        val result =
            dispatcher.call(
                "android_input_tap",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "x" to 10,
                    "y" to 20,
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `input tap is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-tap-serial")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.DEVICE_INPUT)))

        val result = dispatcher.call("android_input_tap", mapOf("rootPath" to root.toString(), "x" to 10, "y" to 20))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-device-serial"))
    }

    @Test
    fun `input tap runs with device_input capability enabled`() {
        val root = Files.createTempDirectory("dak-tap-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.DEVICE_INPUT)))

        val result =
            dispatcher.call(
                "android_input_tap",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "x" to 10,
                    "y" to 20,
                ),
            )

        assertEquals("success", result["status"])
    }

    @Test
    fun `app uninstall is blocked without confirmDestructive`() {
        val root = Files.createTempDirectory("dak-uninstall-confirm")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.APP_DESTRUCTIVE)))

        val result =
            dispatcher.call(
                "android_app_uninstall",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("destructive-confirmation-required"))
    }

    @Test
    fun `app uninstall runs with confirmDestructive`() {
        val root = Files.createTempDirectory("dak-uninstall-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.APP_DESTRUCTIVE)))

        val result =
            dispatcher.call(
                "android_app_uninstall",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example",
                    "confirmDestructive" to true,
                ),
            )

        assertEquals("success", result["status"])
    }

    @Test
    fun `permission grant runs with permission_mutation capability`() {
        val root = Files.createTempDirectory("dak-permgrant-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.PERMISSION_MUTATION)))

        val result =
            dispatcher.call(
                "android_permission_grant",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example",
                    "permission" to "android.permission.CAMERA",
                ),
            )

        assertEquals("success", result["status"])
    }

    @Test
    fun `emulator list avds returns names from hermetic emulator`() {
        val root = Files.createTempDirectory("dak-avds-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.EMULATOR_CONTROL)))

        val result = dispatcher.call("android_emulator_list_avds", mapOf("rootPath" to root.toString()))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val avds = result["avds"] as List<String>
        assertTrue(avds.contains("Pixel_5"))
        assertTrue(avds.contains("Pixel_6"))
    }

    @Test
    fun `deep link is blocked when app_control capability is not enabled`() {
        val root = Files.createTempDirectory("dak-deeplink-cap")
        val dispatcher = dispatcher(root, controlConfig(root))

        val result =
            dispatcher.call(
                "android_deep_link",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "uri" to "myapp://home",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `run_flow executes a single input step`() {
        val root = Files.createTempDirectory("dak-flow-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL)))

        @Suppress("UNCHECKED_CAST")
        val actions =
            listOf<Map<String, Any?>>(
                mapOf("tool" to "android_input_tap", "arguments" to mapOf("x" to 1, "y" to 2)),
            )
        val result =
            dispatcher.call(
                "android_run_flow",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "actions" to actions),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val steps = result["steps"] as List<Map<String, Any>>
        assertEquals(1, steps.size)
        assertEquals("success", steps[0]["status"])
    }

    @Test
    fun `file push is blocked when file_import capability is not enabled`() {
        val root = Files.createTempDirectory("dak-push-cap")
        val dispatcher = dispatcher(root, controlConfig(root))

        val result =
            dispatcher.call(
                "android_file_push",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "localPath" to root.toString(),
                    "remotePath" to "/sdcard/x",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    private fun fakeAdb(root: java.nio.file.Path): String {
        val script = root.resolve("fake-adb-ctl.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            # argv: ${'$'}1=-s ${'$'}2=serial ${'$'}3=verb ...
            case "${'$'}3" in
              shell)
                case "${'$'}4" in
                  pm)    echo "pm ${'$'}5 ok" ;;
                  am)    echo "am ${'$'}5 ok" ;;
                  input) echo "input ok" ;;
                  *)     echo "shell ${'$'}4" ;;
                esac
                ;;
              emu)       echo "emu ${'$'}4 ok" ;;
              uninstall) echo "uninstall ok" ;;
              install)   echo "install ok" ;;
              pull)      printf 'pulled' > "${'$'}5"; echo "pull ok" ;;
              push)      echo "push ok" ;;
              *)         echo "unknown adb command" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }

    private fun fakeEmulator(root: java.nio.file.Path): String {
        val script = root.resolve("fake-emulator.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            case "${'$'}1" in
              -list-avds) echo "Pixel_5"; echo "Pixel_6" ;;
              *)          echo "emulator ${'$'}1 ${'$'}2" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }
}
