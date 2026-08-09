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
    fun `permission grant does not allow shell metacharacter injection via the permission argument`() {
        val root = Files.createTempDirectory("dak-permgrant-injection")
        val marker = root.resolve("injected.marker")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.PERMISSION_MUTATION)))

        val result =
            dispatcher.call(
                "android_permission_grant",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example",
                    "permission" to "android.permission.CAMERA; touch $marker",
                ),
            )

        assertEquals("success", result["status"])
        assertTrue(!Files.exists(marker))
    }

    @Test
    fun `deep link does not allow shell metacharacter injection via the uri argument`() {
        val root = Files.createTempDirectory("dak-deeplink-injection")
        val marker = root.resolve("injected.marker")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.APP_CONTROL)))

        dispatcher.call(
            "android_deep_link",
            mapOf(
                "rootPath" to root.toString(),
                "deviceSerial" to "emulator-5554",
                "uri" to "myapp://home; touch $marker",
            ),
        )

        assertTrue(!Files.exists(marker))
    }

    @Test
    fun `emulator snapshot save runs with the documented snapshotName argument`() {
        val root = Files.createTempDirectory("dak-snapshot-save-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.EMULATOR_CONTROL)))

        val result =
            dispatcher.call(
                "android_emulator_snapshot_save",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "snapshotName" to "checkpoint-1",
                ),
            )

        assertEquals("success", result["status"])
    }

    @Test
    fun `emulator snapshot save is blocked when snapshotName is missing`() {
        val root = Files.createTempDirectory("dak-snapshot-save-missing")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.EMULATOR_CONTROL)))

        val result =
            dispatcher.call(
                "android_emulator_snapshot_save",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-snapshot-name"))
    }

    @Test
    fun `emulator snapshot restore is blocked without confirmDestructive`() {
        val root = Files.createTempDirectory("dak-snapshot-restore-confirm")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.EMULATOR_RESTORE)))

        val result =
            dispatcher.call(
                "android_emulator_snapshot_restore",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "snapshotName" to "checkpoint-1",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("destructive-confirmation-required"))
    }

    @Test
    fun `emulator snapshot restore runs with the documented snapshotName argument and confirmDestructive`() {
        val root = Files.createTempDirectory("dak-snapshot-restore-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.EMULATOR_RESTORE)))

        val result =
            dispatcher.call(
                "android_emulator_snapshot_restore",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "snapshotName" to "checkpoint-1",
                    "confirmDestructive" to true,
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

    @Test
    fun `file push is blocked without confirmDestructive even with file_import capability`() {
        val root = Files.createTempDirectory("dak-push-confirm")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.FILE_IMPORT)))

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
        assertTrue((result["warnings"] as List<*>).contains("destructive-confirmation-required"))
    }

    @Test
    fun `file push is blocked when local path escapes the project root`() {
        val root = Files.createTempDirectory("dak-push-root")
        val outside = Files.createTempDirectory("dak-push-outside")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.FILE_IMPORT)))

        val result =
            dispatcher.call(
                "android_file_push",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "localPath" to outside.toString(),
                    "remotePath" to "/sdcard/x",
                    "confirmDestructive" to true,
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("host-path-denied"))
    }

    @Test
    fun `file push is blocked when remote path targets app-private storage`() {
        val root = Files.createTempDirectory("dak-push-private")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.FILE_IMPORT)))

        val result =
            dispatcher.call(
                "android_file_push",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "localPath" to root.toString(),
                    "remotePath" to "/data/data/com.other.app/files/x",
                    "confirmDestructive" to true,
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("device-path-denied"))
    }

    @Test
    fun `file push succeeds inside allowed host and device scopes`() {
        val root = Files.createTempDirectory("dak-push-ok")
        val localFile = root.resolve("payload.txt").also { Files.writeString(it, "hi") }
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.FILE_IMPORT)))

        val result =
            dispatcher.call(
                "android_file_push",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "localPath" to localFile.toString(),
                    "remotePath" to "/sdcard/x",
                    "confirmDestructive" to true,
                ),
            )

        assertEquals("success", result["status"])
    }

    @Test
    fun `file pull is blocked when remote path targets app-private storage`() {
        val root = Files.createTempDirectory("dak-pull-private")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.FILE_EXPORT)))

        val result =
            dispatcher.call(
                "android_file_pull",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "remotePath" to "/data/data/com.other.app/databases/accounts.db",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("device-path-denied"))
    }

    @Test
    fun `file pull succeeds for a public storage path`() {
        val root = Files.createTempDirectory("dak-pull-ok")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.FILE_EXPORT)))

        val result =
            dispatcher.call(
                "android_file_pull",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "remotePath" to "/sdcard/Download/report.pdf",
                ),
            )

        assertEquals("success", result["status"])
    }

    private fun fakeAdb(root: java.nio.file.Path): String {
        // These fakes are POSIX shell scripts, and that is load-bearing rather than incidental:
        // the `shell` branch re-evaluates joined argv the way a real device's /system/bin/sh does,
        // which is what lets them exercise shell-injection regressions at all. Reimplementing that
        // in batch would weaken the coverage it exists to provide, so on Windows these skip.
        org.junit.Assume.assumeTrue(
            "requires a POSIX shell for the fake adb/emulator scripts",
            !System.getProperty("os.name").startsWith("Windows"),
        )
        val script = root.resolve("fake-adb-ctl.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            # argv: ${'$'}1=-s ${'$'}2=serial ${'$'}3=verb ...
            # The `shell` branch reassembles and re-evaluates the remaining args the way a real
            # device's `/system/bin/sh -c "joined args"` would, instead of pattern-matching argv
            # positions directly — otherwise this fake could never exercise shell-injection
            # regressions, since ProcessBuilder delivers argv to this script pre-split.
            case "${'$'}3" in
              shell)
                shift 3
                pm() { echo "pm ${'$'}* ok"; }
                am() { echo "am ${'$'}* ok"; }
                input() { echo "input ok"; }
                eval "${'$'}@"
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
        // These fakes are POSIX shell scripts, and that is load-bearing rather than incidental:
        // the `shell` branch re-evaluates joined argv the way a real device's /system/bin/sh does,
        // which is what lets them exercise shell-injection regressions at all. Reimplementing that
        // in batch would weaken the coverage it exists to provide, so on Windows these skip.
        org.junit.Assume.assumeTrue(
            "requires a POSIX shell for the fake adb/emulator scripts",
            !System.getProperty("os.name").startsWith("Windows"),
        )
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

    @Test
    fun `flow recording captures interactions and writes all three formats`() {
        val root = Files.createTempDirectory("dak-flow-record")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL)))

        assertEquals("success", dispatcher.call("android_flow_record_start", mapOf("name" to "login"))["status"])
        dispatcher.call(
            "android_input_tap",
            mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "x" to 100, "y" to 200),
        )
        val stopped = dispatcher.call("android_flow_record_stop", mapOf("rootPath" to root.toString()))

        assertEquals("success", stopped["status"])
        val flowDir = root.resolve("build/droidagentkit/flows")
        assertTrue("run_flow json should exist", Files.exists(flowDir.resolve("login.json")))
        assertTrue("maestro yaml should exist", Files.exists(flowDir.resolve("login.yaml")))
        assertTrue("compose skeleton should exist", Files.exists(flowDir.resolve("login.kt")))
        assertTrue(
            "the recorded tap should be in the flow",
            Files.readString(flowDir.resolve("login.json")).contains("android_input_tap"),
        )
    }

    @Test
    fun `stopping without starting is refused`() {
        val root = Files.createTempDirectory("dak-flow-nostart")
        val dispatcher = dispatcher(root, controlConfig(root))

        val result = dispatcher.call("android_flow_record_stop", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
    }

    @Test
    fun `starting a second recording is refused rather than silently replacing the first`() {
        val root = Files.createTempDirectory("dak-flow-double")
        val dispatcher = dispatcher(root, controlConfig(root))
        dispatcher.call("android_flow_record_start", mapOf("name" to "one"))

        val second = dispatcher.call("android_flow_record_start", mapOf("name" to "two"))

        assertEquals("blocked", second["status"])
    }

    @Test
    fun `a denied interaction is not recorded`() {
        // Recording after the fact and only on success keeps a flow from replaying a step that
        // never actually worked -- the capability here is deliberately absent.
        val root = Files.createTempDirectory("dak-flow-denied")
        val dispatcher = dispatcher(root, controlConfig(root, emptySet()))
        dispatcher.call("android_flow_record_start", mapOf("name" to "denied"))

        dispatcher.call(
            "android_input_tap",
            mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "x" to 1, "y" to 2),
        )
        val stopped = dispatcher.call("android_flow_record_stop", mapOf("rootPath" to root.toString()))

        assertEquals("partial", stopped["status"])
        assertTrue(
            "an unauthorized step must not end up in a replayable flow",
            !Files.readString(root.resolve("build/droidagentkit/flows/denied.json")).contains("android_input_tap"),
        )
    }

    @Test
    fun `android_doctor reports checks and stays read-only`() {
        val root = Files.createTempDirectory("dak-doctor-tool")
        val dispatcher = dispatcher(root, controlConfig(root))

        val result = dispatcher.call("android_doctor", mapOf("rootPath" to root.toString()))

        @Suppress("UNCHECKED_CAST")
        val checks = result["checks"] as List<Map<*, *>>
        assertTrue("should report several checks", checks.size >= 5)
        assertTrue("java must be among them", checks.any { it["name"] == "java" })
        val doctorTool = dispatcher.listTools().first { it.name == "android_doctor" }
        assertEquals(true, doctorTool.annotations["readOnlyHint"])
    }

    @Test
    fun `tap by element refuses when the selector is missing`() {
        val root = Files.createTempDirectory("dak-tap-elem-nosel")
        val dispatcher = dispatcher(root, controlConfig(root, setOf(Capability.DEVICE_INPUT)))

        val result =
            dispatcher.call(
                "android_input_tap_element",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"),
            )

        assertEquals("blocked", result["status"])
        @Suppress("UNCHECKED_CAST")
        val warnings = result["warnings"] as List<String>
        assertTrue("should say what is missing: $warnings", warnings.contains("missing-selector"))
    }

    @Test
    fun `tap by element still requires the device_input capability`() {
        // Resolving by label must not become a way around the capability that gates tapping.
        val root = Files.createTempDirectory("dak-tap-elem-nocap")
        val dispatcher = dispatcher(root, controlConfig(root, emptySet()))

        val result =
            dispatcher.call(
                "android_input_tap_element",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "text" to "Sign in"),
            )

        assertEquals("blocked", result["status"])
    }

    @Test
    fun `tap by element is listed with the other input tools`() {
        val root = Files.createTempDirectory("dak-tap-elem-list")
        val dispatcher = dispatcher(root, controlConfig(root))

        assertTrue(dispatcher.listTools().map { it.name }.contains("android_input_tap_element"))
    }
}
