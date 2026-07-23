package com.droidagentkit.device

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity

data class PermissionAuditEntry(
    val name: String,
    val granted: Boolean,
    val runtime: Boolean,
)

data class PermissionAuditResult(
    val packageName: String,
    val entries: List<PermissionAuditEntry>,
    val findings: List<DiagnosticFinding>,
)

object PermissionAuditParser {
    private val permissionLine = Regex("""^\s+(android\.permission\.[A-Za-z0-9_.]+):\s*granted=(true|false)""")
    private val runtimeHeader = Regex("""^\s*runtime permissions:\s*$""")
    private val installHeader = Regex("""^\s*install permissions:\s*$""")

    fun parse(
        packageName: String,
        dumpsysOutput: String,
    ): PermissionAuditResult {
        val entries = mutableListOf<PermissionAuditEntry>()
        val findings = mutableListOf<DiagnosticFinding>()
        if (dumpsysOutput.isBlank()) {
            findings +=
                DiagnosticFinding(
                    "permissions",
                    Severity.WARNING,
                    "empty-package-dump",
                    "dumpsys package output was empty; the package may not be installed or the device may be unreachable.",
                    packageName,
                )
            return PermissionAuditResult(packageName, entries, findings)
        }
        if (!dumpsysOutput.contains("Package [$packageName]")) {
            findings +=
                DiagnosticFinding(
                    "permissions",
                    Severity.WARNING,
                    "package-not-found",
                    "dumpsys package output did not mention package '$packageName'.",
                    packageName,
                )
            return PermissionAuditResult(packageName, entries, findings)
        }
        var section = Section.OTHER
        dumpsysOutput.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                runtimeHeader.matches(line) -> section = Section.RUNTIME
                installHeader.matches(line) -> section = Section.INSTALL
                else -> {
                    val match = permissionLine.find(line)
                    if (match != null) {
                        val name = match.groupValues[1]
                        val granted = match.groupValues[2] == "true"
                        val runtime = section == Section.RUNTIME
                        entries += PermissionAuditEntry(name, granted, runtime)
                        if (runtime && !granted) {
                            findings +=
                                DiagnosticFinding(
                                    "permissions",
                                    Severity.INFO,
                                    "runtime-permission-not-granted",
                                    "Runtime permission $name is not granted.",
                                    name,
                                )
                        }
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            findings +=
                DiagnosticFinding(
                    "permissions",
                    Severity.WARNING,
                    "no-permission-section",
                    "No install/runtime permission lines were parsed for '$packageName'.",
                    packageName,
                )
        }
        return PermissionAuditResult(packageName, entries, findings)
    }

    private enum class Section { INSTALL, RUNTIME, OTHER }
}
