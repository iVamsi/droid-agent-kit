package com.droidagentkit.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

enum class StorageOutcome { SUCCESS, UNSUPPORTED, BLOCKED, FAILED }

class StorageException(
    val outcome: StorageOutcome,
    val code: String,
    message: String,
) : RuntimeException(message)

data class DatabaseInfo(
    val name: String,
    val sizeBytes: Long,
    val hasWal: Boolean,
)

data class ColumnInfo(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKey: Boolean,
)

data class TableSchema(
    val name: String,
    val type: String,
    val columns: List<ColumnInfo>,
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
    val warnings: List<String>,
)

/**
 * Read-only SQLite inspector for app-data snapshots. Opens host snapshot files in engine-level
 * read-only mode, disables extension loading, rejects writes/multi-statements/unsafe PRAGMAs, and
 * bounds rows/cells/duration. SQLCipher and custom SQLite engines are reported as UNSUPPORTED.
 */
class SqliteInspector(
    private val maxRows: Int = 100,
    private val maxBytesPerCell: Int = 4096,
    private val timeoutSeconds: Long = 5,
) {
    fun listDatabases(snapshotDir: Path): List<DatabaseInfo> {
        ensureSnapshotDir(snapshotDir)
        val dbs = mutableListOf<DatabaseInfo>()
        Files.list(snapshotDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".db") }.forEach { db ->
                val name = db.fileName.toString()
                val hasWal = Files.exists(snapshotDir.resolve("$name-wal")) || Files.exists(snapshotDir.resolve("$name-journal"))
                dbs.add(DatabaseInfo(name = name, sizeBytes = Files.size(db), hasWal = hasWal))
            }
        }
        return dbs.sortedBy { it.name }
    }

    fun schema(
        snapshotDir: Path,
        database: String,
    ): List<TableSchema> {
        val dbPath = confinedDatabase(snapshotDir, database)
        return openReadOnly(dbPath) { conn ->
            conn.createStatement().use { stmt ->
                val tables =
                    stmt
                        .executeQuery(
                            "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
                        ).use { rs ->
                            val out = mutableListOf<Pair<String, String>>()
                            while (rs.next()) out.add(rs.getString(1) to rs.getString(2))
                            out
                        }
                tables.map { (name, type) ->
                    TableSchema(name = name, type = type, columns = columnsFor(conn, name))
                }
            }
        }
    }

    fun query(
        snapshotDir: Path,
        database: String,
        sql: String,
    ): QueryResult {
        val dbPath = confinedDatabase(snapshotDir, database)
        val validated = validateQuery(sql)
        return openReadOnly(dbPath) { conn ->
            conn.createStatement().use { stmt ->
                stmt.queryTimeout = timeoutSeconds.toInt().coerceAtLeast(1)
                stmt.maxRows = maxRows + 1
                val warnings = mutableListOf<String>()
                val rs =
                    try {
                        stmt.executeQuery(validated)
                    } catch (e: java.sql.SQLTimeoutException) {
                        throw StorageException(StorageOutcome.FAILED, "query-timeout", "Query exceeded the ${timeoutSeconds}s budget.")
                    }
                rs.use {
                    val meta = it.metaData
                    val columnCount = meta.columnCount
                    val columns = (1..columnCount).map { meta.getColumnLabel(it) }
                    val rows = mutableListOf<List<String?>>()
                    while (it.next() && rows.size < maxRows) {
                        val row =
                            (1..columnCount).map { c ->
                                val raw = it.getString(c)
                                if (raw != null && raw.toByteArray().size > maxBytesPerCell) {
                                    warnings += "cell-truncated"
                                    raw.take(maxBytesPerCell / 4)
                                } else {
                                    raw
                                }
                            }
                        rows.add(row)
                    }
                    val truncated = it.next() || rows.size == maxRows
                    QueryResult(columns = columns, rows = rows, truncated = truncated, warnings = warnings)
                }
            }
        }
    }

    private fun columnsFor(
        conn: java.sql.Connection,
        table: String,
    ): List<ColumnInfo> {
        conn.prepareStatement("PRAGMA table_info(${quoteIdent(table)})").use { stmt ->
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<ColumnInfo>()
                while (rs.next()) {
                    out.add(
                        ColumnInfo(
                            name = rs.getString("name"),
                            type = rs.getString("type") ?: "",
                            notNull = rs.getInt("notnull") != 0,
                            primaryKey = rs.getInt("pk") != 0,
                        ),
                    )
                }
                return out
            }
        }
    }

    private fun <T> openReadOnly(
        dbPath: Path,
        block: (java.sql.Connection) -> T,
    ): T {
        if (!Files.exists(dbPath)) {
            throw StorageException(StorageOutcome.BLOCKED, "database-not-found", "Database not found in snapshot: ${dbPath.fileName}")
        }
        val props =
            Properties().apply {
                setProperty("open_mode", "1") // SQLITE_OPEN_READONLY
                setProperty("query_only", "1")
            }
        val conn =
            try {
                DriverManager.getConnection("jdbc:sqlite:$dbPath", props)
            } catch (e: java.sql.SQLException) {
                if (isCipherError(e)) {
                    throw StorageException(
                        StorageOutcome.UNSUPPORTED,
                        "sqlcipher-unsupported",
                        "Database appears encrypted (SQLCipher/custom SQLite). Read-only inspection is unsupported.",
                    )
                }
                throw StorageException(StorageOutcome.FAILED, "database-open-failed", e.message ?: "Could not open database.")
            }
        return try {
            conn.use { block(it) }
        } catch (e: java.sql.SQLException) {
            if (isCipherError(e)) {
                throw StorageException(
                    StorageOutcome.UNSUPPORTED,
                    "sqlcipher-unsupported",
                    "Database appears encrypted (SQLCipher/custom SQLite). Read-only inspection is unsupported.",
                )
            }
            throw StorageException(StorageOutcome.FAILED, "database-query-failed", e.message ?: "Query failed.")
        }
    }

    private fun isCipherError(e: java.sql.SQLException): Boolean {
        val msg = (e.message ?: "").lowercase()
        return "file is not a database" in msg || "not a database" in msg || "file is encrypted" in msg
    }

    private fun ensureSnapshotDir(snapshotDir: Path) {
        if (!Files.isDirectory(snapshotDir)) {
            throw StorageException(StorageOutcome.BLOCKED, "snapshot-missing", "Snapshot directory does not exist: $snapshotDir")
        }
    }

    private fun confinedDatabase(
        snapshotDir: Path,
        database: String,
    ): Path {
        if (database.isBlank() || database.contains("..") || database.contains('/') || database.contains('\\')) {
            throw StorageException(
                StorageOutcome.BLOCKED,
                "invalid-database-name",
                "Database name must be a bare filename within the snapshot.",
            )
        }
        val resolved = snapshotDir.resolve(database).normalize()
        if (!resolved.startsWith(snapshotDir.normalize())) {
            throw StorageException(StorageOutcome.BLOCKED, "path-escape", "Database path escapes the snapshot directory.")
        }
        return resolved
    }

    private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private fun validateQuery(sql: String): String {
        val stripped = stripLiteralsAndComments(sql).trim()
        if (stripped.isEmpty()) {
            throw StorageException(StorageOutcome.BLOCKED, "empty-query", "SQL query is empty.")
        }
        if (stripped.contains(';')) {
            throw StorageException(StorageOutcome.BLOCKED, "multi-statement-not-allowed", "Only a single read-only statement is permitted.")
        }
        val upper = stripped.uppercase()
        val forbidden =
            listOf(
                "INSERT",
                "UPDATE",
                "DELETE",
                "CREATE",
                "DROP",
                "ALTER",
                "ATTACH",
                "DETACH",
                "REPLACE",
                "VACUUM",
                "REINDEX",
                "BEGIN",
                "COMMIT",
                "ROLLBACK",
                "SAVEPOINT",
                "PRAGMA ",
            )
        for (kw in forbidden) {
            if (upper.startsWith(kw) || upper.contains(" $kw") || upper.contains("\t$kw") || upper.contains("\n$kw")) {
                if (kw == "PRAGMA ") {
                    validatePragma(stripped)
                } else {
                    throw StorageException(
                        StorageOutcome.BLOCKED,
                        "write-not-allowed",
                        "Only SELECT/WITH/EXPLAIN and safe read-only PRAGMA queries are permitted.",
                    )
                }
            }
        }
        val allowedStart =
            upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("EXPLAIN") || upper.startsWith("PRAGMA")
        if (!allowedStart) {
            throw StorageException(
                StorageOutcome.BLOCKED,
                "write-not-allowed",
                "Only SELECT/WITH/EXPLAIN and safe read-only PRAGMA queries are permitted.",
            )
        }
        return sql.trim().trimEnd(';').trim()
    }

    private fun validatePragma(stripped: String) {
        val body = stripped.substringAfter("PRAGMA", "").trim()
        if (body.contains('=') || body.contains('(').not() && body.contains("= ")) {
            throw StorageException(StorageOutcome.BLOCKED, "unsafe-pragma", "Writable PRAGMA assignments are not allowed.")
        }
        val name = body.substringBefore(' ').substringBefore('(').lowercase()
        if (name !in SAFE_PRAGMAS) {
            throw StorageException(StorageOutcome.BLOCKED, "unsafe-pragma", "PRAGMA '$name' is not on the safe read-only list.")
        }
    }

    private fun stripLiteralsAndComments(sql: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < sql.length) {
                        if (sql[i] == quote) {
                            if (i + 1 < sql.length && sql[i + 1] == quote) {
                                i += 2
                                continue
                            }
                            break
                        }
                        i++
                    }
                    out.append(' ')
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i += 2
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    companion object {
        val SAFE_PRAGMAS: Set<String> =
            setOf(
                "table_info",
                "table_list",
                "database_list",
                "foreign_key_list",
                "index_list",
                "index_info",
                "page_size",
                "journal_mode",
                "encoding",
                "schema_version",
                "user_version",
                "compile_options",
                "pragma_list",
                "function_list",
                "module_list",
                "collation_list",
            )
    }
}
