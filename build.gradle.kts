plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

group = "com.droidagentkit"
version = "0.2.4-alpha"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // JaCoCo rather than Kover: Kover 0.9.1 is the latest release and reads
    // `compilation.compileKotlinTask`, which the Kotlin 2.4.0 Gradle plugin no longer exposes.
    apply(plugin = "jacoco")

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
        finalizedBy(tasks.withType<JacocoReport>())
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}
