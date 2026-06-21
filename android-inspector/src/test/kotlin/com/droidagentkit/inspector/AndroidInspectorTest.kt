package com.droidagentkit.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AndroidInspectorTest {
    @Test
    fun `inspector detects modules plugins versions manifest and commands`() {
        val root = Files.createTempDirectory("dak-android-project")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) }
            rootProject.name = "DemoApp"
            include(":app", ":feature:scan")
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [versions]
            agp = "9.2.0"
            kotlin = "2.3.20"
            composeBom = "2026.06.00"
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("app/src/main"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android { namespace = "com.example.demo" }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("app/src/main/AndroidManifest.xml"),
            """
            <manifest package="com.example.demo">
              <application>
                <activity android:name=".MainActivity" android:exported="true">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
                </activity>
              </application>
            </manifest>
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("feature/scan"))
        Files.writeString(
            root.resolve("feature/scan/build.gradle.kts"),
            """
            plugins { id("com.android.library") }
            android { namespace = "com.example.scan" }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        assertEquals("DemoApp", report.projectName)
        assertEquals("9.2.0", report.versions["agp"])
        assertEquals("2.3.20", report.versions["kotlin"])
        assertEquals(listOf(":app", ":feature:scan"), report.modules.map { it.path })
        assertEquals(AndroidModuleType.APPLICATION, report.modules.first().type)
        assertEquals("com.example.demo", report.modules.first().namespace)
        assertEquals("com.example.demo.MainActivity", report.modules.first().launcherActivities.single())
        assertTrue(report.commandMatrix.any { it.id == "app-test-unit" })
    }

    @Test
    fun `inspector captures inter-module project dependencies`() {
        val root = Files.createTempDirectory("dak-deps")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            "rootProject.name = \"Deps\"\ninclude(\":app\", \":core\", \":ui\")",
        )
        Files.createDirectories(root.resolve("app"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            """
            plugins { id("com.android.application") }
            android { namespace = "com.example.app" }
            dependencies {
                implementation(project(":ui"))
                implementation(project(":core"))
            }
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("core"))
        Files.writeString(
            root.resolve("core/build.gradle.kts"),
            "plugins { id(\"com.android.library\") }\nandroid { namespace = \"com.example.core\" }",
        )
        Files.createDirectories(root.resolve("ui"))
        Files.writeString(
            root.resolve("ui/build.gradle.kts"),
            "plugins { id(\"com.android.library\") }\nandroid { namespace = \"com.example.ui\" }",
        )

        val report = AndroidProjectInspector().inspect(root)

        val appModule = report.modules.first { it.path == ":app" }
        assertEquals(listOf(":core", ":ui"), appModule.moduleDependencies)
        val coreModule = report.modules.first { it.path == ":core" }
        assertTrue(coreModule.moduleDependencies.isEmpty())
        val uiModule = report.modules.first { it.path == ":ui" }
        assertTrue(uiModule.moduleDependencies.isEmpty())
    }

    @Test
    fun `inspector extracts build types and product flavors from application module`() {
        val root = Files.createTempDirectory("dak-variants")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            "rootProject.name = \"Variants\"\ninclude(\":app\")",
        )
        Files.createDirectories(root.resolve("app"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            """
            plugins { id("com.android.application") }
            android {
                namespace = "com.example.variants"
                buildTypes {
                    release { minifyEnabled = true }
                    staging { initWith(debug) }
                }
                productFlavors {
                    demo { dimension = "tier" }
                    full { dimension = "tier" }
                }
            }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        val app = report.modules.first()
        assertTrue(app.buildTypes.contains("release"))
        assertTrue(app.buildTypes.contains("staging"))
        assertTrue(app.productFlavors.contains("demo"))
        assertTrue(app.productFlavors.contains("full"))
        // flavor-specific commands should be generated
        assertTrue(report.commandMatrix.any { ":app:testDemoDebugUnitTest" in it.command })
    }

    @Test
    fun `inspector returns partial report for broken or non android projects`() {
        val root = Files.createTempDirectory("dak-broken-project")
        Files.writeString(root.resolve("README.md"), "not an Android project")

        val report = AndroidProjectInspector().inspect(root)

        assertEquals(ProjectSupport.PARTIAL, report.support)
        assertTrue(report.warnings.any { it.contains("settings.gradle") })
        assertTrue(report.modules.isEmpty())
    }
}
