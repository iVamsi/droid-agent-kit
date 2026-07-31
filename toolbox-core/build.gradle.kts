plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

/**
 * Coverage is gated on the classes that decide authority, not on a project-wide average — an
 * average can stay healthy while exactly the security-critical code rots. The floors sit below
 * current values so this is a regression alarm rather than a tripwire to be tuned constantly.
 */
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            element = "CLASS"
            includes =
                listOf(
                    "com.droidagentkit.core.DroidAgentConfigLoader",
                    "com.droidagentkit.core.SafetyConfig",
                    "com.droidagentkit.core.DefaultOperationPolicy",
                    "com.droidagentkit.core.Redactor",
                    "com.droidagentkit.core.DeviceIdentifiers",
                )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
