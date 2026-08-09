plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
}

group = "com.droidagentkit"
version = "0.2.6-alpha"

// Unfiltered, project-wide coverage. toolbox-core narrows its own report to the classes its
// verification rule gates, so the whole-project view lives here.
dependencies {
    kover(project(":toolbox-core"))
    kover(project(":mcp-server"))
    kover(project(":android-inspector"))
    kover(project(":android-device-core"))
    kover(project(":storage-inspector"))
    kover(project(":network-core"))
    kover(project(":perfetto-core"))
    kover(project(":auditor-cli"))
    kover(project(":visuals-core"))
    kover(project(":cli"))
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // Kover rather than JaCoCo: this is a pure-Kotlin project, and Kover understands Kotlin
    // constructs (inline functions, synthetic members) that JaCoCo miscounts. Requires >= 0.9.2;
    // 0.9.1 reads `compilation.compileKotlinTask`, which Kotlin 2.4.0's Gradle plugin removed.
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = false
        ignoreFailures = false
        basePath = rootProject.projectDir.absolutePath
    }

    // ktlint-gradle 14.2.0 pulls ktlint-cli 1.5.0 -> logback 1.3.14, which has several fixed CVEs.
    // Three (GHSA-25qh-j22f-pwp8, GHSA-6v67-2wr5-gvf4, GHSA-pr98-23f8-jwxv) are patched within the
    // 1.3.x branch, but three more (GHSA-jhq6-gfmj-v8fx, GHSA-p47f-322f-whfh, GHSA-qqpg-mvqg-649v)
    // only have fixes in 1.5.x, so force the current latest release outright. Scoped to ktlint's
    // own configurations only; logback is never a runtime/test dependency here.
    configurations.matching { it.name.startsWith("ktlint") }.configureEach {
        resolutionStrategy {
            force("ch.qos.logback:logback-classic:1.6.0", "ch.qos.logback:logback-core:1.6.0")
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
    }

    tasks.withType<Test>().configureEach {
        systemProperty("java.awt.headless", "true")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

/**
 * Per-module coverage floors beyond toolbox-core's policy-class gate.
 *
 * A single project-wide average can stay healthy while one module rots, so each of these carries
 * its own rule. The floors sit roughly ten points below the measured value at the time they were
 * added (mcp-server 84%, android-device-core 91%, android-inspector 94%, auditor-cli 96%): the
 * point is to catch a real regression, not to force a retune on every commit.
 *
 * toolbox-core is deliberately absent -- it configures a stricter, class-filtered rule of its own.
 */
val coverageFloors =
    mapOf(
        ":mcp-server" to 75,
        ":android-device-core" to 80,
        ":android-inspector" to 85,
        ":auditor-cli" to 85,
    )

coverageFloors.forEach { (path, floor) ->
    project(path) {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
            reports {
                verify {
                    rule("$path line coverage") {
                        minBound(floor)
                    }
                }
            }
        }
    }
}
