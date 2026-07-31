package com.droidagentkit.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppDataSnapshotterTest {
    @Test
    fun `isDebuggable returns true when run-as id reports a uid`() {
        val exec = fakeAdb(mapOf("id" to "uid=10234(u0_a234) gid=10234".toByteArray()))
        val snapshotter = AppDataSnapshotter(adbPath = "adb")

        assertTrue(snapshotter.isDebuggable(exec, "emulator-5554", "com.example.app"))
    }

    @Test
    fun `isDebuggable returns false when run-as fails`() {
        val exec = fakeAdb(mapOf("id" to "Package 'com.example' is not debuggable".toByteArray()))
        val snapshotter = AppDataSnapshotter(adbPath = "adb")

        assertTrue(!snapshotter.isDebuggable(exec, "emulator-5554", "com.example"))
    }

    @Test
    fun `snapshot pulls db and wal sidecars into the host snapshot dir`() {
        val dbBytes = "sqlite".toByteArray()
        val walBytes = byteArrayOf(1, 2, 3)
        val responses =
            mutableMapOf(
                "run-as com.example.app id" to "uid=10234".toByteArray(),
                "run-as com.example.app ls databases" to "app.db\napp.db-wal\n".toByteArray(),
                "exec-out run-as com.example.app cat databases/app.db" to dbBytes,
                "exec-out run-as com.example.app cat databases/app.db-wal" to walBytes,
            )
        val exec = fakeAdb(responses)
        val snapshotter = AppDataSnapshotter(adbPath = "adb")
        val hostDir = Files.createTempDirectory("dak-snap-host")

        val snapshot = snapshotter.snapshot(exec, "emulator-5554", "com.example.app", hostDir)

        assertEquals(1, snapshot.databases.size)
        assertEquals("app.db", snapshot.databases[0].name)
        assertTrue(snapshot.databases[0].hasWal)
        assertTrue(Files.exists(snapshot.snapshotDir.resolve("app.db")))
        assertTrue(Files.exists(snapshot.snapshotDir.resolve("app.db-wal")))
        assertTrue(snapshot.warnings.contains("app-force-stopped"))
    }

    @Test
    fun `snapshot blocks for non-debuggable packages`() {
        val exec = fakeAdb(mapOf("run-as com.example.app id" to "not debuggable".toByteArray()))
        val snapshotter = AppDataSnapshotter(adbPath = "adb")
        val hostDir = Files.createTempDirectory("dak-snap-nodebug")

        try {
            snapshotter.snapshot(exec, "emulator-5554", "com.example.app", hostDir)
            throw AssertionError("Expected StorageException")
        } catch (e: StorageException) {
            assertEquals(StorageOutcome.BLOCKED, e.outcome)
            assertEquals("not-debuggable", e.code)
        }
    }

    @Test
    fun `fileTree parses ls output into bounded entries`() {
        val output =
            """
            total 16
            drwxrwx--x 2 u0_a1 u0_a1 4096 2024-01-01 12:00 cache
            -rw-rw------- 1 u0_a1 u0_a1   42 2024-01-01 12:00 app.db
            -rw-rw------- 1 u0_a1 u0_a1   80 2024-01-01 12:00 app.db-wal
            """.trimIndent()
        val entries = FileTreeParser.parseLs(output, basePath = "databases", maxEntries = 100)

        assertEquals(3, entries.size)
        assertTrue(entries.any { it.name == "cache" && it.directory })
        assertTrue(entries.any { it.name == "app.db" && !it.directory })
        assertEquals("databases/app.db", entries.first { it.name == "app.db" }.path)
    }

    @Test
    fun `fileTree parseFind bounds entries at maxEntries`() {
        val output = (1..50).joinToString("\n") { "databases/file$it" }
        val entries = FileTreeParser.parseFind(output, basePath = "databases", maxEntries = 10)

        assertEquals(10, entries.size)
    }

    @Test
    fun `fileTree parseFind drops entries outside the requested subtree`() {
        // find output comes from a device the toolkit does not trust; entries that claim to sit
        // outside the path that was asked for are discarded rather than surfaced.
        val output =
            listOf(
                "databases/app.db",
                "../../etc/passwd",
                "/data/data/com.other.app/x.db",
                "databases/nested/inner.db",
            ).joinToString("\n")

        val entries = FileTreeParser.parseFind(output, basePath = "databases", maxEntries = 100)

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.path.startsWith("databases/") })
    }

    @Test
    fun `prefs parser extracts typed entries`() {
        val xml =
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="theme">dark</string>
                <boolean name="enabled" value="true" />
                <int name="count" value="5" />
                <set name="tags"><string>a</string><string>b</string></set>
            </map>
            """.trimIndent()

        val entries = PrefsParser.parse(xml)

        assertEquals(4, entries.size)
        assertEquals("dark", entries.first { it.key == "theme" }.value)
        assertEquals("true", entries.first { it.key == "enabled" }.value)
        assertEquals("5", entries.first { it.key == "count" }.value)
        assertEquals("a,b", entries.first { it.key == "tags" }.value)
    }

    private fun fakeAdb(responses: Map<String, ByteArray>): AdbExecutor =
        object : AdbExecutor {
            override fun run(
                command: List<String>,
                binary: Boolean,
            ): ByteArray {
                val key = command.drop(3).joinToString(" ") // drop adb, -s, serial
                return responses.entries.firstOrNull { key.contains(it.key) }?.value ?: ByteArray(0)
            }
        }
}
