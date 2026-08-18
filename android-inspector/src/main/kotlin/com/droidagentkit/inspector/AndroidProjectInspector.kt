package com.droidagentkit.inspector

import com.droidagentkit.core.CommandSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class AndroidProjectInspector {
    fun inspect(root: Path): AndroidProjectReport {
        val warnings = mutableListOf<String>()
        val versions = parseVersions(root)
        val pluginAliases = parsePluginAliases(root)
        val toolchain = ToolchainCompatibility.inspect(root, versions)
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
                versions = versions,
                commandMatrix = emptyList(),
                warnings = warnings,
                toolchain = toolchain,
            )
        }

        val settingsText = Files.readString(settings)
        val projectName = parseProjectName(settingsText) ?: root.fileName?.toString() ?: "unknown"
        val modulePaths = parseIncludes(settingsText)
        if (modulePaths.isEmpty()) warnings.add("No Gradle modules were found in settings.")
        val conventionPluginIds = resolveConventionPluginIds(root, settingsText, pluginAliases)

        val modules =
            modulePaths.mapNotNull { modulePath ->
                inspectModule(root, modulePath, warnings, pluginAliases, conventionPluginIds)
            }
        val support = if (modules.isEmpty()) ProjectSupport.PARTIAL else ProjectSupport.SUPPORTED
        return AndroidProjectReport(
            projectName = projectName,
            support = support,
            rootPath = root.toString(),
            modules = modules,
            versions = versions,
            commandMatrix = modules.flatMap { commandSpecsFor(root, it) },
            warnings = warnings,
            toolchain = toolchain,
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
            Regex("[\"']([^\"']+)[\"']").findAll(match.groupValues[1]).forEach {
                modules.add(it.groupValues[1].asModulePath())
            }
        }
        Regex("include\\s+([^\\n]+)").findAll(settingsText).forEach { match ->
            Regex("[\"']([^\"']+)[\"']").findAll(match.groupValues[1]).forEach {
                modules.add(it.groupValues[1].asModulePath())
            }
        }
        return modules.toList()
    }

    private fun String.asModulePath(): String = if (startsWith(':')) this else ":$this"

    private fun inspectModule(
        root: Path,
        modulePath: String,
        warnings: MutableList<String>,
        pluginAliases: Map<String, String>,
        conventionPluginIds: Map<String, Set<String>>,
    ): AndroidModuleSummary? {
        val dir = root.resolve(modulePath.removePrefix(":").replace(':', '/'))
        if (!dir.exists() || !dir.isDirectory()) {
            warnings.add("Module $modulePath directory was not found at $dir.")
            return null
        }
        val buildFile = listOf(dir.resolve("build.gradle.kts"), dir.resolve("build.gradle")).firstOrNull { it.exists() }
        val buildText = buildFile?.let(Files::readString).orEmpty()
        val pluginIds = parsePluginIds(buildText, pluginAliases)
        val effectivePluginIds = pluginIds + pluginIds.flatMap { conventionPluginIds[it].orEmpty() }
        val type =
            when {
                "com.android.kotlin.multiplatform.library" in effectivePluginIds -> AndroidModuleType.KMP_ANDROID
                "com.android.application" in effectivePluginIds -> AndroidModuleType.APPLICATION
                "com.android.dynamic-feature" in effectivePluginIds -> AndroidModuleType.DYNAMIC_FEATURE
                "com.android.library" in effectivePluginIds -> AndroidModuleType.LIBRARY
                "com.android.test" in effectivePluginIds -> AndroidModuleType.TEST_MODULE
                "org.jetbrains.kotlin.multiplatform" in effectivePluginIds -> AndroidModuleType.KMP_ANDROID
                "org.jetbrains.kotlin.jvm" in effectivePluginIds ||
                    "java" in effectivePluginIds ||
                    "java-library" in effectivePluginIds ||
                    "application" in effectivePluginIds -> AndroidModuleType.JVM_TOOLING
                else -> AndroidModuleType.UNKNOWN
            }
        val namespace =
            Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(buildText)?.groupValues?.get(1)
                ?: Regex("namespace\\s+'([^']+)'").find(buildText)?.groupValues?.get(1)
        val manifest =
            listOf(dir.resolve("src/main/AndroidManifest.xml"), dir.resolve("src/androidMain/AndroidManifest.xml"))
                .firstOrNull { it.exists() }
                ?: dir.resolve("src/main/AndroidManifest.xml")
        val manifestText = if (manifest.exists()) Files.readString(manifest) else ""
        val packageName =
            Regex("<manifest[^>]*package\\s*=\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                ?: namespace
        val launchers = parseLauncherActivities(manifestText, packageName)
        val sourceSets = detectSourceSets(dir)
        val usesCompose =
            buildText.contains("compose", ignoreCase = true) ||
                listOf("main/java", "main/kotlin", "androidMain/kotlin").any { dir.resolve("src/$it").containsKotlinCompose() }
        val hasUnitTests = "test" in sourceSets || "androidHostTest" in sourceSets || "withHostTestBuilder" in buildText
        val hasAndroidTests = "androidTest" in sourceSets || "androidDeviceTest" in sourceSets || "withDeviceTestBuilder" in buildText
        val kotlinIntegration =
            when {
                "com.android.kotlin.multiplatform.library" in pluginIds ||
                    "org.jetbrains.kotlin.multiplatform" in pluginIds -> KotlinIntegration.MULTIPLATFORM
                "org.jetbrains.kotlin.android" in pluginIds -> KotlinIntegration.ANDROID_PLUGIN
                type != AndroidModuleType.UNKNOWN -> KotlinIntegration.BUILT_IN
                pluginIds.none { it.startsWith("org.jetbrains.kotlin") } -> KotlinIntegration.NONE
                else -> KotlinIntegration.UNKNOWN
            }
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
            pluginIds = pluginIds,
            kotlinIntegration = kotlinIntegration,
            compileSdk = parseSdkLevel(buildText, "compileSdk"),
            minSdk = parseSdkLevel(buildText, "minSdk"),
            targetSdk = parseSdkLevel(buildText, "targetSdk"),
            sourceSets = sourceSets,
            hasScreenshotTests = sourceSets.any { it.startsWith("screenshotTest") } || "com.android.compose.screenshot" in pluginIds,
            managedDevices = parseCreatedNames(buildText, "localDevices"),
            managedDeviceGroups = parseCreatedNames(buildText, "groups"),
            confidence = if (buildFile != null) EvidenceConfidence.DECLARED else EvidenceConfidence.UNKNOWN,
        )
    }

    private fun parsePluginIds(
        buildText: String,
        aliases: Map<String, String>,
    ): List<String> {
        val ids = linkedSetOf<String>()
        Regex("""id\s*\(\s*["']([^"']+)["']\s*\)""").findAll(buildText).forEach { ids += it.groupValues[1] }
        Regex("""id\s+["']([^"']+)["']""").findAll(buildText).forEach { ids += it.groupValues[1] }
        Regex("""kotlin\s*\(\s*["']([^"']+)["']\s*\)""").findAll(buildText).forEach {
            ids += "org.jetbrains.kotlin.${it.groupValues[1]}"
        }
        Regex("""alias\s*\(\s*libs\.plugins\.([A-Za-z0-9_.]+)\s*\)""").findAll(buildText).forEach {
            aliases[it.groupValues[1]]?.let(ids::add)
        }
        return ids.sorted()
    }

    private fun parseSdkLevel(
        buildText: String,
        name: String,
    ): Int? =
        Regex("""\b$name(?:Version)?\s*(?:=|\s)\s*(\d+)""")
            .find(buildText)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun parseCreatedNames(
        buildText: String,
        sectionName: String,
    ): List<String> {
        val sectionStart = Regex("""(?m)^\s*$sectionName\s*\{""").find(buildText)?.range?.last ?: return emptyList()
        val body = StringBuilder()
        var depth = 0
        for (index in (sectionStart + 1) until buildText.length) {
            when (val character = buildText[index]) {
                '{' -> {
                    depth++
                    body.append(character)
                }
                '}' -> {
                    if (depth == 0) break
                    depth--
                    body.append(character)
                }
                else -> body.append(character)
            }
        }
        return Regex("""create\s*\(\s*["']([^"']+)["']\s*\)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    private fun detectSourceSets(moduleDir: Path): List<String> {
        val src = moduleDir.resolve("src")
        if (!src.exists() || !src.isDirectory()) return emptyList()
        return Files.list(src).use { paths ->
            paths
                .filter(Files::isDirectory)
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
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

    private fun parsePluginAliases(root: Path): Map<String, String> {
        val catalog = root.resolve("gradle/libs.versions.toml")
        if (!catalog.exists()) return emptyMap()
        val result = linkedMapOf<String, String>()
        var inPlugins = false
        Files.readAllLines(catalog).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inPlugins = line == "[plugins]"
            } else if (inPlugins && line.isNotBlank() && !line.startsWith("#")) {
                val alias = line.substringBefore("=").trim()
                val id = Regex("""\bid\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
                if (alias.isNotBlank() && id != null) result[alias.replace('-', '.')] = id
            }
        }
        return result
    }

    /**
     * Convention-plugin build-logic (e.g. Now in Android's `build-logic` composite build) hides the
     * real AGP plugin ID inside a compiled plugin class in an `includeBuild(...)` project, invisible to
     * a per-module plugin-id scan. This resolves, for each such custom plugin ID, the set of real plugin
     * IDs it applies internally (via `apply(plugin = "...")`), by statically matching `gradlePlugin { ... }`
     * registrations to their `implementationClass` source file. Best-effort only; an unresolved custom
     * plugin ID simply leaves the module's type as UNKNOWN.
     */
    private fun resolveConventionPluginIds(
        root: Path,
        settingsText: String,
        aliases: Map<String, String>,
    ): Map<String, Set<String>> {
        val includedBuilds = Regex("""includeBuild\s*\(\s*["']([^"']+)["']""").findAll(settingsText).map { it.groupValues[1] }.toList()
        if (includedBuilds.isEmpty()) return emptyMap()

        val resolved = mutableMapOf<String, MutableSet<String>>()
        for (buildDir in includedBuilds) {
            val buildRoot = root.resolve(buildDir)
            if (!buildRoot.isDirectory()) continue
            val kotlinFiles =
                runCatching {
                    Files.walk(buildRoot).use { stream ->
                        stream
                            .filter { (it.toString().endsWith(".kt") || it.toString().endsWith(".kts")) && "/build/" !in it.toString() }
                            .toList()
                    }
                }.getOrElse { emptyList() }
            val registrationFiles = kotlinFiles.filter { it.toString().endsWith(".gradle.kts") }
            for (file in registrationFiles) {
                val text = runCatching { Files.readString(file) }.getOrNull() ?: continue
                Regex("""register\(\s*"[^"]+"\s*\)\s*\{([^{}]*)\}""").findAll(text).forEach { match ->
                    val block = match.groupValues[1]
                    val implClass =
                        Regex("""implementationClass\s*=\s*["']([^"']+)["']""")
                            .find(block)
                            ?.groupValues
                            ?.get(1)
                            ?.substringAfterLast('.')
                    val literalId = Regex("""id\s*=\s*["']([^"']+)["']""").find(block)?.groupValues?.get(1)
                    val aliasPath = Regex("""id\s*=\s*libs\.plugins\.([A-Za-z0-9_.]+)""").find(block)?.groupValues?.get(1)
                    val pluginId = literalId ?: aliasPath?.let { aliasPathFor(aliases, it) }?.let(aliases::get)
                    if (implClass == null || pluginId == null) return@forEach
                    val implFile = kotlinFiles.firstOrNull { it.fileName.toString() == "$implClass.kt" } ?: return@forEach
                    val implText = runCatching { Files.readString(implFile) }.getOrNull() ?: return@forEach
                    val appliedIds =
                        Regex("""apply\(\s*plugin\s*=\s*["']([^"']+)["']\s*\)""").findAll(implText).map { it.groupValues[1] }.toSet()
                    if (appliedIds.isNotEmpty()) resolved.getOrPut(pluginId) { mutableSetOf() }.addAll(appliedIds)
                }
            }
        }
        // Convention plugins can apply other convention plugins (e.g. a "feature api" plugin applying an
        // "android library" plugin, which itself applies com.android.library); flatten those chains.
        return resolved.keys.associateWith { expandTransitively(it, resolved) }
    }

    private fun expandTransitively(
        id: String,
        rawApplied: Map<String, Set<String>>,
        visited: MutableSet<String> = mutableSetOf(),
    ): Set<String> {
        if (!visited.add(id)) return emptySet()
        val direct = rawApplied[id].orEmpty()
        return direct + direct.filter { it in rawApplied }.flatMap { expandTransitively(it, rawApplied, visited) }
    }

    /** Trims trailing Provider/`libs.plugins` accessor segments (`.get`, `.asProvider`, `.pluginId`, ...) until a known catalog alias matches. */
    private fun aliasPathFor(
        aliases: Map<String, String>,
        rawPath: String,
    ): String? {
        var candidate = rawPath
        repeat(4) {
            if (aliases.containsKey(candidate)) return candidate
            val lastDot = candidate.lastIndexOf('.')
            if (lastDot < 0) return null
            candidate = candidate.substring(0, lastDot)
        }
        return null
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
        if (module.type == AndroidModuleType.JVM_TOOLING) {
            commands +=
                CommandSpec(
                    id = "$moduleName-test",
                    command = listOf("./gradlew", "${module.path}:test"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 600,
                )
        }
        if (module.hasAndroidTests) {
            commands +=
                CommandSpec(
                    id = "$moduleName-connected-debug-android-test",
                    command = listOf("./gradlew", "${module.path}:connectedDebugAndroidTest"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = true,
                    timeoutSeconds = 900,
                )
        }
        module.managedDevices.forEach { device ->
            commands +=
                CommandSpec(
                    id = "$moduleName-$device-debug-android-test",
                    command = listOf("./gradlew", "${module.path}:${device}DebugAndroidTest"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 1200,
                )
        }
        module.managedDeviceGroups.forEach { group ->
            commands +=
                CommandSpec(
                    id = "$moduleName-$group-group-debug-android-test",
                    command = listOf("./gradlew", "${module.path}:${group}GroupDebugAndroidTest"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 1200,
                )
        }
        if (module.hasScreenshotTests) {
            commands +=
                CommandSpec(
                    id = "$moduleName-validate-debug-screenshot-test",
                    command = listOf("./gradlew", "${module.path}:validateDebugScreenshotTest"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 900,
                )
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
