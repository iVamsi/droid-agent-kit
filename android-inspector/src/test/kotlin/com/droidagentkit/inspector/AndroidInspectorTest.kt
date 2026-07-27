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
        assertEquals(
            "com.example.demo.MainActivity",
            report.modules
                .first()
                .launcherActivities
                .single(),
        )
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
    fun `inspector resolves library aliases from version catalog`() {
        val root = Files.createTempDirectory("dak-catalog")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            "rootProject.name = \"CatalogTest\"\n",
        )
        Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [versions]
            kotlin = "2.1.0"
            hilt = "2.54"

            [libraries]
            hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
            kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }

            [plugins]
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        assertEquals("2.1.0", report.versions["kotlin"])
        assertEquals("2.54", report.versions["hilt"])
        assertEquals("2.54", report.versions["hilt-android"])
        assertEquals("2.1.0", report.versions["kotlin-stdlib"])
        assertEquals("2.1.0", report.versions["kotlin-android"])
    }

    @Test
    fun `inspector resolves module type through an included build-logic convention plugin`() {
        val root = Files.createTempDirectory("dak-convention-plugin")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            """
            pluginManagement { includeBuild("build-logic") }
            rootProject.name = "ConventionApp"
            include(":app")
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [plugins]
            myapp-android-application = { id = "myapp.android.application" }
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("app/src/main"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            """
            plugins { alias(libs.plugins.myapp.android.application) }
            android { namespace = "com.example.conventionapp" }
            """.trimIndent(),
        )

        val pluginSrcDir = root.resolve("build-logic/convention/src/main/kotlin")
        Files.createDirectories(pluginSrcDir)
        Files.writeString(
            root.resolve("build-logic/convention/build.gradle.kts"),
            """
            plugins { `kotlin-dsl` }
            gradlePlugin {
                plugins {
                    register("androidApplication") {
                        id = libs.plugins.myapp.android.application.get().pluginId
                        implementationClass = "AndroidApplicationConventionPlugin"
                    }
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            pluginSrcDir.resolve("AndroidApplicationConventionPlugin.kt"),
            """
            class AndroidApplicationConventionPlugin : Plugin<Project> {
                override fun apply(target: Project) {
                    target.apply(plugin = "com.android.application")
                }
            }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        assertEquals(AndroidModuleType.APPLICATION, report.modules.single().type)
    }

    @Test
    fun `inspector resolves module type through a chain of two convention plugins`() {
        val root = Files.createTempDirectory("dak-convention-chain")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            """
            pluginManagement { includeBuild("build-logic") }
            rootProject.name = "ChainApp"
            include(":feature:api")
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [plugins]
            myapp-android-library = { id = "myapp.android.library" }
            myapp-feature-api = { id = "myapp.feature.api" }
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("feature/api/src/main"))
        Files.writeString(
            root.resolve("feature/api/build.gradle.kts"),
            """
            plugins { alias(libs.plugins.myapp.feature.api) }
            android { namespace = "com.example.chainapp.feature.api" }
            """.trimIndent(),
        )

        val pluginSrcDir = root.resolve("build-logic/convention/src/main/kotlin")
        Files.createDirectories(pluginSrcDir)
        Files.writeString(
            root.resolve("build-logic/convention/build.gradle.kts"),
            """
            plugins { `kotlin-dsl` }
            gradlePlugin {
                plugins {
                    register("androidLibrary") {
                        id = libs.plugins.myapp.android.library.get().pluginId
                        implementationClass = "AndroidLibraryConventionPlugin"
                    }
                    register("featureApi") {
                        id = libs.plugins.myapp.feature.api.get().pluginId
                        implementationClass = "FeatureApiConventionPlugin"
                    }
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            pluginSrcDir.resolve("AndroidLibraryConventionPlugin.kt"),
            """
            class AndroidLibraryConventionPlugin : Plugin<Project> {
                override fun apply(target: Project) {
                    target.apply(plugin = "com.android.library")
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            pluginSrcDir.resolve("FeatureApiConventionPlugin.kt"),
            """
            class FeatureApiConventionPlugin : Plugin<Project> {
                override fun apply(target: Project) {
                    target.apply(plugin = "myapp.android.library")
                }
            }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        assertEquals(AndroidModuleType.LIBRARY, report.modules.single().type)
    }

    @Test
    fun `inspector recognizes com android test as a standalone test module`() {
        val root = Files.createTempDirectory("dak-test-module")
        Files.writeString(
            root.resolve("settings.gradle.kts"),
            """
            rootProject.name = "TestModuleApp"
            include(":benchmarks")
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("benchmarks/src/main"))
        Files.writeString(
            root.resolve("benchmarks/build.gradle.kts"),
            """
            plugins { id("com.android.test") }
            android { namespace = "com.example.benchmarks" }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        assertEquals(AndroidModuleType.TEST_MODULE, report.modules.single().type)
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

    @Test
    fun `inspector normalizes Gradle includes without leading colons`() {
        val root = Files.createTempDirectory("dak-modern-includes")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Includes\"\ninclude(\"cli\", \"feature:scan\")")
        Files.createDirectories(root.resolve("cli"))
        Files.writeString(root.resolve("cli/build.gradle.kts"), "plugins { kotlin(\"jvm\") }")
        Files.createDirectories(root.resolve("feature/scan"))
        Files.writeString(root.resolve("feature/scan/build.gradle.kts"), "plugins { id(\"com.android.library\") }")

        val report = AndroidProjectInspector().inspect(root)

        assertEquals(listOf(":cli", ":feature:scan"), report.modules.map { it.path })
        assertEquals(AndroidModuleType.JVM_TOOLING, report.modules.first().type)
    }

    @Test
    fun `inspector recognizes AGP 9 built in Kotlin and documented toolchain mismatch`() {
        val root = Files.createTempDirectory("dak-agp9")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Agp9\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("gradle/wrapper"))
        Files.writeString(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip",
        )
        Files.createDirectories(root.resolve("gradle"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [versions]
            agp = "9.2.0"
            kotlin = "2.3.20"

            [plugins]
            android-application = { id = "com.android.application", version.ref = "agp" }
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("app/src/main/kotlin"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            """
            plugins { alias(libs.plugins.android.application) }
            android {
                namespace = "com.example.agp9"
                compileSdk = 37
                defaultConfig { minSdk = 24; targetSdk = 37 }
            }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        val app = report.modules.single()
        assertEquals(AndroidModuleType.APPLICATION, app.type)
        assertEquals(KotlinIntegration.BUILT_IN, app.kotlinIntegration)
        assertEquals(37, app.compileSdk)
        assertEquals(24, app.minSdk)
        assertEquals(37, app.targetSdk)
        assertEquals(
            CompatibilityStatus.OUTSIDE_DOCUMENTED_RANGE,
            report.toolchain.findings
                .first { it.component == "kotlin-gradle" }
                .status,
        )
    }

    @Test
    fun `inspector recognizes Android KMP source sets tests and single variant model`() {
        val root = Files.createTempDirectory("dak-kmp")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Kmp\"\ninclude(\":shared\")")
        Files.createDirectories(root.resolve("shared/src/androidMain"))
        Files.createDirectories(root.resolve("shared/src/androidHostTest"))
        Files.createDirectories(root.resolve("shared/src/androidDeviceTest"))
        Files.writeString(
            root.resolve("shared/build.gradle.kts"),
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.android.kotlin.multiplatform.library")
            }
            kotlin {
                android {
                    namespace = "com.example.shared"
                    compileSdk = 37
                    minSdk = 24
                    withHostTestBuilder {}
                    withDeviceTestBuilder {}
                }
            }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)

        val shared = report.modules.single()
        assertEquals(AndroidModuleType.KMP_ANDROID, shared.type)
        assertEquals(KotlinIntegration.MULTIPLATFORM, shared.kotlinIntegration)
        assertEquals(listOf("androidDeviceTest", "androidHostTest", "androidMain"), shared.sourceSets)
        assertTrue(shared.hasUnitTests)
        assertTrue(shared.hasAndroidTests)
        assertTrue(report.commandMatrix.none { "testDebugUnitTest" in it.command })
    }

    @Test
    fun `inspector discovers managed device groups and official screenshot validation`() {
        val root = Files.createTempDirectory("dak-modern-tests")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Tests\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app/src/screenshotTestDebug"))
        Files.writeString(
            root.resolve("app/build.gradle.kts"),
            """
            plugins {
                id("com.android.application")
                id("com.android.compose.screenshot")
            }
            android {
                namespace = "com.example.tests"
                testOptions {
                    managedDevices {
                        localDevices { create("pixelApi37") { apiLevel = 37 } }
                        groups { create("phones") { } }
                    }
                }
            }
            """.trimIndent(),
        )

        val report = AndroidProjectInspector().inspect(root)
        val app = report.modules.single()

        assertEquals(listOf("pixelApi37"), app.managedDevices)
        assertEquals(listOf("phones"), app.managedDeviceGroups)
        assertTrue(app.hasScreenshotTests)
        assertTrue(report.commandMatrix.any { it.command.last() == ":app:pixelApi37DebugAndroidTest" })
        assertTrue(report.commandMatrix.any { it.command.last() == ":app:phonesGroupDebugAndroidTest" })
        assertTrue(report.commandMatrix.any { it.command.last() == ":app:validateDebugScreenshotTest" })
    }
}
