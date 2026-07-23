package com.droidagentkit.mcp.tools

import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.Capability
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.OutputMode
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.mcp.McpTool
import com.droidagentkit.storage.AdbExecutor
import com.droidagentkit.storage.AppDataSnapshotter
import com.droidagentkit.storage.PrefsParser
import com.droidagentkit.storage.SqliteInspector
import com.droidagentkit.storage.StorageException
import com.droidagentkit.storage.StorageOutcome
import java.nio.file.Files
import java.nio.file.Path

class StorageToolProvider(
    private val context: DeviceToolContext,
) : McpToolProvider {
    override val group: ToolGroup = ToolGroup.STORAGE

    private val toolNames: Set<String> =
        setOf(
            "android_db_list_databases",
            "android_db_schema",
            "android_db_query",
            "android_prefs_dump",
            "android_file_tree",
        )

    private val inspector = SqliteInspector()
    private val adbPath: String get() = context.config.safety.adbPath

    override fun listTools(): List<McpTool> = buildTools()

    override fun supports(name: String): Boolean = name in toolNames

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        when (name) {
            "android_db_list_databases" -> listDatabases(arguments)
            "android_db_schema" -> dbSchema(arguments)
            "android_db_query" -> dbQuery(arguments)
            "android_prefs_dump" -> prefsDump(arguments)
            "android_file_tree" -> fileTree(arguments)
            else -> unsupported(name)
        }

    private fun buildTools(): List<McpTool> =
        listOf(
            McpTool(
                name = "android_db_list_databases",
                title = "List app databases",
                description =
                    "Snapshot a debuggable app's databases (with WAL sidecars) and list them. " +
                        "Requires app_data_read and a successful run-as. Sensitive.",
                inputSchema = packageSchema("deviceSerial", "packageName"),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
            McpTool(
                name = "android_db_schema",
                title = "Inspect an app database schema",
                description =
                    "Snapshot a debuggable app and return the table/view schema of one database. " +
                        "Read-only; requires app_data_read.",
                inputSchema = packageSchema("deviceSerial", "packageName", "database"),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
            McpTool(
                name = "android_db_query",
                title = "Run a read-only SQLite query",
                description =
                    "Snapshot a debuggable app and run a bounded read-only SELECT/WITH/EXPLAIN/safe-PRAGMA " +
                        "against one database. Writes and multi-statements are rejected. Requires app_data_read.",
                inputSchema = packageSchema("deviceSerial", "packageName", "database", "sql"),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
            McpTool(
                name = "android_prefs_dump",
                title = "Dump SharedPreferences",
                description =
                    "Read a debuggable app's SharedPreferences XML and return typed key/value entries. " +
                        "Read-only; requires app_data_read. Sensitive.",
                inputSchema = packageSchema("deviceSerial", "packageName"),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
            McpTool(
                name = "android_file_tree",
                title = "List app files",
                description =
                    "List files under a debuggable app's private data directory via run-as. " +
                        "Read-only; requires app_data_read. Sensitive.",
                inputSchema = packageSchema("deviceSerial", "packageName"),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
        )

    private fun listDatabases(arguments: Map<String, Any?>): Map<String, Any> {
        val (auth, denied) = authorizeAndPackage(arguments, "android_db_list_databases")
        if (auth == null) return denied
        val root = context.resolveRoot(arguments)
        val snapshot =
            snapshotOrBlock(root, auth.serial, auth.pkg, "android_db_list_databases")
                ?: return blocked("snapshot-failed", "Could not snapshot app data for ${auth.pkg}.")
        val dbs = snapshot.databases.map { mapOf("name" to it.name, "sizeBytes" to it.sizeBytes, "hasWal" to it.hasWal) }
        val artifacts =
            snapshot.databases.map {
                context.registerExistingArtifact(
                    root,
                    snapshot.snapshotDir.resolve(it.name),
                    ArtifactType.SQLITE_SNAPSHOT,
                    "App DB snapshot: ${it.name}",
                    ArtifactSensitivity.SENSITIVE,
                )
            }
        return context.resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Listed ${dbs.size} database(s) for ${auth.pkg}.",
                warnings = snapshot.warnings,
                artifacts = artifacts,
            ),
        ) + mapOf("databases" to dbs)
    }

    private fun dbSchema(arguments: Map<String, Any?>): Map<String, Any> {
        val (auth, denied) = authorizeAndPackage(arguments, "android_db_schema")
        if (auth == null) return denied
        val database =
            arguments["database"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-database", "database is required for android_db_schema.")
        val root = context.resolveRoot(arguments)
        val snapshot =
            snapshotOrBlock(root, auth.serial, auth.pkg, "android_db_schema")
                ?: return blocked("snapshot-failed", "Could not snapshot app data for ${auth.pkg}.")
        return try {
            val schema =
                inspector.schema(snapshot.snapshotDir, database).map { table ->
                    mapOf(
                        "name" to table.name,
                        "type" to table.type,
                        "columns" to
                            table.columns.map {
                                mapOf(
                                    "name" to it.name,
                                    "type" to it.type,
                                    "notNull" to it.notNull,
                                    "primaryKey" to it.primaryKey,
                                )
                            },
                    )
                }
            context.resultMap(
                ToolResult(
                    status = ResultStatus.SUCCESS,
                    summary = "Schema for $database: ${schema.size} table(s).",
                    warnings = snapshot.warnings,
                ),
            ) +
                mapOf("schema" to schema)
        } catch (e: StorageException) {
            storageExceptionResult(e, snapshot.warnings)
        }
    }

    private fun dbQuery(arguments: Map<String, Any?>): Map<String, Any> {
        val (auth, denied) = authorizeAndPackage(arguments, "android_db_query")
        if (auth == null) return denied
        val database =
            arguments["database"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-database", "database is required for android_db_query.")
        val sql =
            arguments["sql"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-sql", "sql is required for android_db_query.")
        val root = context.resolveRoot(arguments)
        val snapshot =
            snapshotOrBlock(root, auth.serial, auth.pkg, "android_db_query")
                ?: return blocked("snapshot-failed", "Could not snapshot app data for ${auth.pkg}.")
        return try {
            val result = inspector.query(snapshot.snapshotDir, database, sql)
            context.resultMap(
                ToolResult(
                    status = ResultStatus.SUCCESS,
                    summary = "Query returned ${result.rows.size} row(s)${if (result.truncated) " (truncated)" else ""}.",
                    warnings =
                        snapshot.warnings + result.warnings,
                ),
            ) + mapOf("columns" to result.columns, "rows" to result.rows, "truncated" to result.truncated)
        } catch (e: StorageException) {
            storageExceptionResult(e, snapshot.warnings)
        }
    }

    private fun prefsDump(arguments: Map<String, Any?>): Map<String, Any> {
        val (auth, denied) = authorizeAndPackage(arguments, "android_prefs_dump")
        if (auth == null) return denied
        val root = context.resolveRoot(arguments)
        val executor = contextAdbExecutor(root)
        val snapshotter = AppDataSnapshotter(adbPath)
        if (!snapshotter.isDebuggable(executor, auth.serial, auth.pkg)) {
            return blocked("not-debuggable", "Package is not debuggable or run-as failed: ${auth.pkg}.")
        }
        val names = snapshotter.listPrefs(executor, auth.serial, auth.pkg)
        if (names.isEmpty()) {
            return context.resultMap(
                ToolResult(status = ResultStatus.SUCCESS, summary = "No SharedPreferences files found for ${auth.pkg}."),
            )
        }
        val entries = mutableListOf<Map<String, Any>>()
        for (name in names) {
            val xml =
                runCatching { snapshotter.readPrefs(executor, auth.serial, auth.pkg, name) }.getOrNull()
                    ?: continue
            val parsed = PrefsParser.parse(String(xml))
            entries.add(mapOf("file" to name, "entries" to parsed.map { mapOf("key" to it.key, "type" to it.type, "value" to it.value) }))
        }
        return context.resultMap(
            ToolResult(status = ResultStatus.SUCCESS, summary = "Dumped ${names.size} SharedPreferences file(s) for ${auth.pkg}."),
        ) +
            mapOf("prefs" to entries)
    }

    private fun fileTree(arguments: Map<String, Any?>): Map<String, Any> {
        val (auth, denied) = authorizeAndPackage(arguments, "android_file_tree")
        if (auth == null) return denied
        val path = arguments["path"]?.toString()?.takeIf { it.isNotBlank() } ?: "."
        val recursive = arguments["recursive"] == true
        val root = context.resolveRoot(arguments)
        val executor = contextAdbExecutor(root)
        val snapshotter = AppDataSnapshotter(adbPath)
        if (!snapshotter.isDebuggable(executor, auth.serial, auth.pkg)) {
            return blocked("not-debuggable", "Package is not debuggable or run-as failed: ${auth.pkg}.")
        }
        val entries =
            try {
                snapshotter.fileTree(executor, auth.serial, auth.pkg, path, recursive)
            } catch (e: StorageException) {
                return storageExceptionResult(e, emptyList())
            }
        val wire =
            entries.map {
                mapOf(
                    "name" to it.name,
                    "path" to it.path,
                    "size" to it.size,
                    "directory" to it.directory,
                    "symlink" to it.symlink,
                )
            }
        return context.resultMap(
            ToolResult(status = ResultStatus.SUCCESS, summary = "Listed ${entries.size} entr(y/ies) under $path for ${auth.pkg}."),
        ) +
            mapOf("entries" to wire)
    }

    private fun snapshotOrBlock(
        root: Path,
        serial: String,
        pkg: String,
        tool: String,
    ): com.droidagentkit.storage.AppDataSnapshot? {
        val executor = contextAdbExecutor(root)
        val snapshotter = AppDataSnapshotter(adbPath)
        return runCatching {
            snapshotter.snapshot(
                executor,
                serial,
                pkg,
                context.artifactOutputDir(root).resolve("storage/${context.safeId("$tool-${System.currentTimeMillis()}")}"),
            )
        }.getOrNull()
    }

    private fun contextAdbExecutor(root: Path): AdbExecutor =
        object : AdbExecutor {
            override fun run(
                command: List<String>,
                binary: Boolean,
            ): ByteArray {
                val result =
                    context.run(
                        root,
                        CommandSpec(
                            id = "storage-adb",
                            command = command,
                            workingDirectory = root.toString(),
                            mutatesProject = false,
                            requiresDevice = true,
                            timeoutSeconds = 60,
                            outputMode = if (binary) OutputMode.BINARY else OutputMode.TEXT,
                            sensitivity = ArtifactSensitivity.SENSITIVE,
                        ),
                    )
                val artifactPath = result.artifacts.firstOrNull()?.let { Path.of(it.path) }
                return artifactPath?.let { runCatching { Files.readAllBytes(it) }.getOrDefault(ByteArray(0)) } ?: ByteArray(0)
            }
        }

    private data class Auth(
        val serial: String,
        val pkg: String,
    )

    private fun authorizeAndPackage(
        arguments: Map<String, Any?>,
        tool: String,
    ): Pair<Auth?, Map<String, Any>> {
        val serial =
            arguments["deviceSerial"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return null to missingSerial(tool)
        val pkg =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return null to blocked("missing-package", "packageName is required for $tool.")
        val decision =
            context.authorize(
                OperationRequest(
                    operationId = tool,
                    requiredCapabilities = setOf(Capability.APP_DATA_READ),
                    destructive = false,
                    deviceSerial = serial,
                    packageName = pkg,
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return null to
                context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        }
        return Auth(serial, pkg) to emptyMap()
    }

    private fun storageExceptionResult(
        e: StorageException,
        warnings: List<String>,
    ): Map<String, Any> {
        val status =
            when (e.outcome) {
                StorageOutcome.UNSUPPORTED -> ResultStatus.UNSUPPORTED
                StorageOutcome.BLOCKED -> ResultStatus.BLOCKED
                else -> ResultStatus.FAILED
            }
        return context.resultMap(ToolResult(status = status, summary = e.message ?: e.code, warnings = warnings + e.code))
    }

    private fun missingSerial(tool: String): Map<String, Any> =
        context.resultMap(
            ToolResult(
                status = ResultStatus.BLOCKED,
                summary = "deviceSerial is required for $tool.",
                warnings = listOf("missing-device-serial"),
            ),
        )

    private fun blocked(
        code: String,
        reason: String,
    ): Map<String, Any> = context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = reason, warnings = listOf(code)))

    private fun unsupported(name: String): Map<String, Any> =
        context.resultMap(
            ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown storage tool: $name", warnings = listOf("unknown-tool")),
        )

    private fun packageSchema(vararg required: String): Map<String, Any> {
        val props =
            mapOf(
                "rootPath" to rootPathProp,
                "deviceSerial" to str("adb device serial to target."),
                "packageName" to str("Debuggable Android package to inspect."),
                "database" to str("Database filename within the app snapshot (for db schema/query)."),
                "sql" to str("Read-only SQL (SELECT/WITH/EXPLAIN/safe PRAGMA) for android_db_query."),
                "path" to str("Relative path under the app data dir for android_file_tree. Defaults to the data root."),
                "recursive" to bool("If true, list files recursively (bounded). Defaults to false."),
            )
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }

    private val toolResultSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "schemaVersion" to mapOf("type" to "string"),
                    "status" to mapOf("type" to "string"),
                    "summary" to mapOf("type" to "string"),
                    "artifacts" to mapOf("type" to "array"),
                    "redactionsApplied" to mapOf("type" to "array"),
                    "warnings" to mapOf("type" to "array"),
                ),
            "required" to listOf("schemaVersion", "status", "summary"),
        )

    private val rootPathProp: Map<String, Any> =
        mapOf(
            "type" to "string",
            "description" to "Absolute path of the target Android project root.",
        )

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)

    private fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)
}
