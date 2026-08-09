package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A clock the test advances explicitly, so rate-limit behavior needs no sleeping. */
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

class InvocationBudgetTest {
    @Test
    fun `destructive calls are allowed up to the limit`() {
        val budget = InvocationBudget(BudgetLimits(maxDestructivePerMinute = 3), TestClock())

        repeat(3) {
            assertEquals(BudgetDecision.Allowed, budget.recordDestructive("android_app_clear_data"))
        }
    }

    @Test
    fun `the call past the limit is refused and names the knob to raise`() {
        val budget = InvocationBudget(BudgetLimits(maxDestructivePerMinute = 2), TestClock())
        repeat(2) { budget.recordDestructive("android_app_clear_data") }

        val decision = budget.recordDestructive("android_app_clear_data")

        assertTrue(decision is BudgetDecision.Exceeded)
        assertEquals("destructive-budget-exceeded", (decision as BudgetDecision.Exceeded).code)
        assertTrue("should say how to raise it: ${decision.reason}", decision.reason.contains("maxDestructivePerMinute"))
    }

    @Test
    fun `a refused call is not counted, so the window drains normally`() {
        // If a refusal still consumed budget, a client retrying in a loop could keep itself
        // permanently locked out long after the original burst.
        val clock = TestClock()
        val budget = InvocationBudget(BudgetLimits(maxDestructivePerMinute = 1), clock)
        budget.recordDestructive("op")
        repeat(5) { budget.recordDestructive("op") }

        clock.advance(Duration.ofSeconds(61))

        assertEquals(BudgetDecision.Allowed, budget.recordDestructive("op"))
    }

    @Test
    fun `the window slides rather than resetting on a fixed boundary`() {
        val clock = TestClock()
        val budget = InvocationBudget(BudgetLimits(maxDestructivePerMinute = 2), clock)
        budget.recordDestructive("op")
        clock.advance(Duration.ofSeconds(30))
        budget.recordDestructive("op")

        assertTrue("still inside the window", budget.recordDestructive("op") is BudgetDecision.Exceeded)

        // 31s later the first call has aged out but the second has not: room for exactly one.
        clock.advance(Duration.ofSeconds(31))
        assertEquals(BudgetDecision.Allowed, budget.recordDestructive("op"))
        assertTrue(budget.recordDestructive("op") is BudgetDecision.Exceeded)
    }

    @Test
    fun `artifact bytes accumulate across a session and are capped`() {
        val budget = InvocationBudget(BudgetLimits(maxArtifactBytesPerSession = 1_000), TestClock())

        assertEquals(BudgetDecision.Allowed, budget.recordArtifactBytes(600))
        assertEquals(BudgetDecision.Allowed, budget.recordArtifactBytes(400))
        assertEquals(1_000, budget.artifactBytes())

        val decision = budget.recordArtifactBytes(1)
        assertTrue(decision is BudgetDecision.Exceeded)
        assertEquals("artifact-budget-exceeded", (decision as BudgetDecision.Exceeded).code)
        assertEquals("a refused write must not be counted", 1_000, budget.artifactBytes())
    }

    @Test
    fun `defaults are permissive enough for ordinary use`() {
        // The budget is a blast-radius bound, not a throttle for normal work: a handful of
        // destructive calls and a few hundred MB of artifacts must pass untouched.
        val budget = InvocationBudget(clock = TestClock())

        repeat(6) { assertEquals(BudgetDecision.Allowed, budget.recordDestructive("op")) }
        assertEquals(BudgetDecision.Allowed, budget.recordArtifactBytes(512L * 1024 * 1024))
    }

    @Test
    fun `concurrent recording never exceeds the limit`() {
        val budget = InvocationBudget(BudgetLimits(maxDestructivePerMinute = 10), TestClock())
        val allowed =
            java.util.concurrent.atomic
                .AtomicInteger()
        val threads =
            (1..8).map {
                Thread {
                    repeat(10) {
                        if (budget.recordDestructive("op") == BudgetDecision.Allowed) allowed.incrementAndGet()
                    }
                }
            }

        threads.forEach { it.start() }
        threads.forEach {
            it.join(
                java.util.concurrent.TimeUnit.SECONDS
                    .toMillis(10),
            )
        }

        assertEquals("exactly the limit should get through", 10, allowed.get())
    }

    @Test
    fun `the policy denies destructive calls once the budget is spent`() {
        val safety =
            SafetyConfig(
                allowCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                budgets = BudgetLimits(maxDestructivePerMinute = 2),
            )
        val policy =
            DefaultOperationPolicy(
                safety,
                listOf(
                    java.nio.file.Path
                        .of(System.getProperty("user.dir")),
                ),
                budget = InvocationBudget(safety.budgets, TestClock()),
            )
        val request =
            OperationRequest(
                operationId = "android_app_clear_data",
                requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                destructive = true,
                confirmDestructive = true,
            )

        assertTrue(policy.authorize(request) is AuthorizationDecision.Allowed)
        assertTrue(policy.authorize(request) is AuthorizationDecision.Allowed)

        val denied = policy.authorize(request)
        assertEquals("destructive-budget-exceeded", (denied as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `read-only work never consumes destructive budget`() {
        val safety = SafetyConfig(budgets = BudgetLimits(maxDestructivePerMinute = 1))
        val policy =
            DefaultOperationPolicy(
                safety,
                listOf(
                    java.nio.file.Path
                        .of(System.getProperty("user.dir")),
                ),
                budget = InvocationBudget(safety.budgets, TestClock()),
            )
        val readOnly =
            OperationRequest(
                operationId = "android_devices_list",
                requiredCapabilities = emptySet(),
                destructive = false,
            )

        repeat(50) { assertTrue(policy.authorize(readOnly) is AuthorizationDecision.Allowed) }
    }

    @Test
    fun `a project config can lower the budget but never raise it`() {
        val projectDir =
            java.nio.file.Files
                .createTempDirectory("dak-budget-project")
        java.nio.file.Files
            .createDirectories(projectDir.resolve(".droidagentkit"))
        java.nio.file.Files.writeString(
            projectDir.resolve(".droidagentkit/config.yaml"),
            """
            schemaVersion: 1
            safety:
              maxDestructivePerMinute: 999
            """.trimIndent(),
        )
        val policyPath =
            java.nio.file.Files
                .createTempDirectory("dak-budget-policy")
                .resolve("policy.yaml")
        java.nio.file.Files.writeString(
            policyPath,
            """
            schemaVersion: 1
            safety:
              maxDestructivePerMinute: 4
            """.trimIndent(),
        )

        val loaded = DroidAgentConfigLoader.loadEffective(projectDir, policyPath) as ConfigLoadResult.Loaded

        assertEquals(4, loaded.config.safety.budgets.maxDestructivePerMinute)
    }
}
