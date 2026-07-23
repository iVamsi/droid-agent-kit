package com.droidagentkit.mcp

/**
 * An MCP prompt template. [requiredTools] names the tools the prompt's workflow depends on; the
 * registry only advertises a prompt when every required tool is exposed by the server.
 */
data class McpPrompt(
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument>,
    val requiredTools: Set<String>,
    val build: (Map<String, String>) -> List<Map<String, Any>>,
)

data class McpPromptArgument(
    val name: String,
    val description: String,
    val required: Boolean,
)

/**
 * Registry of MCP prompts. [list] filters prompts whose required tools are all exposed, so a
 * prompt is never advertised to a host that cannot execute its workflow.
 */
class McpPromptRegistry {
    private val prompts = linkedMapOf<String, McpPrompt>()

    fun register(prompt: McpPrompt) {
        prompts[prompt.name] = prompt
    }

    fun list(exposedToolNames: Set<String>): List<McpPrompt> =
        prompts.values.filter { it.requiredTools.isEmpty() || it.requiredTools.all { t -> t in exposedToolNames } }

    fun get(
        name: String,
        exposedToolNames: Set<String>,
    ): McpPrompt? {
        val prompt = prompts[name] ?: return null
        if (prompt.requiredTools.isNotEmpty() && !prompt.requiredTools.all { it in exposedToolNames }) return null
        return prompt
    }
}

/** Builds the standard DroidAgentKit prompt catalog. Each prompt returns user-role guidance. */
object McpPrompts {
    fun registerAll(registry: McpPromptRegistry) {
        registry.register(crashInvestigation())
        registry.register(anrEvidenceReview())
        registry.register(buildFailureFix())
        registry.register(testFailureTriage())
        registry.register(permissionAudit())
        registry.register(appStartupProfile())
        registry.register(visualRegressionReview())
        registry.register(dependencyUpgrade())
    }

    private fun text(text: String): Map<String, Any> = mapOf("role" to "user", "content" to mapOf("type" to "text", "text" to text))

    private fun crashInvestigation() =
        McpPrompt(
            name = "crash-investigation",
            description = "Triage a runtime crash: pull logcat, locate the stack, and propose a fix scoped to the project.",
            arguments = listOf(McpPromptArgument("packageName", "Android package name to filter logs by.", true)),
            requiredTools = setOf("android_logcat_start", "android_logcat_capture", "android_crash_triage"),
            build = { args ->
                val pkg = args["packageName"].orEmpty()
                listOf(
                    text(
                        "Investigate a crash for package '$pkg'. Steps:\n" +
                            "1. Use android_logcat_start (filter by pid for '$pkg') to capture a bounded log buffer.\n" +
                            "2. Use android_crash_triage to extract the fatal stack and component.\n" +
                            "3. Locate the throwing source in the project, propose a minimal fix, and verify with android_build_diagnose.",
                    ),
                )
            },
        )

    private fun anrEvidenceReview() =
        McpPrompt(
            name = "anr-evidence-review",
            description = "Collect ANR evidence: capture the trace, summarize the blocked main thread, and identify the culprit.",
            arguments = listOf(McpPromptArgument("packageName", "Android package name under test.", true)),
            requiredTools = setOf("android_logcat_start", "android_dumpsys"),
            build = { args ->
                val pkg = args["packageName"].orEmpty()
                listOf(
                    text(
                        "Review an ANR for '$pkg'. Steps:\n" +
                            "1. Use android_dumpsys (cpuinfo + package presets) to capture main-thread state.\n" +
                            "2. Use android_logcat_start to capture the surrounding events.\n" +
                            "3. Identify the blocking call on the main thread and propose a fix that moves work off the main thread.",
                    ),
                )
            },
        )

    private fun buildFailureFix() =
        McpPrompt(
            name = "build-failure-fix",
            description = "Reproduce a failing Gradle build, classify the error, and apply a targeted fix.",
            arguments = listOf(McpPromptArgument("task", "Gradle task that failed, e.g. :app:assembleDebug.", true)),
            requiredTools = setOf("android_gradle_run", "android_build_diagnose", "android_lint_run"),
            build = { args ->
                val task = args["task"].orEmpty()
                listOf(
                    text(
                        "Fix a failing build for task '$task'. Steps:\n" +
                            "1. Use android_gradle_run to reproduce the failure and capture the log.\n" +
                            "2. Use android_build_diagnose to classify the error.\n" +
                            "3. If lint-related, use android_lint_run to get the SARIF findings.\n" +
                            "4. Apply the minimal fix and re-run the task to confirm green.",
                    ),
                )
            },
        )

