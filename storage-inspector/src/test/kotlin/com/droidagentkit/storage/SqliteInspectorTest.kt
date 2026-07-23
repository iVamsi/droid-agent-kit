package com.droidagentkit.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

class SqliteInspectorTest {
    private val inspector = SqliteInspector(maxRows = 5, maxBytesPerCell = 64, timeoutSeconds = 5)

    @Test
    fun `listDatabases finds db files and reports wal sidecars`() {
        val dir = Files.createTempDirectory("dak-storage-list")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))
        Files.write(dir.resolve("app.db-wal"), byteArrayOf(0))
        Files.write(dir.resolve("other.txt"), byteArrayOf(0))

        val dbs = inspector.listDatabases(dir)

        assertEquals(1, dbs.size)
        assertEquals("app.db", dbs[0].name)
        assertTrue(dbs[0].hasWal)
    }

    @Test
    fun `schema returns tables and columns`() {
        val dir = Files.createTempDirectory("dak-storage-schema")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1", "name" to "alice")))

        val schema = inspector.schema(dir, "app.db")

        assertEquals(1, schema.size)
        assertEquals("users", schema[0].name)
        assertTrue(schema[0].columns.any { it.name == "id" })
    }

    @Test
    fun `query returns bounded rows for select`() {
        val dir = Files.createTempDirectory("dak-storage-query")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1", "name" to "alice")))

        val result = inspector.query(dir, "app.db", "SELECT id, name FROM users")

        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals("1", result.rows[0][0])
    }

    @Test
    fun `query truncates at maxRows and reports truncated`() {
        val dir = Files.createTempDirectory("dak-storage-trunc")
        createDb(dir.resolve("app.db"), "items", (1..20).map { mapOf("v" to it.toString()) })

        val result = inspector.query(dir, "app.db", "SELECT v FROM items")

        assertEquals(5, result.rows.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `query rejects write statements`() {
        val dir = Files.createTempDirectory("dak-storage-write")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))

        val ex = assertStorage { inspector.query(dir, "app.db", "INSERT INTO users VALUES (2)") }
        assertEquals(StorageOutcome.BLOCKED, ex.outcome)
        assertEquals("write-not-allowed", ex.code)
    }

    @Test
    fun `query rejects multi-statement input`() {
        val dir = Files.createTempDirectory("dak-storage-multi")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))

        val ex = assertStorage { inspector.query(dir, "app.db", "SELECT 1; SELECT 2") }
        assertEquals("multi-statement-not-allowed", ex.code)
    }

    @Test
    fun `query rejects writable pragma assignments`() {
        val dir = Files.createTempDirectory("dak-storage-pragma-write")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))

        val ex = assertStorage { inspector.query(dir, "app.db", "PRAGMA journal_mode=WAL") }
        assertEquals("unsafe-pragma", ex.code)
    }

    @Test
    fun `query rejects pragmas not on the safe list`() {
        val dir = Files.createTempDirectory("dak-storage-pragma-unknown")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))

        val ex = assertStorage { inspector.query(dir, "app.db", "PRAGMA secure_delete") }
        assertEquals("unsafe-pragma", ex.code)
    }

    @Test
    fun `query allows safe read-only pragma`() {
        val dir = Files.createTempDirectory("dak-storage-pragma-ok")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))

        val result = inspector.query(dir, "app.db", "PRAGMA table_info(users)")

        assertTrue(result.columns.isNotEmpty())
    }

    @Test
    fun `query rejects database names that escape the snapshot`() {
        val dir = Files.createTempDirectory("dak-storage-escape")
        createDb(dir.resolve("app.db"), "users", listOf(mapOf("id" to "1")))

        val ex = assertStorage { inspector.query(dir, "../app.db", "SELECT 1") }
        assertEquals("invalid-database-name", ex.code)
    }

    @Test
    fun `encrypted or corrupt databases are reported unsupported`() {
        val dir = Files.createTempDirectory("dak-storage-cipher")
        Files.write(dir.resolve("secret.db"), ByteArray(64) { it.toByte() })

        val ex = assertStorage { inspector.schema(dir, "secret.db") }
        assertEquals(StorageOutcome.UNSUPPORTED, ex.outcome)
        assertEquals("sqlcipher-unsupported", ex.code)
    }

    @Test
    fun `snapshot with wal sidecars is readable in read-only mode`() {
        val dir = Files.createTempDirectory("dak-storage-wal")
        val dbPath = dir.resolve("app.db")
        val props = Properties().apply { setProperty("journal_mode", "WAL") }
        val writer = DriverManager.getConnection("jdbc:sqlite:$dbPath", props)
        writer.createStatement().use { it.execute("CREATE TABLE t(v INT)") }
        writer.createStatement().use { it.execute("INSERT INTO t VALUES (1)") }
        writer.createStatement().use { it.execute("INSERT INTO t VALUES (2)") }

        val snapshotDir = Files.createTempDirectory("dak-storage-wal-snap")
        Files.copy(dbPath, snapshotDir.resolve("app.db"))
        val wal = dir.resolve("app.db-wal")
        if (Files.exists(wal)) Files.copy(wal, snapshotDir.resolve("app.db-wal"))
        val shm = dir.resolve("app.db-shm")
        if (Files.exists(shm)) Files.copy(shm, snapshotDir.resolve("app.db-shm"))

        val result = inspector.query(snapshotDir, "app.db", "SELECT v FROM t ORDER BY v")

        assertEquals(2, result.rows.size)
        assertEquals("1", result.rows[0][0])
        assertEquals("2", result.rows[1][0])
        writer.close()
    }

    private fun createDb(
        path: Path,
        table: String,
        rows: List<Map<String, String>>,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { stmt ->
                val cols = rows.firstOrNull()?.keys?.toList() ?: listOf("v")
                stmt.execute("CREATE TABLE $table (${cols.joinToString(", ") { "$it TEXT" }})")
                rows.forEach { row ->
                    val values = cols.joinToString(", ") { "'${row[it]}'" }
                    stmt.execute("INSERT INTO $table (${cols.joinToString(", ")}) VALUES ($values)")
                }
            }
        }
    }

    private fun assertStorage(block: () -> Unit): StorageException {
        try {
            block()
            throw AssertionError("Expected StorageException.")
        } catch (e: StorageException) {
            return e
        }
    }
}
