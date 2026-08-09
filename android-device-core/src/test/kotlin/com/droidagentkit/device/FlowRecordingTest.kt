package com.droidagentkit.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private class TestClock(
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z"),
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

class FlowRecordingTest {
    private fun recordSample(clock: TestClock = TestClock()): RecordedFlow {
        val recorder = FlowRecorder(clock)
        recorder.start("login")
        recorder.append("android_app_launch", mapOf("packageName" to "com.example.app", "deviceSerial" to "emulator-5554"))
        clock.advance(Duration.ofMillis(500))
        recorder.append("android_input_tap_element", mapOf("text" to "Sign in", "deviceSerial" to "emulator-5554"))
        recorder.append("android_input_type", mapOf("text" to "hunter2", "target" to "Password"))
        return recorder.stop()
    }

    @Test
    fun `appending outside a recording is a no-op rather than an error`() {
        // Providers call append unconditionally on every input tool, so this is the common path.
        val recorder = FlowRecorder(TestClock())

        recorder.append("android_input_tap", mapOf("x" to 1, "y" to 2))

        assertTrue(!recorder.isRecording)
        assertThrows(IllegalStateException::class.java) { recorder.stop() }
    }

    @Test
    fun `starting twice is refused`() {
        val recorder = FlowRecorder(TestClock())
        recorder.start("first")

        val error = assertThrows(IllegalStateException::class.java) { recorder.start("second") }

        assertTrue("should name the running flow: ${error.message}", error.message!!.contains("first"))
    }

    @Test
    fun `the device serial is not baked into a recording`() {
        // A flow with a serial in it only replays on the machine that recorded it.
        val flow = recordSample()

        assertTrue(
            "no step may carry a serial: ${flow.steps}",
            flow.steps.none { it.arguments.containsKey("deviceSerial") },
        )
    }

    @Test
    fun `steps keep their order and relative timing`() {
        val flow = recordSample()

        assertEquals(listOf("android_app_launch", "android_input_tap_element", "android_input_type"), flow.steps.map { it.tool })
        assertEquals(0L, flow.steps[0].atMillis)
        assertEquals(500L, flow.steps[1].atMillis)
    }

    @Test
    fun `stopping resets the recorder for the next flow`() {
        val recorder = FlowRecorder(TestClock())
        recorder.start("one")
        recorder.append("android_input_tap", mapOf("x" to 1, "y" to 2))
        recorder.stop()

        recorder.start("two")
        val second = recorder.stop()

        assertEquals("two", second.name)
        assertTrue("the second flow must not inherit the first's steps", second.steps.isEmpty())
    }

    @Test
    fun `the run-flow emitter round-trips into the actions shape run_flow consumes`() {
        val json = FlowEmitters.toRunFlowJson(recordSample())

        // android_run_flow reads `actions: [{tool, arguments}]`; anything else silently replays nothing.
        assertTrue("must carry actions: $json", json.contains("\"actions\":["))
        assertTrue(json.contains("\"tool\":\"android_app_launch\""))
        assertTrue(json.contains("\"arguments\":"))
        assertTrue("packageName should survive", json.contains("com.example.app"))
    }

    @Test
    fun `the maestro emitter maps known steps and flags the ones it cannot express`() {
        val recorder = FlowRecorder(TestClock())
        recorder.start("checkout")
        recorder.append("android_app_launch", mapOf("packageName" to "com.example.shop"))
        recorder.append("android_input_tap_element", mapOf("text" to "Buy"))
        recorder.append("android_input_type", mapOf("text" to "4111 1111"))
        recorder.append("android_file_pull", mapOf("remotePath" to "/sdcard/x"))
        val yaml = FlowEmitters.toMaestroYaml(recorder.stop())

        assertTrue("appId comes from the launched package: $yaml", yaml.contains("appId: com.example.shop"))
        assertTrue(yaml.contains("- launchApp"))
        assertTrue(yaml.contains("- tapOn: \"Buy\""))
        assertTrue(yaml.contains("- inputText: \"4111 1111\""))
        // Silently dropping a step would produce a flow that looks complete and is not.
        assertTrue("unmappable steps must be visible: $yaml", yaml.contains("# unsupported in Maestro: android_file_pull"))
    }

    @Test
    fun `the compose emitter produces a plausible test class and refuses to invent an assertion`() {
        val kotlin = FlowEmitters.toComposeTest(recordSample())

        assertTrue(kotlin.contains("class LoginTest {"))
        assertTrue(kotlin.contains("@Test"))
        assertTrue(kotlin.contains("onNodeWithText(\"Sign in\").performClick()"))
        // A recording captures steps, not intent. Fabricating an assertion would produce a test
        // that passes for the wrong reason, which is worse than no test.
        assertTrue("must ask the human for the assertion: $kotlin", kotlin.contains("TODO"))
    }

    @Test
    fun `emitted text escapes quotes rather than producing broken output`() {
        val recorder = FlowRecorder(TestClock())
        recorder.start("quotes")
        recorder.append("android_input_type", mapOf("text" to "say \"hi\""))
        val flow = recorder.stop()

        assertTrue(FlowEmitters.toMaestroYaml(flow).contains("\\\"hi\\\""))
        assertTrue(FlowEmitters.toComposeTest(flow).contains("\\\"hi\\\""))
    }
}
