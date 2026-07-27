plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

group = "com.droidagentkit"
version = "0.1.0-alpha"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // ktlint-gradle 14.2.0 pulls ktlint-cli 1.5.0 -> logback 1.3.14, which has several fixed CVEs
    // (GHSA-25qh-j22f-pwp8, GHSA-6v67-2wr5-gvf4, GHSA-pr98-23f8-jwxv). Force the patched 1.3.x
    // release for ktlint's own configurations only; logback is never a runtime/test dependency here.
    configurations.matching { it.name.startsWith("ktlint") }.configureEach {
        resolutionStrategy {
            force("ch.qos.logback:logback-classic:1.3.16", "ch.qos.logback:logback-core:1.3.16")
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
