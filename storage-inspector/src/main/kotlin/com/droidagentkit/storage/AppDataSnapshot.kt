package com.droidagentkit.storage

import com.droidagentkit.core.ShellQuote
import java.nio.file.Files
import java.nio.file.Path

/** Executes a fully-formed adb command list and returns its stdout bytes. Implemented by the MCP provider. */
interface AdbExecutor {
    fun run(
        command: List<String>,
        binary: Boolean,
    ): ByteArray
}

data class AppDataSnapshot(
    val snapshotDir: Path,
    val databases: List<DatabaseInfo>,
    val warnings: List<String>,
)

data class PrefEntry(
    val key: String,
    val type: String,
    val value: String,
)

data class FileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val directory: Boolean,
    val symlink: Boolean,
)

/**
 * Orchestrates a consistent app-data snapshot for a debuggable package: verifies `run-as`,
 * force-stops the app, then copies database files (with `-wal`/`-shm` sidecars) and prefs XML into a
 * host snapshot directory. All adb commands are built here; execution is delegated to [AdbExecutor]
 * so the snapshotter stays hermetically testable.
 */
class AppDataSnapshotter(
    private val adbPath: String,
) {
    fun isDebuggable(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
    ): Boolean {
        val out =
            runCatching { exec.run(shell(serial, "run-as", packageName, "id"), binary = false) }
                .getOrDefault(ByteArray(0))
        return String(out).contains("uid=")
    }

    fun snapshot(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
        hostDir: Path,
    ): AppDataSnapshot {
        if (!isDebuggable(exec, serial, packageName)) {
            throw StorageException(StorageOutcome.BLOCKED, "not-debuggable", "Package is not debuggable or run-as failed: $packageName")
        }
        // Force-stop for a consistent snapshot; warn that app state changed.
        runCatching { exec.run(shell(serial, "am", "force-stop", packageName), binary = false) }
        val snapshotDir = hostDir.resolve("snapshot").also { Files.createDirectories(it) }
        val warnings = mutableListOf("app-force-stopped")

        val dbListing = listDatabases(exec, serial, packageName)
        val pulled = mutableListOf<DatabaseInfo>()
        for (name in dbListing) {
            if (!name.endsWith(".db")) continue
            // The listing comes off the device, so treat each entry as untrusted input to a host
            // path the same way SqliteInspector.confinedDatabase already does for query arguments.
            if (!isBareFileName(name)) {
                warnings += "skipped-unsafe-database-name"
                continue
            }
            pullFile(exec, serial, packageName, "databases/$name", snapshotDir.resolve(name))
            val hasWal = dbListing.any { it == "$name-wal" }
            val hasShm = dbListing.any { it == "$name-shm" }
            if (hasWal) pullFile(exec, serial, packageName, "databases/$name-wal", snapshotDir.resolve("$name-wal"))
            if (hasShm) pullFile(exec, serial, packageName, "databases/$name-shm", snapshotDir.resolve("$name-shm"))
            pulled.add(DatabaseInfo(name = name, sizeBytes = Files.size(snapshotDir.resolve(name)), hasWal = hasWal))
        }
        return AppDataSnapshot(snapshotDir = snapshotDir, databases = pulled, warnings = warnings)
    }

    fun listPrefs(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
    ): List<String> {
        val out =
            runCatching {
                exec.run(shell(serial, "run-as", packageName, "ls", "shared_prefs"), binary = false)
            }.getOrDefault(ByteArray(0))
        return String(out).lines().map { it.trim() }.filter { it.endsWith(".xml") }
    }

    fun readPrefs(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
        name: String,
    ): ByteArray {
        if (!name.endsWith(".xml") || name.contains('/') || name.contains("..")) {
            throw StorageException(StorageOutcome.BLOCKED, "invalid-prefs-name", "Prefs name must be a bare .xml filename.")
        }
        return exec.run(listOf(adbPath, "-s", serial, "exec-out", "run-as", packageName, "cat", "shared_prefs/$name"), binary = true)
    }

    fun fileTree(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
        path: String,
        recursive: Boolean,
        maxEntries: Int = 1000,
    ): List<FileEntry> {
        val rel = sanitizeRelativePath(path)
        val out =
            if (recursive) {
                runCatching {
                    exec.run(shell(serial, "run-as", packageName, "find", rel), binary = false)
                }.getOrDefault(ByteArray(0))
            } else {
                runCatching {
                    exec.run(shell(serial, "run-as", packageName, "ls", "-la", rel), binary = false)
                }.getOrDefault(ByteArray(0))
            }
        val text = String(out)
        return if (recursive) {
            FileTreeParser.parseFind(
                text,
                basePath = rel,
                maxEntries,
            )
        } else {
            FileTreeParser.parseLs(text, basePath = rel, maxEntries)
        }
    }

    private fun listDatabases(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
    ): List<String> {
        val out =
            runCatching {
                exec.run(shell(serial, "run-as", packageName, "ls", "databases"), binary = false)
            }.getOrDefault(ByteArray(0))
        return String(out).lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun pullFile(
        exec: AdbExecutor,
        serial: String,
        packageName: String,
        deviceRel: String,
        dest: Path,
    ) {
        val bytes = exec.run(listOf(adbPath, "-s", serial, "exec-out", "run-as", packageName, "cat", deviceRel), binary = true)
        Files.write(dest, bytes)
    }

    /**
     * Builds `adb -s <serial> shell …` with every post-shell argument single-quoted.
     *
     * `adb shell` re-joins those arguments into `/system/bin/sh -c`, so an unquoted path or package
     * name can inject a second command outside `run-as`. [ShellQuote] closes that.
     */
    private fun shell(
        serial: String,
        vararg args: String,
    ): List<String> = listOf(adbPath, "-s", serial, "shell") + args.map(ShellQuote::quote)

    /**
     * A device-listed name must be a bare filename before it is resolved against a host directory.
     *
     * The listing is parsed from command output on a device the toolkit does not trust, so this is
     * checked rather than assumed. Matching an explicit character set also keeps whitespace out:
     * the pulled path is passed to `adb exec-out run-as … cat`, whose arguments the device shell
     * re-splits, so a name containing a space would not round-trip.
     */
    private fun isBareFileName(name: String): Boolean = name != "." && name != ".." && BARE_FILE_NAME.matches(name)

    private companion object {
        val BARE_FILE_NAME = Regex("^[A-Za-z0-9._-]+$")
    }

    private fun sanitizeRelativePath(path: String): String {
        val trimmed = path.trim().trimStart('/').ifBlank { "." }
        if (trimmed.contains("..")) {
            throw StorageException(StorageOutcome.BLOCKED, "path-escape", "Path may not traverse parent directories.")
        }
        return trimmed
    }
}

/** Parses Android SharedPreferences XML into typed entries using the JDK DOM parser. */
object PrefsParser {
    fun parse(xml: String): List<PrefEntry> {
        if (xml.isBlank()) return emptyList()
        val factory =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val entries = mutableListOf<PrefEntry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node !is org.w3c.dom.Element) continue
            val key = node.getAttribute("name") ?: ""
            val type = node.tagName
            val value =
                when (type) {
                    "set" -> {
                        val items = mutableListOf<String>()
                        val children = node.childNodes
                        for (j in 0 until children.length) {
                            val child = children.item(j)
                            if (child is org.w3c.dom.Element && child.tagName == "string") items += child.textContent ?: ""
                        }
                        items.joinToString(",")
                    }
                    else -> node.getAttribute("value")?.takeIf { it.isNotBlank() } ?: (node.textContent ?: "")
                }
            entries.add(PrefEntry(key = key, type = type, value = value))
        }
        return entries
    }
}

