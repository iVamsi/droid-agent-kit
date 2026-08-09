package com.droidagentkit.device

import com.droidagentkit.core.Json

/**
 * Renders a [RecordedFlow] into the formats a team actually keeps.
 *
 * Three targets, deliberately: the native one replays through `android_run_flow` with no other
 * tooling; Maestro YAML is where most mobile teams already keep flows; and the Compose test is a
 * starting point for a repository that would rather own the assertion in Kotlin. All three are
 * pure functions of the flow so they can be golden-tested without a device.
 */
object FlowEmitters {
    /** Round-trips through `android_run_flow`'s `actions` argument. */
    fun toRunFlowJson(flow: RecordedFlow): String =
        Json.write(
            mapOf(
                "name" to flow.name,
                "recordedAt" to flow.recordedAt.toString(),
                "actions" to
                    flow.steps.map { step ->
                        mapOf("tool" to step.tool, "arguments" to step.arguments)
                    },
            ),
        )

    /**
     * Maestro YAML. Steps Maestro has no equivalent for are emitted as comments rather than
     * dropped: a silently shorter flow is worse than one that says what it could not express.
     */
    fun toMaestroYaml(flow: RecordedFlow): String =
        buildString {
            val appId = flow.steps.firstNotNullOfOrNull { it.arguments["packageName"] } ?: "com.example.app"
            appendLine("# Recorded by DroidAgentKit from ${flow.name} at ${flow.recordedAt}")
            appendLine("appId: $appId")
            appendLine("---")
            flow.steps.forEach { step ->
                when (step.tool) {
                    "android_app_launch" -> appendLine("- launchApp")
                    "android_input_tap" -> {
                        val x = step.arguments["x"]
                        val y = step.arguments["y"]
                        appendLine("- tapOn:")
                        appendLine("    point: $x,$y")
                    }
                    "android_input_tap_element" ->
                        appendLine(
                            "- tapOn: ${quote(step.arguments["text"] ?: step.arguments["resourceId"] ?: "")}",
                        )
                    "android_input_type" -> appendLine("- inputText: ${quote(step.arguments["text"] ?: "")}")
                    "android_input_key" -> appendLine("- pressKey: ${step.arguments["keycode"] ?: step.arguments["key"] ?: ""}")
                    "android_input_swipe" -> {
                        appendLine("- swipe:")
                        appendLine("    start: ${step.arguments["startX"]},${step.arguments["startY"]}")
                        appendLine("    end: ${step.arguments["endX"]},${step.arguments["endY"]}")
                    }
                    "android_deep_link" -> appendLine("- openLink: ${quote(step.arguments["url"] ?: "")}")
                    else -> appendLine("# unsupported in Maestro: ${step.tool} ${step.arguments}")
                }
            }
        }

    /**
     * A Compose UI test skeleton. Deliberately a starting point rather than a finished test: only
     * the human knows what the flow was supposed to *assert*, so the file ends with an explicit
     * reminder instead of a fabricated assertion that would pass for the wrong reason.
     */
    fun toComposeTest(flow: RecordedFlow): String {
        val className =
            flow.name
                .split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotBlank() }
                .joinToString("") { part ->
                    part.replaceFirstChar { it.uppercase() }
                }.ifBlank { "RecordedFlow" } + "Test"
        return buildString {
            appendLine("package com.example.flows")
            appendLine()
            appendLine("import androidx.compose.ui.test.junit4.createAndroidComposeRule")
            appendLine("import androidx.compose.ui.test.onNodeWithText")
            appendLine("import androidx.compose.ui.test.performClick")
            appendLine("import androidx.compose.ui.test.performTextInput")
            appendLine("import org.junit.Rule")
            appendLine("import org.junit.Test")
            appendLine()
            appendLine("/** Recorded by DroidAgentKit from '${flow.name}' at ${flow.recordedAt}. */")
            appendLine("class $className {")
            appendLine("    @get:Rule val rule = createAndroidComposeRule<MainActivity>()")
            appendLine()
            appendLine("    @Test")
            appendLine("    fun `${flow.name} replays`() {")
            flow.steps.forEach { step ->
                when (step.tool) {
                    "android_input_tap_element" ->
                        appendLine("        rule.onNodeWithText(${kotlinString(step.arguments["text"] ?: "")}).performClick()")
                    "android_input_type" ->
                        appendLine(
                            "        rule.onNodeWithText(${kotlinString(step.arguments["target"] ?: "")}).performTextInput(${kotlinString(
                                step.arguments["text"] ?: "",
                            )})",
                        )
                    "android_input_tap" ->
                        appendLine("        // tap at (${step.arguments["x"]}, ${step.arguments["y"]}) -- replace with a semantic matcher")
                    else -> appendLine("        // ${step.tool} ${step.arguments}")
                }
            }
            appendLine()
            appendLine("        // TODO: assert what this flow was meant to verify. A recording captures")
            appendLine("        // the steps, not the intent -- only you know which assertion makes this a test.")
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    private fun kotlinString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
