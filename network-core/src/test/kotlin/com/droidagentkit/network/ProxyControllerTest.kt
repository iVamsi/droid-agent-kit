package com.droidagentkit.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyControllerTest {
    private val adb = "/usr/bin/adb"

    private fun fake(responses: Map<String, ByteArray>): NetworkCommandExecutor =
        object : NetworkCommandExecutor {
            override fun run(
                command: List<String>,
                binary: Boolean,
            ): ByteArray {
                val key = command.drop(3).joinToString(" ")
                return responses[key] ?: ByteArray(0)
            }
        }

    @Test
    fun `readProxy returns null for empty or none sentinel`() {
        val controller = ProxyController(adb)
        assertNull(controller.readProxy(fake(mapOf("shell settings get global http_proxy" to "".toByteArray())), "emulator-5554"))
        assertNull(controller.readProxy(fake(mapOf("shell settings get global http_proxy" to ":none".toByteArray())), "emulator-5554"))
    }

    @Test
    fun `readProxy returns prior value when set`() {
        val controller = ProxyController(adb)
        val prior =
            controller.readProxy(
                fake(mapOf("shell settings get global http_proxy" to "10.0.2.2:8888".toByteArray())),
                "emulator-5554",
            )
        assertEquals("10.0.2.2:8888", prior)
    }

    @Test
    fun `installProxy saves prior and puts new proxy`() {
        val calls = mutableListOf<String>()
        val exec =
            object : NetworkCommandExecutor {
                override fun run(
                    command: List<String>,
                    binary: Boolean,
                ): ByteArray {
                    calls += command.drop(3).joinToString(" ")
                    return if (command
                            .drop(
                                3,
                            ).joinToString(" ")
                            .endsWith("get global http_proxy")
                    ) {
                        "10.0.2.2:8888".toByteArray()
                    } else {
                        ByteArray(0)
                    }
                }
            }
        val snapshot = ProxyController(adb).installProxy(exec, "emulator-5554", "10.0.2.2", 8080)
        assertEquals("10.0.2.2:8888", snapshot.priorProxy)
        assertEquals("10.0.2.2:8080", snapshot.installedProxy)
        assertTrue(calls.any { it == "shell settings put global http_proxy 10.0.2.2:8080" })
    }

    @Test
    fun `restoreProxy clears when prior was null`() {
        val calls = mutableListOf<String>()
        val exec =
            object : NetworkCommandExecutor {
                override fun run(
                    command: List<String>,
                    binary: Boolean,
                ): ByteArray {
                    calls += command.drop(3).joinToString(" ")
                    return if (command.drop(3).joinToString(" ").endsWith("get global http_proxy")) {
                        ":none".toByteArray()
                    } else {
                        ByteArray(0)
                    }
                }
            }
        val error = ProxyController(adb).restoreProxy(exec, "emulator-5554", null)
        assertNull(error)
        assertTrue(calls.any { it == "shell settings put global http_proxy :none" })
    }

    @Test
    fun `restoreProxy puts prior value when present`() {
        val calls = mutableListOf<String>()
        val exec =
            object : NetworkCommandExecutor {
                override fun run(
                    command: List<String>,
                    binary: Boolean,
                ): ByteArray {
                    calls += command.drop(3).joinToString(" ")
                    return if (command.drop(3).joinToString(" ").endsWith("get global http_proxy")) {
                        "10.0.2.2:8888".toByteArray()
                    } else {
                        ByteArray(0)
                    }
                }
            }
        val error = ProxyController(adb).restoreProxy(exec, "emulator-5554", "10.0.2.2:8888")
        assertNull(error)
        assertTrue(calls.any { it == "shell settings put global http_proxy 10.0.2.2:8888" })
    }

    @Test
    fun `restoreProxy reports when verification fails after retries`() {
        val exec =
            object : NetworkCommandExecutor {
                override fun run(
                    command: List<String>,
                    binary: Boolean,
                ): ByteArray =
                    if (command.drop(3).joinToString(" ").endsWith("get global http_proxy")) {
                        "10.0.2.2:9999".toByteArray()
                    } else {
                        ByteArray(0)
                    }
            }
        val error = ProxyController(adb).restoreProxy(exec, "emulator-5554", null)
        assertTrue(error!!.contains("proxy-restore-unverified"))
    }
}
