package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CapabilityPolicyTest {
    private val root = Files.createTempDirectory("dak-policy").toAbsolutePath().normalize()

    private fun policy(config: SafetyConfig = SafetyConfig()) = DefaultOperationPolicy(config, listOf(root))

    @Test
    fun `denies when required capability is not enabled`() {
        val request =
            OperationRequest(
                operationId = "input-tap",
                requiredCapabilities = setOf(Capability.DEVICE_INPUT),
                destructive = false,
            )
        val decision = policy().authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("capability-not-enabled", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `allows when capability is enabled`() {
        val request =
            OperationRequest(
                operationId = "input-tap",
                requiredCapabilities = setOf(Capability.DEVICE_INPUT),
                destructive = false,
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.DEVICE_INPUT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Allowed)
    }

    @Test
    fun `denies destructive operation without confirmation`() {
        val request =
            OperationRequest(
                operationId = "app-clear",
                requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                destructive = true,
                confirmDestructive = false,
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.APP_DESTRUCTIVE))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("destructive-confirmation-required", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `allows destructive operation with confirmation and capability`() {
        val request =
            OperationRequest(
                operationId = "app-clear",
                requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                destructive = true,
                confirmDestructive = true,
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.APP_DESTRUCTIVE))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Allowed)
    }

    @Test
    fun `denies host path outside allowed roots`() {
        val outside = Files.createTempDirectory("dak-outside").toAbsolutePath().normalize()
        val request =
            OperationRequest(
                operationId = "file-push",
                requiredCapabilities = setOf(Capability.FILE_IMPORT),
                destructive = false,
                hostPaths = listOf(outside.resolve("evil.apk")),
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_IMPORT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("host-path-denied", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `denies forbidden device paths`() {
        val request =
            OperationRequest(
                operationId = "file-pull",
                requiredCapabilities = setOf(Capability.FILE_EXPORT),
                destructive = false,
                devicePaths = listOf("/system/build.prop"),
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_EXPORT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("device-path-denied", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `denies device paths under app-private storage`() {
        val request =
            OperationRequest(
                operationId = "file-pull",
                requiredCapabilities = setOf(Capability.FILE_EXPORT),
                destructive = false,
                devicePaths = listOf("/data/data/com.other.app/databases/accounts.db"),
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_EXPORT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("device-path-denied", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `denies device path traversal that resolves into a forbidden prefix`() {
        val request =
            OperationRequest(
                operationId = "file-pull",
                requiredCapabilities = setOf(Capability.FILE_EXPORT),
                destructive = false,
                devicePaths = listOf("/sdcard/../data/data/com.other.app/shared_prefs/x.xml"),
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_EXPORT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("device-path-denied", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `denies non-absolute device paths`() {
        val request =
            OperationRequest(
                operationId = "file-pull",
                requiredCapabilities = setOf(Capability.FILE_EXPORT),
                destructive = false,
                devicePaths = listOf(".."),
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_EXPORT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Denied)
        assertEquals("device-path-denied", (decision as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `allows device paths under public storage`() {
        val request =
            OperationRequest(
                operationId = "file-pull",
                requiredCapabilities = setOf(Capability.FILE_EXPORT),
                destructive = false,
                devicePaths = listOf("/sdcard/Download/report.pdf"),
            )
        val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_EXPORT))
        val decision = policy(config).authorize(request)
        assertTrue(decision is AuthorizationDecision.Allowed)
    }

    @Test
    fun `deprecated aliases map to capabilities when allowCapabilities is empty`() {
        val config = SafetyConfig(allowAdbInput = true, allowAppInstall = true, allowEmulatorStart = true)
        val enabled = config.allowedCapabilities()
        assertTrue(enabled.contains(Capability.DEVICE_INPUT))
        assertTrue(enabled.contains(Capability.APP_INSTALL))
        assertTrue(enabled.contains(Capability.EMULATOR_CONTROL))
    }

    @Test
    fun `allowCapabilities wins over deprecated aliases`() {
        val config =
            SafetyConfig(
                allowAdbInput = true,
                allowCapabilities = setOf(Capability.APP_DATA_READ),
            )
        val enabled = config.allowedCapabilities()
        assertEquals(setOf(Capability.APP_DATA_READ), enabled)
    }
}
