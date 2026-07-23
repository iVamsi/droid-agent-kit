package com.droidagentkit.device

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity

enum class DumpsysPreset(
    val wireName: String,
    val service: String,
) {
    MEMINFO("meminfo", "meminfo"),
    GFXINFO("gfxinfo", "gfxinfo"),
    CPUINFO("cpuinfo", "cpuinfo"),
    BATTERYSTATS("batterystats", "batterystats"),
    PACKAGE("package", "package"),
}

data class DumpsysSummary(
    val preset: DumpsysPreset,
    val summary: Map<String, Any>,
    val provenance: Map<String, String>,
    val findings: List<DiagnosticFinding>,
)

data class BatterySummary(
    val summary: Map<String, Any>,
    val provenance: Map<String, String>,
    val findings: List<DiagnosticFinding>,
)

object DumpsysSummaryParser {
    fun parseBattery(
        deviceSerial: String,
        output: String,
    ): BatterySummary {
        val provenance = mapOf("deviceSerial" to deviceSerial, "service" to "battery", "source" to "adb shell dumpsys battery")
        if (output.isBlank()) {
            return BatterySummary(
                emptyMap(),
                provenance,
                listOf(
                    DiagnosticFinding(
                        "battery",
                        Severity.WARNING,
                        "empty-dumpsys",
                        "dumpsys battery returned no output; the device may be offline or unauthorized.",
                        deviceSerial,
                    ),
                ),
            )
        }
        val summary = mutableMapOf<String, Any>()
        extractInt(output, "level:")?.let { summary["level"] = it }
        extractInt(output, "scale:")?.let { summary["scale"] = it }
        extractInt(output, "temperature:")?.let { summary["temperatureTenthsCelsius"] = it }
        extractInt(output, "voltage:")?.let { summary["voltageMillivolts"] = it }
        extractText(output, "status:")?.let { summary["status"] = it }
        extractText(output, "health:")?.let { summary["health"] = it }
        extractText(output, "technology:")?.let { summary["technology"] = it }
        if (summary.isEmpty()) summary["note"] = "Battery fields not found; raw output retained as evidence."
        return BatterySummary(summary, provenance, emptyList())
    }

    fun parse(
        preset: DumpsysPreset,
        deviceSerial: String,
        output: String,
        packageName: String? = null,
    ): DumpsysSummary {
        val provenance =
            mapOf(
                "deviceSerial" to deviceSerial,
                "service" to preset.service,
                "source" to "adb shell dumpsys ${preset.service}${packageName?.let { " $it" } ?: ""}",
            )
        if (output.isBlank()) {
            return DumpsysSummary(
                preset,
                emptyMap(),
                provenance,
                listOf(
                    DiagnosticFinding(
                        "dumpsys",
                        Severity.WARNING,
                        "empty-dumpsys",
                        "dumpsys ${preset.service} returned no output; the device may be offline or unauthorized.",
                        deviceSerial,
                    ),
                ),
            )
        }
        val summary =
            when (preset) {
                DumpsysPreset.MEMINFO -> parseMeminfo(output)
                DumpsysPreset.GFXINFO -> parseGfxinfo(output)
                DumpsysPreset.BATTERYSTATS -> parseBatterystats(output)
                DumpsysPreset.CPUINFO -> parseCpuinfo(output)
                DumpsysPreset.PACKAGE ->
                    mapOf(
                        "note" to
                            "Raw dumpsys package output retained as evidence; use android_permission_audit for structured permission state.",
                    )
            }
        return DumpsysSummary(preset, summary, provenance, emptyList())
    }

    private fun parseMeminfo(output: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val totalRam = extractKb(output, "Total RAM")
        val freeRam = extractKb(output, "Free RAM")
        val usedRam = extractKb(output, "Used RAM")
        totalRam?.let { result["totalRamKb"] = it }
        freeRam?.let { result["freeRamKb"] = it }
        usedRam?.let { result["usedRamKb"] = it }
        if (result.isEmpty()) result["note"] = "Total/Free/Used RAM lines not found; raw output retained as evidence."
        return result
    }

    private fun parseGfxinfo(output: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        extractInt(output, "Total frames rendered:")?.let { result["totalFrames"] = it }
        extractInt(output, "Janky frames:")?.let { result["jankyFrames"] = it }
        extractInt(output, "Number of slow renders")?.let { result["slowRenders"] = it }
        if (result.isEmpty()) result["note"] = "GFXINFO frame lines not found; raw output retained as evidence."
        return result
    }

    private fun parseBatterystats(output: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        extractInt(output, "Battery History")?.let { result["historySize"] = it }
        result["note"] = "dumpsys batterystats is verbose; raw output retained as evidence for offline analysis."
        return result
    }

    private fun parseCpuinfo(output: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val loadLines =
            output
                .lineSequence()
                .filter {
                    it.contains("cpu load", ignoreCase = true) || it.matches(Regex(".*\\d+\\.\\d+%.*"))
                }.take(8)
                .toList()
        if (loadLines.isNotEmpty()) result["loadLines"] = loadLines
        if (result.isEmpty()) result["note"] = "CPU load lines not found; raw output retained as evidence."
        return result
    }

    private fun extractKb(
        output: String,
        label: String,
    ): Long? {
        val match = Regex("$label:\\s*([\\d,]+)\\s*KB").find(output)
        return match
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toLongOrNull()
    }

    private fun extractInt(
        output: String,
        label: String,
    ): Int? {
        val match = Regex("$label:?\\s*(\\d+)").find(output)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractText(
        output: String,
        label: String,
    ): String? {
        val match = Regex("$label:?\\s*(\\S.+)").find(output)
        return match
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
