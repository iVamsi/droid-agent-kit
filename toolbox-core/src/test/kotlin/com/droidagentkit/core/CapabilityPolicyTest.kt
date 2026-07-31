package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `rejects identifiers that would land in adb flag position`() {
        val config = SafetyConfig(allowCapabilities = setOf(Capability.APP_DESTRUCTIVE))

        val badSerial =
            policy(config).authorize(
                OperationRequest(
                    operationId = "app-uninstall",
                    requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                    destructive = true,
                    confirmDestructive = true,
                    deviceSerial = "--help",
                ),
            )
        assertEquals("invalid-device-serial", (badSerial as AuthorizationDecision.Denied).code)

        val badPackage =
            policy(config).authorize(
                OperationRequest(
                    operationId = "app-uninstall",
                    requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                    destructive = true,
                    confirmDestructive = true,
                    deviceSerial = "emulator-5554",
                    packageName = "-x",
                ),
            )
        assertEquals("invalid-package-name", (badPackage as AuthorizationDecision.Denied).code)
    }

    @Test
    fun `accepts real serials and package names`() {
        assertTrue(DeviceIdentifiers.isValidDeviceSerial("emulator-5554"))
        assertTrue(DeviceIdentifiers.isValidDeviceSerial("192.168.1.5:5555"))
        assertTrue(DeviceIdentifiers.isValidDeviceSerial("R58M12ABCDE"))
        assertTrue(DeviceIdentifiers.isValidPackageName("com.example.app"))
        assertTrue(DeviceIdentifiers.isValidPackageName("com.example.app.free_tier"))

        assertFalse(DeviceIdentifiers.isValidDeviceSerial("-s"))
        assertFalse(DeviceIdentifiers.isValidDeviceSerial("a b"))
        assertFalse(DeviceIdentifiers.isValidPackageName("noDots"))
        assertFalse(DeviceIdentifiers.isValidPackageName("com.example app"))
        assertFalse(DeviceIdentifiers.isValidPackageName("--help"))
    }

    @Test
    fun `denies device paths that no allowlist entry covers`() {
        // Previously reachable because the policy blocked a fixed list instead of permitting one.
        // /data/local/tmp is the usual staging directory for Android privilege pivots.
        listOf(
            "/data/local/tmp/payload.so",
            "/etc/hosts",
            "/vendor/bin/sh",
            "/cache/x",
            "/mnt/expand/y",
        ).forEach { path ->
            val request =
                OperationRequest(
                    operationId = "file-push",
                    requiredCapabilities = setOf(Capability.FILE_IMPORT),
                    destructive = false,
                    devicePaths = listOf(path),
                )
            val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_IMPORT))
            val decision = policy(config).authorize(request)
            assertTrue("expected $path to be denied", decision is AuthorizationDecision.Denied)
            assertEquals("device-path-denied", (decision as AuthorizationDecision.Denied).code)
        }
    }

    @Test
    fun `allows public storage device paths`() {
        listOf(
            "/sdcard/Download/report.pdf",
            "/storage/emulated/0/Documents/x.txt",
        ).forEach { path ->
            val request =
                OperationRequest(
                    operationId = "file-pull",
                    requiredCapabilities = setOf(Capability.FILE_EXPORT),
                    destructive = false,
                    devicePaths = listOf(path),
                )
            val config = SafetyConfig(allowCapabilities = setOf(Capability.FILE_EXPORT))
            assertTrue("expected $path to be allowed", policy(config).authorize(request) is AuthorizationDecision.Allowed)
        }
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
