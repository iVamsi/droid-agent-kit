package com.droidagentkit.cli

import java.nio.file.Path

object ProjectLocator {
    fun resolve(
        requested: String,
        environment: Map<String, String> = System.getenv(),
        currentDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    ): Path {
        if (requested != "auto") return Path.of(requested).toAbsolutePath().normalize()
        val envPath = listOf(
            "CLAUDE_PROJECT_DIR",
            "CODEX_WORKSPACE",
            "CODEX_PROJECT_DIR",
            "PWD",
        ).firstNotNullOfOrNull { key ->
            environment[key]?.takeIf { it.isNotBlank() }?.let(Path::of)
        }
        return (envPath ?: currentDirectory).toAbsolutePath().normalize()
    }
}
