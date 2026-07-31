package com.droidagentkit.core

import java.nio.file.Files
import java.nio.file.Path

enum class Capability {
    DEVICE_INPUT,
    APP_INSTALL,
    APP_CONTROL,
    APP_DESTRUCTIVE,
    PERMISSION_MUTATION,
    EMULATOR_CONTROL,
    EMULATOR_RESTORE,
    FILE_EXPORT,
    FILE_IMPORT,
    APP_DATA_READ,
    SENSITIVE_DIAGNOSTICS,
    NETWORK_INTERCEPTION,
    GOLDEN_UPDATE,
}

enum class ToolGroup {
    CORE,
    DEVICE_READ,
    DEVICE_CONTROL,
    PERFETTO,
    VISUALS,
    STORAGE,
    NETWORK_EXPERIMENTAL,
}

data class OperationRequest(
    val operationId: String,
    val requiredCapabilities: Set<Capability>,
    val destructive: Boolean,
    val deviceSerial: String? = null,
    val packageName: String? = null,
    val hostPaths: List<Path> = emptyList(),
    val devicePaths: List<String> = emptyList(),
    /**
     * Guards against a model invoking a destructive tool *by accident*. This flag arrives in the
     * MCP tool arguments, so it is supplied by the agent rather than by a human — a compromised
     * or prompt-injected agent can simply pass `true`. The control that actually bounds a hostile
     * agent is the capability set in the user policy, which the agent cannot influence.
     */
    val confirmDestructive: Boolean = false,
    val mutating: Boolean = false,
)

data class AuthorizedOperation internal constructor(
    val request: OperationRequest,
)

sealed interface AuthorizationDecision {
    data class Allowed(
        val operation: AuthorizedOperation,
    ) : AuthorizationDecision

    data class Denied(
        val code: String,
        val reason: String,
    ) : AuthorizationDecision
}

interface OperationPolicy {
    fun authorize(request: OperationRequest): AuthorizationDecision
}

class DefaultOperationPolicy(
    private val config: SafetyConfig,
    private val allowedRoots: List<Path>,
) : OperationPolicy {
    override fun authorize(request: OperationRequest): AuthorizationDecision {
        request.deviceSerial?.let { serial ->
            if (!DeviceIdentifiers.isValidDeviceSerial(serial)) {
                return AuthorizationDecision.Denied(
                    "invalid-device-serial",
                    "Device serial '$serial' is not a valid adb serial.",
                )
            }
        }
        request.packageName?.let { pkg ->
            if (!DeviceIdentifiers.isValidPackageName(pkg)) {
                return AuthorizationDecision.Denied(
                    "invalid-package-name",
                    "Package name '$pkg' is not a valid Android package name.",
                )
            }
        }
        val enabled = config.allowedCapabilities()
        val missing = request.requiredCapabilities - enabled
        if (missing.isNotEmpty()) {
            return AuthorizationDecision.Denied(
                "capability-not-enabled",
                "Required capabilities not enabled: ${missing.joinToString(", ") { it.name.lowercase() }}",
            )
        }
        if (request.destructive && !request.confirmDestructive) {
            return AuthorizationDecision.Denied(
                "destructive-confirmation-required",
                "Operation '${request.operationId}' is destructive and requires confirmDestructive=true.",
            )
        }
        val realRoots = allowedRoots.map { resolveThroughLinks(it) }
        request.hostPaths.forEach { path ->
            val resolved = resolveThroughLinks(path)
            if (realRoots.none { resolved.startsWith(it) }) {
                return AuthorizationDecision.Denied(
                    "host-path-denied",
                    "Host path '${path.toAbsolutePath().normalize()}' is outside the allowed roots.",
                )
            }
        }
        request.devicePaths.forEach { devicePath ->
            if (!devicePath.startsWith("/")) {
                return AuthorizationDecision.Denied(
                    "device-path-denied",
                    "Device path '$devicePath' must be an absolute path.",
                )
            }
            val normalized = normalizeDevicePath(devicePath)
            if (ALLOWED_DEVICE_PATH_PREFIXES.none { normalized == it.removeSuffix("/") || normalized.startsWith(it) }) {
                return AuthorizationDecision.Denied(
                    "device-path-denied",
                    "Device path '$devicePath' is outside the allowed public-storage scope.",
                )
            }
        }
        return AuthorizationDecision.Allowed(AuthorizedOperation(request))
    }

    private companion object {
        // Generic file push/pull is for public/external storage only, so this is an allowlist
        // rather than a list of things to block. A denylist left everything unnamed reachable —
        // including /data/local/tmp, the usual staging directory for Android privilege pivots,
        // plus /etc, /vendor, /cache and /mnt.
        //
        // App-private storage stays out deliberately: the `storage` tool group provides scoped,
        // run-as-based read access to a debuggable app's own data, which is the supported path.
        val ALLOWED_DEVICE_PATH_PREFIXES =
            listOf("/sdcard/", "/storage/emulated/0/", "/storage/self/primary/")

        /**
         * Absolute path with symlinks resolved, tolerating a target that does not exist yet.
         *
         * `normalize()` alone is lexical, so a link inside an allowed root pointed anywhere and the
         * containment check still passed. A pull destination legitimately may not exist, so this
         * resolves the deepest ancestor that does and re-appends the rest — enough to catch a link
         * anywhere along the existing part of the path.
         */
        fun resolveThroughLinks(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            var existing = absolute
            while (existing.parent != null && !Files.exists(existing)) {
                existing = existing.parent
            }
            val real = runCatching { existing.toRealPath() }.getOrDefault(existing)
            val remainder = runCatching { existing.relativize(absolute) }.getOrNull()
            return if (remainder == null || remainder.toString().isEmpty()) real else real.resolve(remainder).normalize()
        }

        fun normalizeDevicePath(path: String): String {
            val segments = path.split("/")
            val normalized = mutableListOf<String>()
            for (segment in segments) {
                when (segment) {
                    "", "." -> {}
                    ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.size - 1)
                    else -> normalized.add(segment)
                }
            }
            return "/" + normalized.joinToString("/")
        }
    }
}
