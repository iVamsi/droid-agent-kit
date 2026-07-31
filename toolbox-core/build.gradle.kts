plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

/**
 * Coverage is gated on the classes that decide authority, not on a project-wide average — an
 * average can stay healthy while exactly the security-critical code rots. The floor sits below
 * current values so this is a regression alarm rather than a tripwire to be tuned constantly.
 *
 * Kover 0.9.9's `KoverVerifyRule` exposes only bounds and `groupBy`, with no per-rule filter, so
 * the class list has to be applied at report level. That narrows *this module's* report to those
 * classes; the root project aggregates an unfiltered report across every module, so full coverage
 * visibility is preserved there.
 */
kover {
    reports {
        filters {
            includes {
                classes(
                    "com.droidagentkit.core.DroidAgentConfigLoader",
                    "com.droidagentkit.core.SafetyConfig",
                    "com.droidagentkit.core.DefaultOperationPolicy",
                    "com.droidagentkit.core.Redactor",
                    "com.droidagentkit.core.DeviceIdentifiers",
                )
            }
        }
        verify {
            rule("authority-deciding classes") {
                minBound(85)
            }
        }
    }
}