    private fun testFailureTriage() =
        McpPrompt(
            name = "test-failure-triage",
            description = "Reproduce a failing test, capture output, and isolate the cause.",
            arguments = listOf(McpPromptArgument("task", "Gradle test task, e.g. :app:testDebugUnitTest.", true)),
            requiredTools = setOf("android_test_run", "android_gradle_run"),
            build = { args ->
                val task = args["task"].orEmpty()
                listOf(
                    text(
                        "Triage a failing test for '$task'. Steps:\n" +
                            "1. Use android_test_run to reproduce and capture the failure report.\n" +
                            "2. Use android_gradle_run if a clean re-run is needed.\n" +
                            "3. Isolate the failing assertion, fix, and re-run until green.",
                    ),
                )
            },
        )

    private fun permissionAudit() =
        McpPrompt(
            name = "permission-audit",
            description = "Audit runtime permission grants for a package and flag ungranted required permissions.",
            arguments = listOf(McpPromptArgument("packageName", "Android package name to audit.", true)),
            requiredTools = setOf("android_permission_audit", "android_permission_grant"),
            build = { args ->
                val pkg = args["packageName"].orEmpty()
                listOf(
                    text(
                        "Audit permissions for '$pkg'. Steps:\n" +
                            "1. Use android_permission_audit to list runtime permissions and their grant state.\n" +
                            "2. Flag any required-but-ungranted permission.\n" +
                            "3. If intentional, use android_permission_grant to grant it and re-audit.",
                    ),
                )
            },
        )

    private fun appStartupProfile() =
        McpPrompt(
            name = "app-startup-profile",
            description = "Profile app startup: capture a trace, measure cold-start duration, and find slow spans.",
            arguments = listOf(McpPromptArgument("packageName", "Android package name to launch.", true)),
            requiredTools = setOf("android_app_launch", "android_screen_snapshot"),
            build = { args ->
                val pkg = args["packageName"].orEmpty()
                listOf(
                    text(
                        "Profile startup for '$pkg'. Steps:\n" +
                            "1. Use android_app_launch to cold-start the app.\n" +
                            "2. Use android_screen_snapshot to confirm the first drawn frame.\n" +
                            "3. Identify slow startup spans and propose targeted deferral or backgrounding.",
                    ),
                )
            },
        )

    private fun visualRegressionReview() =
        McpPrompt(
            name = "visual-regression-review",
            description = "Review a visual regression diff and decide whether to update the golden or fix the UI.",
            arguments = listOf(McpPromptArgument("case", "Screenshot test case name.", true)),
            requiredTools = setOf("android_screen_snapshot"),
            build = { args ->
                val case = args["case"].orEmpty()
                listOf(
                    text(
                        "Review visual regression for case '$case'. Steps:\n" +
                            "1. Use android_screen_snapshot to capture the current rendering.\n" +
                            "2. Compare against the golden; classify the diff as intended or a regression.\n" +
                            "3. If intended, update the golden; if a regression, fix the UI and re-capture.",
                    ),
                )
            },
        )

    private fun dependencyUpgrade() =
        McpPrompt(
            name = "dependency-upgrade",
            description = "Plan a dependency upgrade: inspect the matrix, check for advisories, and stage the bump.",
            arguments = listOf(McpPromptArgument("dependency", "Dependency coordinate to upgrade, e.g. androidx.core:core-ktx.", false)),
            requiredTools = setOf("android_project_inspect", "android_dependency_check"),
            build = { args ->
                val dep = args["dependency"].orEmpty().ifBlank { "all outdated dependencies" }
                listOf(
                    text(
                        "Upgrade $dep. Steps:\n" +
                            "1. Use android_project_inspect to read the current version matrix.\n" +
                            "2. Use android_dependency_check to surface advisories or version drift.\n" +
                            "3. Stage the bump, run android_build_diagnose, and confirm tests still pass.",
                    ),
                )
            },
        )
}
