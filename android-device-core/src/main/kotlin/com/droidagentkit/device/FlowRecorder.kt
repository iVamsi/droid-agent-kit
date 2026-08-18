package com.droidagentkit.device

import java.time.Clock
import java.time.Instant

/**
 * One recorded interaction, named by the MCP tool that performed it.
 *
 * Arguments are kept as strings because they are replayed verbatim into `android_run_flow`, and
 * because a recorded flow is a document a human reads and edits, not an internal structure.
 */
data class FlowStep(
    val tool: String,
    val arguments: Map<String, String>,
    val atMillis: Long,
)

data class RecordedFlow(
    val name: String,
    val steps: List<FlowStep>,
    val recordedAt: Instant,
)

/**
 * Captures the device interactions an agent performs so they can be replayed later.
 *
 * `android_run_flow` can execute a flow but cannot produce one, so an exploratory agent session
 * leaves nothing behind. Recording turns it into a regression test.
 *
 * Recording observes calls that were already authorized and grants nothing. The device serial is
 * dropped from every step: it identifies the machine that recorded the flow, not the flow, and a
 * replay supplies its own.
 */
class FlowRecorder(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Any()
    private var name: String? = null
    private var startedAt: Instant? = null
    private val steps = mutableListOf<FlowStep>()

    val isRecording: Boolean get() = synchronized(lock) { name != null }

    fun start(flowName: String) {
        synchronized(lock) {
            check(name == null) { "A flow named '$name' is already being recorded; stop it first." }
            require(flowName.isNotBlank()) { "Flow name must not be blank." }
            name = flowName
            startedAt = clock.instant()
            steps.clear()
        }
    }

    /** No-op when not recording, so providers can call it unconditionally. */
    fun append(
        tool: String,
        arguments: Map<String, Any?>,
    ) {
        synchronized(lock) {
            val start = startedAt ?: return
            steps +=
                FlowStep(
                    tool = tool,
                    arguments =
                        arguments
                            .filterKeys { it !in EXCLUDED_ARGUMENTS }
                            .filterValues { it != null }
                            .mapValues { (_, value) -> value.toString() }
                            .toSortedMap(),
                    atMillis = clock.instant().toEpochMilli() - start.toEpochMilli(),
                )
        }
    }

    fun stop(): RecordedFlow =
        synchronized(lock) {
            val flowName = name ?: error("No flow is being recorded.")
            val flow = RecordedFlow(flowName, steps.toList(), startedAt ?: clock.instant())
            name = null
            startedAt = null
            steps.clear()
            flow
        }

    private companion object {
        /**
         * Identifies the machine that recorded the flow rather than the flow itself. Replay
         * supplies its own serial, and baking one in would make every recording non-portable.
         */
        val EXCLUDED_ARGUMENTS = setOf("deviceSerial", "confirmDestructive")
    }
}
