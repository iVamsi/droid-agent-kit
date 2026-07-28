package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class McpDispatcherTest {
    @Test
    fun `dispatcher lists expected android tools`() {
        val root = Files.createTempDirectory("dak-tool-list")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val tools = dispatcher.listTools().map { it.name }

        assertEquals(
            listOf(
                "android_project_inspect",
                "android_gradle_run",
                "android_devices_list",
                "android_app_install",
                "android_app_launch",
                "android_logcat_capture",
                "android_screen_snapshot",
                "android_accessibility_snapshot",
                "android_report_bundle",
                "android_lint_run",
                "android_crash_triage",
                "android_dependency_check",
                "android_build_performance",
                "android_test_run",
                "android_build_diagnose",
            ),
            tools,
        )
    }

    @Test
    fun `gradle run blocks denied task`() {
        val root = Files.createTempDirectory("dak-mcp")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_gradle_run", mapOf("rootPath" to root.toString(), "task" to "clean"))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("not allowlisted"))
    }

    @Test
    fun `project inspect returns useful partial result`() {
        val root = Files.createTempDirectory("dak-mcp-inspect")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"McpDemo\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_project_inspect", mapOf("rootPath" to root.toString()))

        assertEquals("success", result["status"])
        assertTrue(result["summary"].toString().contains("McpDemo"))
    }

    @Test
    fun `snapshot is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-snapshot")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_screen_snapshot", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("deviceSerial"))
    }

    @Test
    fun `accessibility snapshot is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-accessibility")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_accessibility_snapshot", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("deviceSerial"))
    }

    @Test
    fun `each tool exposes an input schema with type object and properties`() {
        val root = Files.createTempDirectory("dak-tool-schema")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val tools = dispatcher.listTools()

        assertEquals(15, tools.size)
        tools.forEach { tool ->
            assertEquals("tool ${tool.name} missing type:object", "object", tool.inputSchema["type"])
            assertTrue(
                "tool ${tool.name} missing properties",
                tool.inputSchema.containsKey("properties"),
            )
        }
    }

    @Test
    fun `gradle run tool schema marks task as required`() {
        val root = Files.createTempDirectory("dak-gradle-schema")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val gradleTool = dispatcher.listTools().first { it.name == "android_gradle_run" }

        @Suppress("UNCHECKED_CAST")
        val required = gradleTool.inputSchema["required"] as List<*>
        assertTrue(required.contains("task"))
    }

    @Test
    fun `report bundle writes structured markdown with modules table and safe commands`() {
        val root = Files.createTempDirectory("dak-bundle")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            "rootProject.name = \"BundleTest\"\ninclude(\":app\")",
        )
        Files.createDirectories(root.resolve("app/src/test/java"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            "plugins { id(\"com.android.application\") }\nandroid { namespace = \"com.example.bundle\" }",
        )
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_report_bundle", mapOf("rootPath" to root.toString()))

        assertEquals("success", result["status"])
        val artifact = (result["artifacts"] as List<*>).first() as Map<*, *>
        val content =
            java.nio.file.Files
                .readString(
                    java.nio.file.Path
                        .of(artifact["path"].toString()),
                )
        assertTrue(content.contains("## Modules"))
        assertTrue(content.contains("## Safe Commands"))
        assertTrue(content.contains(":app"))
        assertTrue(content.contains("Readiness:"))
        assertTrue("report must include ## Key Versions section", content.contains("## Key Versions"))
        assertTrue("report must include ## Warnings section", content.contains("## Warnings"))
    }

    @Test
    fun `lint run blocks denied task`() {
        val root = Files.createTempDirectory("dak-lint-denied")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_lint_run", mapOf("rootPath" to root.toString(), "task" to "clean"))

        assertEquals("blocked", result["status"])
    }

    @Test
    fun `dispatcher rejects a caller supplied project root`() {
        val root = Files.createTempDirectory("dak-root")
        val otherRoot = Files.createTempDirectory("dak-other-root")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_project_inspect", mapOf("rootPath" to otherRoot.toString()))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("project-root-denied"))
    }

    @Test
    fun `gradle run rejects unsafe extra arguments`() {
        val root = Files.createTempDirectory("dak-gradle-args")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:assembleDebug")),
            )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result =
            dispatcher.call(
                "android_gradle_run",
                mapOf(
                    "rootPath" to root.toString(),
                    "task" to ":app:assembleDebug",
                    "arguments" to listOf("--init-script", "malicious.gradle.kts"),
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("gradle-argument-denied"))
    }

    @Test
    fun `lint run parses android lint xml report into findings`() {
        val root = Files.createTempDirectory("dak-lint-xml")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:lintDebug")),
            )
        writeFakeGradlew(root)
        val reportDir = root.resolve("app/build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(
            reportDir.resolve("lint-results-debug.xml"),
            """
            <issues>
              <issue id="HardcodedText" severity="Warning" message="Hardcoded string">
                <location file="src/main/res/layout/main.xml" line="12"/>
              </issue>
            </issues>
            """.trimIndent(),
        )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result = dispatcher.call("android_lint_run", mapOf("rootPath" to root.toString(), "task" to ":app:lintDebug"))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals(1, findings.size)
        assertEquals("HardcodedText", findings[0]["title"])
        assertEquals("warning", findings[0]["severity"])
    }

    @Test
    fun `lint run returns partial status when no structured report is found`() {
        val root = Files.createTempDirectory("dak-lint-none")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:ktlintCheck")),
            )
        writeFakeGradlew(root)
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result = dispatcher.call("android_lint_run", mapOf("rootPath" to root.toString(), "task" to ":app:ktlintCheck"))

        assertEquals("partial", result["status"])
        @Suppress("UNCHECKED_CAST")
        val warnings = result["warnings"] as List<*>
        assertTrue(warnings.contains("no-structured-lint-report-found"))
    }

    private fun writeFakeGradlew(root: java.nio.file.Path) {
        val wrapper = root.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(
            wrapper,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )
    }

    @Test
    fun `crash triage is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-crash-triage")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_crash_triage", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("deviceSerial"))
    }

    @Test
    fun `dependency check flags version drift across modules`() {
        val root = Files.createTempDirectory("dak-dispatch-dep")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("core"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.11.0\")")
        Files.writeString(root.resolve("core/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result = dispatcher.call("android_dependency_check", mapOf("rootPath" to root.toString()))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals(1, findings.size)
    }

    @Test
    fun `build performance parses slowest tasks from the profile report`() {
        val root = Files.createTempDirectory("dak-build-perf")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:assembleDebug")),
            )
        writeFakeGradlew(root)
        val profileDir = root.resolve("build/reports/profile")
        Files.createDirectories(profileDir)
        Files.writeString(
            profileDir.resolve("profile-test.html"),
            """
            <html><body>
            <div class="tab" id="tab0">
            <table>
            <tr><td>Total Build Time</td><td class="numeric">0.487s</td></tr>
            </table>
            </div>
            <div class="tab" id="tab4">
            <table>
            <tr><td>:toolbox-core</td><td class="numeric">0.021s</td><td>(total)</td></tr>
            <tr><td class="indentPath">:toolbox-core:compileKotlin</td><td class="numeric">0.012s</td><td>UP-TO-DATE</td></tr>
            </table>
            </div>
            </body></html>
            """.trimIndent(),
        )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result = dispatcher.call("android_build_performance", mapOf("rootPath" to root.toString(), "task" to ":app:assembleDebug"))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals(1, findings.size)
        assertEquals(":toolbox-core:compileKotlin", findings[0]["title"])
    }

    @Test
    fun `test run returns junit summary and structured failures`() {
        val root = Files.createTempDirectory("dak-test-run")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:testDebugUnitTest")),
            )
        writeFakeGradlew(root)
        val results = root.resolve("app/build/test-results/testDebugUnitTest")
        Files.createDirectories(results)
        Files.writeString(
            results.resolve("TEST-example.xml"),
            """
            <testsuite tests="1" failures="1" errors="0" skipped="0" time="0.1">
              <testcase classname="example.Test" name="fails"><failure message="boom"/></testcase>
            </testsuite>
            """.trimIndent(),
        )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result =
            dispatcher.call(
                "android_test_run",
                mapOf("rootPath" to root.toString(), "task" to ":app:testDebugUnitTest", "mode" to "unit"),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val summary = result["testSummary"] as Map<String, Any>
        assertEquals(1, summary["tests"])
        assertEquals(1, summary["failures"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (result["findings"] as List<*>).size)
    }

    @Test
    fun `build diagnose preserves unknown failures and classifies known compiler output`() {
        val root = Files.createTempDirectory("dak-build-diagnose")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:assembleDebug")),
            )
        val wrapper = root.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\necho \"e: file:///project/App.kt:4:2 Unresolved reference\"\nexit 1\n")
        Files.setPosixFilePermissions(
            wrapper,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result =
            dispatcher.call(
                "android_build_diagnose",
                mapOf("rootPath" to root.toString(), "task" to ":app:assembleDebug"),
            )

        assertEquals("failed", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals("compiler.kotlin", findings.single()["category"])
    }

    @Test
    fun `app launch does not allow shell metacharacter injection via packageName`() {
        val root = Files.createTempDirectory("dak-launch-injection")
        val marker = root.resolve("injected.marker")
        val adb = root.resolve("fake-adb-launch.sh")
        Files.writeString(
            adb,
            """
            #!/bin/bash
            case "${'$'}3" in
              shell)
                shift 3
                am() { echo "am ${'$'}* ok"; }
                eval "${'$'}@"
                ;;
              *) echo "ok" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(
            adb,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(adbPath = adb.toString()),
            )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result =
            dispatcher.call(
                "android_app_launch",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example; touch $marker",
                ),
            )

        assertEquals("success", result["status"])
        assertTrue(!Files.exists(marker))
    }

    @Test
    fun `app install blocks apkPath outside the project root`() {
        val root = Files.createTempDirectory("dak-apk-confine")
        val outside = Files.createTempDirectory("dak-apk-outside")
        val apk = outside.resolve("app.apk")
        Files.writeString(apk, "fake")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val result =
            dispatcher.call(
                "android_app_install",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "apkPath" to apk.toString(),
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("host-path-denied"))
    }

    @Test
    fun `app install is blocked when APP_INSTALL capability is disabled`() {
        val root = Files.createTempDirectory("dak-apk-cap")
        val apk = root.resolve("app.apk")
        Files.writeString(apk, "fake")
        val config =
            DroidAgentConfig.default().copy(
                safety =
                    DroidAgentConfig.default().safety.copy(
                        allowAppInstall = false,
                        allowCapabilities = emptySet(),
                    ),
            )
        val dispatcher = DroidAgentMcpDispatcher(config, root)

        val result =
            dispatcher.call(
                "android_app_install",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "apkPath" to apk.toString(),
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }
}
