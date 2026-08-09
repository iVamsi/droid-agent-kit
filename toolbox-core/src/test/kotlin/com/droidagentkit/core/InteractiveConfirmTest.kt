package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class InteractiveConfirmTest {
    private val roots = listOf(Path.of(System.getProperty("user.dir")))

    private fun destructiveRequest() =
        OperationRequest(
            operationId = "android_app_clear_data",
            requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
            destructive = true,
            confirmDestructive = true,
        )

    private fun safety(requireInteractive: Boolean) =
        SafetyConfig(
            allowCapabilities = setOf(Capability.APP_DESTRUCTIVE),
            requireInteractiveConfirm = requireInteractive,
        )

    @Test
    fun `with the setting off nothing is asked`() {
        var asked = false
        val policy =
            DefaultOperationPolicy(safety(requireInteractive = false), roots) {
                asked = true
                InteractiveConfirmation.APPROVED
            }

        val decision = policy.authorize(destructiveRequest())

        assertTrue(decision is AuthorizationDecision.Allowed)
        assertTrue("must not prompt when the setting is off", !asked)
    }

    @Test
    fun `an approval lets the operation through`() {
        val policy = DefaultOperationPolicy(safety(requireInteractive = true), roots) { InteractiveConfirmation.APPROVED }

        assertTrue(policy.authorize(destructiveRequest()) is AuthorizationDecision.Allowed)
    }

    @Test
    fun `a decline blocks the operation`() {
        val policy = DefaultOperationPolicy(safety(requireInteractive = true), roots) { InteractiveConfirmation.DECLINED }

        val decision = policy.authorize(destructiveRequest())

        assertEquals("user-declined", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `a host that cannot prompt fails closed`() {
        // Falling back to "allow" would make the setting worse than useless: the user would
        // believe destructive calls need a click while they silently do not.
        val policy = DefaultOperationPolicy(safety(requireInteractive = true), roots)

        val decision = policy.authorize(destructiveRequest())

        assertEquals("interactive-confirm-unavailable", (decision as AuthorizationDecision.Denied).code)
        assertTrue("should say how to fix it: ${decision.reason}", decision.reason.contains("elicitation"))
    }

    @Test
    fun `a non-destructive operation is never gated`() {
        var asked = false
        val policy =
            DefaultOperationPolicy(safety(requireInteractive = true), roots) {
                asked = true
                InteractiveConfirmation.DECLINED
            }

        val decision =
            policy.authorize(
                OperationRequest(
                    operationId = "android_devices_list",
                    requiredCapabilities = emptySet(),
                    destructive = false,
                ),
            )

        assertTrue(decision is AuthorizationDecision.Allowed)
        assertTrue("read-only work must not prompt", !asked)
    }

    @Test
    fun `a missing capability is refused without bothering a human`() {
        // Prompting for something the policy already forbids trains people to click through.
        var asked = false
        val policy =
            DefaultOperationPolicy(
                SafetyConfig(allowCapabilities = emptySet(), requireInteractiveConfirm = true),
                roots,
            ) {
                asked = true
                InteractiveConfirmation.APPROVED
            }

        val decision = policy.authorize(destructiveRequest())

        assertEquals("capability-not-enabled", (decision as AuthorizationDecision.Denied).code)
        assertTrue("must not prompt for an already-forbidden operation", !asked)
    }

    @Test
    fun `a project config cannot enable or disable interactive confirmation`() {
        // The whole point is that the repository being worked on does not get a say.
        val projectDir = Files.createTempDirectory("dak-confirm-project")
        Files.createDirectories(projectDir.resolve(".droidagentkit"))
        Files.writeString(
            projectDir.resolve(".droidagentkit/config.yaml"),
            """
            schemaVersion: 1
            safety:
              requireInteractiveConfirm: false
            """.trimIndent(),
        )
        val policyPath = Files.createTempDirectory("dak-confirm-policy").resolve("policy.yaml")
        Files.writeString(
            policyPath,
            """
            schemaVersion: 1
            safety:
              requireInteractiveConfirm: true
            """.trimIndent(),
        )

        val loaded = DroidAgentConfigLoader.loadEffective(projectDir, policyPath) as ConfigLoadResult.Loaded

        assertTrue("the policy's setting must win", loaded.config.safety.requireInteractiveConfirm)
        assertTrue(
            "the ignored key should be reported: ${loaded.warnings}",
            loaded.warnings.any { it.contains("requireInteractiveConfirm") },
        )
    }
}
