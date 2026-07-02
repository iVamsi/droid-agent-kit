package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object DependencyVersionChecker {
    private val directDependencyRegex = Regex(
        """(?:implementation|api|testImplementation|androidTestImplementation|compileOnly|runtimeOnly)\(\s*"([\w.-]+):([\w.-]+):([\w.-]+)"\s*\)""",
    )
    private val versionEntryRegex = Regex("""^(\w[\w.-]*)\s*=\s*"([^"]+)"""")
    private val libraryAliasRegex = Regex("""^(\w[\w.-]*)\s*=""")
    private val libraryVersionRefRegex = Regex("""version\.ref\s*=\s*"([\w.-]+)"""")
    private val libraryAliasUsageRegex = Regex("""libs\.([a-zA-Z][\w]*(?:\.[a-zA-Z][\w]*)*)""")

    fun check(root: Path): List<DiagnosticFinding> {
        val buildFiles = findBuildFiles(root)
        val findings = mutableListOf<DiagnosticFinding>()
        findings += checkVersionDrift(buildFiles)

        val catalogPath = root.resolve("gradle/libs.versions.toml")
        if (catalogPath.exists()) {
            findings += checkOrphanedCatalogEntries(catalogPath, buildFiles)
        }
        return findings
    }

    private fun findBuildFiles(root: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walk(root, 6).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() == "build.gradle.kts" || it.fileName.toString() == "build.gradle" }
                .forEach { files.add(it) }
        }
        return files
    }

    private fun checkVersionDrift(buildFiles: List<Path>): List<DiagnosticFinding> {
        val coordinateVersions = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        for (file in buildFiles) {
            val text = Files.readString(file)
            directDependencyRegex.findAll(text).forEach { match ->
                val (group, artifact, version) = match.destructured
                val coordinate = "$group:$artifact"
                coordinateVersions.getOrPut(coordinate) { mutableMapOf() }
                    .getOrPut(version) { mutableListOf() }
                    .add(file.toString())
            }
        }
        return coordinateVersions.filter { it.value.size > 1 }.map { (coordinate, versions) ->
            val summary = versions.entries.joinToString("; ") { (version, files) -> "$version in ${files.joinToString(", ")}" }
            DiagnosticFinding(
                category = "dependency_drift",
                severity = Severity.WARNING,
                title = "Version drift for $coordinate",
                detail = summary,
                location = coordinate,
            )
        }
    }

    private fun checkOrphanedCatalogEntries(catalogPath: Path, buildFiles: List<Path>): List<DiagnosticFinding> {
        val lines = Files.readAllLines(catalogPath)
        val versionKeys = mutableSetOf<String>()
        val libraryAliases = mutableSetOf<String>()
        val referencedVersionKeys = mutableSetOf<String>()
        var section = ""
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line == "[versions]" -> section = "versions"
                line == "[libraries]" -> section = "libraries"
                line.startsWith("[") -> section = ""
                section == "versions" -> versionEntryRegex.find(line)?.let { versionKeys.add(it.groupValues[1]) }
                section == "libraries" -> {
                    libraryAliasRegex.find(line)?.let { libraryAliases.add(it.groupValues[1]) }
                    libraryVersionRefRegex.find(line)?.let { referencedVersionKeys.add(it.groupValues[1]) }
                }
            }
        }
        val usedAliases = mutableSetOf<String>()
        for (file in buildFiles) {
            val text = Files.readString(file)
            libraryAliasUsageRegex.findAll(text).forEach { usedAliases.add(it.groupValues[1].replace('.', '-')) }
        }

        val findings = mutableListOf<DiagnosticFinding>()
        for (versionKey in versionKeys - referencedVersionKeys) {
            findings += DiagnosticFinding(
                category = "dependency_drift",
                severity = Severity.INFO,
                title = "Unused version catalog entry: $versionKey",
                detail = "No [libraries] entry in libs.versions.toml references version.ref = \"$versionKey\".",
                location = "gradle/libs.versions.toml",
            )
        }
        for (alias in libraryAliases - usedAliases) {
            findings += DiagnosticFinding(
                category = "dependency_drift",
                severity = Severity.INFO,
                title = "Unused catalog library: $alias",
                detail = "No build file references libs.${alias.replace('-', '.')}.",
                location = "gradle/libs.versions.toml",
            )
        }
        return findings
    }
}