/** Parses `ls -la` and `find` output into bounded file listings. */
object FileTreeParser {
    fun parseLs(
        output: String,
        basePath: String,
        maxEntries: Int,
    ): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        for (line in output.lineSequence()) {
            if (entries.size >= maxEntries) break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("total ")) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 8) continue
            val perms = parts[0]
            val size = parts[4].toLongOrNull() ?: 0L
            val name = parts.drop(7).joinToString(" ")
            if (name == "." || name == "..") continue
            entries.add(
                FileEntry(
                    name = name,
                    path = if (basePath == ".") name else "$basePath/$name",
                    size = size,
                    directory = perms.startsWith("d"),
                    symlink = perms.startsWith("l"),
                ),
            )
        }
        return entries
    }

    /**
     * `find <rel>` already prefixes each line with the path it was given, so [basePath] is not
     * needed to build the result; it is used to confirm the device stayed inside the subtree that
     * was asked for. Output arriving from a device the toolkit does not trust should not be able to
     * introduce entries outside the requested root.
     */
    fun parseFind(
        output: String,
        basePath: String,
        maxEntries: Int,
    ): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        for (line in output.lineSequence()) {
            if (entries.size >= maxEntries) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (basePath != "." && trimmed != basePath && !trimmed.startsWith("$basePath/")) continue
            val name = trimmed.substringAfterLast('/')
            entries.add(FileEntry(name = name, path = trimmed, size = 0L, directory = false, symlink = false))
        }
        return entries
    }
}
