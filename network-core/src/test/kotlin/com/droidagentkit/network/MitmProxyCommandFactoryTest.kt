package com.droidagentkit.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class MitmProxyCommandFactoryTest {
    @Test
    fun `build produces a bounded mitmdump command with hardump and ssl insecure`() {
        // Rendered rather than hardcoded: Path.toString uses the platform separator, so a literal
        // "/tmp/cap.har" assertion passes on POSIX and fails on Windows for no real reason.
        val harPath = Paths.get("/tmp/cap.har")
        val cmd = MitmProxyCommandFactory.build("/usr/local/bin/mitmdump", "127.0.0.1", 8080, harPath)
        assertEquals("/usr/local/bin/mitmdump", cmd.first())
        assertTrue(cmd.contains("--listen-host"))
        assertTrue(cmd.contains("127.0.0.1"))
        assertTrue(cmd.contains("--listen-port"))
        assertTrue(cmd.contains("8080"))
        assertTrue(cmd.any { it.startsWith("hardump=") && it.endsWith(harPath.toString()) })
        assertTrue(cmd.any { it == "ssl_insecure=true" })
        assertTrue(cmd.contains("--quiet"))
    }
}
