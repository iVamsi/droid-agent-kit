package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager

class StorageToolProviderTest {
    private fun config(root: Path): DroidAgentConfig {
        val base = DroidAgentConfig.default()
        return base.copy(
            safety =
                base.safety.copy(
                    allowCapabilities = setOf(Capability.APP_DATA_READ),
                    adbPath = fakeAdb(root),
                ),
        )
    }

    private fun dispatcher(
        root: Path,
        config: DroidAgentConfig,
    ): DroidAgentMcpDispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.STORAGE))

    @Test
    fun `storage tools are listed only when the group is exposed`() {
        val root = Files.createTempDirectory("dak-storage-list")
        val dispatcher = dispatcher(root, config(root))

        val names = dispatcher.listTools().map { it.name }
        assertTrue(names.contains("android_db_list_databases"))
        assertTrue(names.contains("android_db_schema"))
        assertTrue(names.contains("android_db_query"))
        assertTrue(names.contains("android_prefs_dump"))
        assertTrue(names.contains("android_file_tree"))
    }

    @Test
    fun `storage tools are hidden when the group is not exposed`() {
        val root = Files.createTempDirectory("dak-storage-hidden")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        assertTrue(dispatcher.listTools().map { it.name }.none { it.startsWith("android_db_") })
    }

    @Test
    fun `db list is blocked without app_data_read capability`() {
        val root = Files.createTempDirectory("dak-storage-nocap")
        val base = DroidAgentConfig.default()
        val config = base.copy(safety = base.safety.copy(adbPath = fakeAdb(root)))
        val dispatcher = dispatcher(root, config)

        val result =
            dispatcher.call(
                "android_db_list_databases",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `db list is blocked without a package name`() {
        val root = Files.createTempDirectory("dak-storage-nopkg")
        val dispatcher = dispatcher(root, config(root))

        val result = dispatcher.call("android_db_list_databases", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-package"))
    }

    @Test
    fun `db list snapshots and lists databases`() {
        val root = Files.createTempDirectory("dak-storage-dblist")
        seedFakeDb(root)
        val dispatcher = dispatcher(root, config(root))

        val result =
            dispatcher.call(
                "android_db_list_databases",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                ),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val dbs = result["databases"] as List<Map<String, Any>>
        assertTrue(dbs.any { it["name"] == "app.db" })
        val artifacts = result["artifacts"] as List<*>
        assertTrue(artifacts.any { (it as Map<*, *>)["type"] == "sqlite_snapshot" })
    }

    @Test
    fun `db schema returns table schema`() {
        val root = Files.createTempDirectory("dak-storage-schema")
        seedFakeDb(root)
        val dispatcher = dispatcher(root, config(root))

        val result =
            dispatcher.call(
                "android_db_schema",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                    "database" to "app.db",
                ),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val schema = result["schema"] as List<Map<String, Any>>
        assertTrue(schema.any { it["name"] == "users" })
    }

    @Test
    fun `db query returns rows and rejects writes`() {
        val root = Files.createTempDirectory("dak-storage-query")
        seedFakeDb(root)
        val dispatcher = dispatcher(root, config(root))

        val result =
            dispatcher.call(
                "android_db_query",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                    "database" to "app.db",
                    "sql" to "SELECT id FROM users",
                ),
            )
        assertEquals("success", result["status"])
        assertEquals(listOf("id"), result["columns"])

        val blocked =
            dispatcher.call(
                "android_db_query",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                    "database" to "app.db",
                    "sql" to "INSERT INTO users VALUES (2)",
                ),
            )
        assertEquals("blocked", blocked["status"])
        assertTrue((blocked["warnings"] as List<*>).contains("write-not-allowed"))
    }

    @Test
    fun `prefs dump returns typed entries`() {
        val root = Files.createTempDirectory("dak-storage-prefs")
        val dispatcher = dispatcher(root, config(root))

        val result =
            dispatcher.call(
                "android_prefs_dump",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                ),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val prefs = result["prefs"] as List<Map<String, Any>>
        assertTrue(prefs.any { it["file"] == "prefs.xml" })
    }

    @Test
    fun `file tree lists entries`() {
        val root = Files.createTempDirectory("dak-storage-tree")
        val dispatcher = dispatcher(root, config(root))

        val result =
            dispatcher.call(
                "android_file_tree",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example.app",
                    "recursive" to true,
                ),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val entries = result["entries"] as List<Map<String, Any>>
        assertTrue(entries.any { it["name"] == "app.db" })
    }

    private fun seedFakeDb(root: Path) {
        val db = root.resolve("fake-app.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
                stmt.execute("INSERT INTO users VALUES (1, 'alice')")
            }
        }
    }

    private fun fakeAdb(root: Path): String {
        // These fakes are POSIX shell scripts, and that is load-bearing rather than incidental:
        // the `shell` branch re-evaluates joined argv the way a real device's /system/bin/sh does,
        // which is what lets them exercise shell-injection regressions at all. Reimplementing that
        // in batch would weaken the coverage it exists to provide, so on Windows these skip.
        org.junit.Assume.assumeTrue(
            "requires a POSIX shell for the fake adb/emulator scripts",
            !System.getProperty("os.name").startsWith("Windows"),
        )
        val dbPath = root.resolve("fake-app.db").toString()
        val script = root.resolve("fake-storage-adb.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            # ${'$'}1=-s ${'$'}2=serial ${'$'}3=verb ...
            verb="${'$'}3"
            case "${'$'}verb" in
              shell)
                cmd="${'$'}6"
                case "${'$'}cmd" in
                  id) echo "uid=10234" ;;
                  ls)
                    if [ "${'$'}7" = "-la" ]; then target="${'$'}8"; else target="${'$'}7"; fi
                    case "${'$'}target" in
                      databases) echo "app.db"; echo "app.db-wal" ;;
                      shared_prefs) echo "prefs.xml" ;;
                      *) echo "" ;;
                    esac
                    ;;
                  am) echo "force-stopped" ;;
                  find) echo "databases/app.db"; echo "databases/app.db-wal" ;;
                  *) echo "shell-ok" ;;
                esac
                ;;
              exec-out)
                path="${'$'}7"
                case "${'$'}path" in
                  databases/app.db) cat "$dbPath" ;;
                  databases/app.db-wal) printf '' ;;
                  shared_prefs/prefs.xml) printf '%s' '<map><string name="k">v</string></map>' ;;
                  *) echo "" ;;
                esac
                ;;
              *) echo "unknown" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }
}
