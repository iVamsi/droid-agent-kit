package com.droidagentkit.inspector

import com.droidagentkit.core.CommandSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class AndroidProjectInspector {
    fun inspect(root: Path): AndroidProjectReport {
        val warnings = mutableListOf<String>()
        val settings =
            listOf(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"))
                .firstOrNull { it.exists() }
        if (settings == null) {
            warnings.add("No settings.gradle(.kts) found; Android support is partial.")
            return AndroidProjectReport(
                projectName = root.fileName?.toString() ?: "unknown",
                support = ProjectSupport.PARTIAL,
                rootPath = root.toString(),
                modules = emptyList(),
                versions = parseVersions(root),
                commandMatrix = emptyList(),
                warnings = warnings,
            )
        }

        val settingsText = Files.readString(settings)
        val projectName = parseProjectName(settingsText) ?: root.fileName?.toString() ?: "unknown"
        val modulePaths = parseIncludes(settingsText)
        if (modulePaths.isEmpty()) warnings.add("No Gradle modules were found in settings.")

        val modules =
            modulePaths.mapNotNull { modulePath ->
                inspectModule(root, modulePath, warnings)
            }
        val support = if (modules.isEmpty()) ProjectSupport.PARTIAL else ProjectSupport.SUPPORTED
        return AndroidProjectReport(
            projectName = projectName,
            support = support,
            rootPath = root.toString(),
            modules = modules,
            versions = parseVersions(root),
            commandMatrix = modules.flatMap { commandSpecsFor(root, it) },
            warnings = warnings,
        )
    }

    private fun parseProjectName(settingsText: String): String? {
        val regexes =
            listOf(
                Regex("rootProject\\.name\\s*=\\s*\"([^\"]+)\""),
                Regex("rootProject\\.name\\s*=\\s*'([^']+)'"),
            )
        return regexes.firstNotNullOfOrNull { it.find(settingsText)?.groupValues?.get(1) }
    }

    private fun parseIncludes(settingsText: String): List<String> {
        val modules = linkedSetOf<String>()
        Regex("include\\(([^)]*)\\)").findAll(settingsText).forEach { match ->
            Regex("[\"'](:[^\"']+)[\"']").findAll(match.groupValues[1]).forEach { modules.add(it.groupValues[1]) }
        }
        Regex("include\\s+([^\\n]+)").findAll(settingsText).forEach { match ->
            Regex("[\"'](:[^\"']+)[\"']").findAll(match.groupValues[1]).forEach { modules.add(it.groupValues[1]) }
        }
        return modules.toList()
    }

    private fun inspectModule(
        root: Path,
        modulePath: String,
        warnings: MutableList<String>,
    ): AndroidModuleSummary? {
        val dir = root.resolve(modulePath.removePrefix(":").replace(':', '/'))
        if (!dir.exists() || !dir.isDirectory()) {
            warnings.add("Module $modulePath directory was not found at $dir.")
            return null
        }
        val buildFile = listOf(dir.resolve("build.gradle.kts"), dir.resolve("build.gradle")).firstOrNull { it.exists() }
        val buildText = buildFile?.let(Files::readString).orEmpty()
        val type =
            when {
                buildText.contains("com.android.application") -> AndroidModuleType.APPLICATION
                buildText.contains("com.android.dynamic-feature") -> AndroidModuleType.DYNAMIC_FEATURE
                buildText.contains("com.android.library") -> AndroidModuleType.LIBRARY
                buildText.contains(
                    "kotlin(\"multiplatform\")",
                ) ||
                    buildText.contains("kotlin-multiplatform") -> AndroidModuleType.KMP_ANDROID
                else -> AndroidModuleType.UNKNOWN
            }
        val namespace =
            Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(buildText)?.groupValues?.get(1)
                ?: Regex("namespace\\s+'([^']+)'").find(buildText)?.groupValues?.get(1)
        val manifest = dir.resolve("src/main/AndroidManifest.xml")
        val manifestText = if (manifest.exists()) Files.readString(manifest) else ""
        val packageName =
            Regex("<manifest[^>]*package\\s*=\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                ?: namespace
        val launchers = parseLauncherActivities(manifestText, packageName)
        val usesCompose = buildText.contains("compose", ignoreCase = true) || dir.resolve("src/main/java").containsKotlinCompose()
        val hasUnitTests = dir.resolve("src/test").exists()
        val hasAndroidTests = dir.resolve("src/androidTest").exists()
        return AndroidModuleSummary(
            path = modulePath,
            directory = dir.toString(),
            type = type,
            namespace = namespace,
            packageName = packageName,
            launcherActivities = launchers,
            usesCompose = usesCompose,
            hasUnitTests = hasUnitTests,
            hasAndroidTests = hasAndroidTests,
            moduleDependencies = parseModuleDependencies(buildText),
            buildTypes = parseBlockNames(buildText, "buildTypes"),
            productFlavors = parseBlockNames(buildText, "productFlavors"),
        )
    }

    private fun parseModuleDependencies(buildText: String): List<String> =
        Regex("""(?:implementation|api|runtimeOnly|compileOnly)\s*\(\s*project\s*\(\s*["'](:[^"']+)["']\s*\)\s*\)""")
            .findAll(buildText)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

    private fun parseBlockNames(
        buildText: String,
        sectionName: String,
    ): List<String> {
        val sectionEnd =
            Regex("""(?m)^\s*$sectionName\s*\{""").find(buildText)?.range?.last
                ?: return emptyList()
        val body = StringBuilder()
        var depth = 0
        for (i in (sectionEnd + 1) until buildText.length) {
            when (buildText[i]) {
                '{' -> {
                    body.append('{')
                    depth++
                }
                '}' -> {
                    if (depth == 0) break
                    depth--
                    body.append('}')
                }
                else -> body.append(buildText[i])
            }
        }
        val excluded =
            setOf(
                "android",
                "kotlin",
                "dependencies",
                "buildTypes",
                "productFlavors",
                "defaultConfig",
                "signingConfigs",
                "composeOptions",
                "lint",
                "packaging",
            )
        return Regex("""^\s*(\w+)\s*\{""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .filter { it !in excluded }
            .distinct()
            .toList()
    }

    private fun parseLauncherActivities(
        manifestText: String,
        packageName: String?,
    ): List<String> {
        if (!manifestText.contains("android.intent.category.LAUNCHER")) return emptyList()
        return Regex("<activity[^>]*android:name\\s*=\\s*\"([^\"]+)\"[\\s\\S]*?</activity>")
            .findAll(manifestText)
            .filter { it.value.contains("android.intent.category.LAUNCHER") }
            .map { it.groupValues[1].qualifyActivity(packageName) }
            .toList()
    }

    private fun String.qualifyActivity(packageName: String?): String =
        when {
            startsWith(".") && packageName != null -> packageName + this
            contains(".") -> this
            packageName != null -> "$packageName.$this"
            else -> this
        }

    private fun parseVersions(root: Path): Map<String, String> {
        val catalog = root.resolve("gradle/libs.versions.toml")
        if (!catalog.exists()) return emptyMap()

        val lines = Files.readAllLines(catalog)
        val versionTable = linkedMapOf<String, String>() // [versions] → raw string values
        val result = linkedMapOf<String, String>()
        var currentTable = ""

        // First pass: collect [versions]
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentTable = line.removeSurrounding("[", "]").trim()
                continue
            }
            if (currentTable == "versions") {
                val m = Regex("""([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""").find(line) ?: continue
                versionTable[m.groupValues[1]] = m.groupValues[2]
                result[m.groupValues[1]] = m.groupValues[2]
            }
        }

        // Second pass: resolve [libraries] and [plugins]
        currentTable = ""
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentTable = line.removeSurrounding("[", "]").trim()
                continue
            }
            if (currentTable != "libraries" && currentTable != "plugins") continue
            val alias = line.substringBefore("=").trim().takeIf { it.isNotBlank() } ?: continue
            val versionRef = Regex("""version\.ref\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val versionDirect = Regex(""",?\s*version\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val resolved =
                when {
                    versionRef != null -> versionTable[versionRef]
                    versionDirect != null -> versionDirect
                    else -> null
                } ?: continue
            result.putIfAbsent(alias, resolved)
        }

        return result
    }

    private fun commandSpecsFor(
        root: Path,
        module: AndroidModuleSummary,
    ): List<CommandSpec> {
        val moduleName = module.path.removePrefix(":").replace(':', '-')
        val commands = mutableListOf<CommandSpec>()
        if (module.type == AndroidModuleType.APPLICATION || module.type == AndroidModuleType.LIBRARY) {
            commands +=
                CommandSpec(
                    id = "$moduleName-test-unit",
                    command = listOf("./gradlew", "${module.path}:testDebugUnitTest"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 600,
                )
            commands +=
                CommandSpec(
                    id = "$moduleName-lint-debug",
                    command = listOf("./gradlew", "${module.path}:lintDebug"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 600,
                )
            if (module.type == AndroidModuleType.APPLICATION) {
                commands +=
                    CommandSpec(
                        id = "$moduleName-assemble-debug",
                        command = listOf("./gradlew", "${module.path}:assembleDebug"),
                        workingDirectory = root.toString(),
                        mutatesProject = false,
                        requiresDevice = false,
                        timeoutSeconds = 600,
                    )
            }
            if (module.productFlavors.isNotEmpty()) {
                module.productFlavors.forEach { flavor ->
                    val cap = flavor.replaceFirstChar { it.uppercaseChar() }
                    commands +=
                        CommandSpec(
                            id = "$moduleName-test-$flavor-unit",
                            command = listOf("./gradlew", "${module.path}:test${cap}DebugUnitTest"),
                            workingDirectory = root.toString(),
                            mutatesProject = false,
                            requiresDevice = false,
                            timeoutSeconds = 600,
                        )
                }
            }
        }
        return commands
    }

    private fun Path.containsKotlinCompose(): Boolean {
        if (!exists()) return false
        return Files.walk(this).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .limit(200)
                .anyMatch { Files.readString(it).contains("@Composable") }
        }
    }
}
