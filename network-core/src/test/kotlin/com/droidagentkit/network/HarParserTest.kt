package com.droidagentkit.network

import com.droidagentkit.core.RedactionConfig
import com.droidagentkit.core.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarParserTest {
    private val redactor = Redactor(RedactionConfig())

    @Test
    fun `parse returns empty result for blank input`() {
        val result = HarParser.parse("", includeBodies = false, redactor)
        assertTrue(result.flows.isEmpty())
        assertTrue(result.warnings.contains("empty-capture"))
    }

    @Test
    fun `parse returns invalid-har warning for malformed json`() {
        val result = HarParser.parse("{not json", includeBodies = false, redactor)
        assertTrue(result.warnings.contains("invalid-har"))
    }

    @Test
    fun `parse extracts method host path and status`() {
        val har =
            """
            {"log":{"entries":[
              {"request":{"method":"GET","url":"https://api.example.com/v1/users?x=1","headers":[]},
               "response":{"status":200,"headers":[],"content":{"mimeType":"application/json","text":"{}"}}}
            ]}}
            """.trimIndent()
        val result = HarParser.parse(har, includeBodies = false, redactor)
        assertEquals(1, result.flows.size)
        val f = result.flows[0]
        assertEquals("GET", f.method)
        assertEquals("https", f.scheme)
        assertEquals("api.example.com", f.host)
        assertEquals("/v1/users?x=1", f.path)
        assertEquals(200, f.status)
        assertEquals("application/json", f.contentType)
        assertNull(f.requestBody)
        assertNull(f.responseBody)
        assertFalse(result.pinningSuspected)
    }

    @Test
    fun `parse redacts sensitive headers and includes bodies only when opted in`() {
        val har =
            """
            {"log":{"entries":[
              {"request":{"method":"POST","url":"https://api.example.com/login",
                "headers":[{"name":"Authorization","value":"Bearer abc123"},{"name":"X-Api-Key","value":"AIzaAbCdEfGh1234567890"}],
                "postData":{"text":"password=hunter2"}},
               "response":{"status":200,"headers":[{"name":"Set-Cookie","value":"sid=secret"}],
                "content":{"mimeType":"text/plain","text":"token=supersecret123"}}}
            ]}}
            """.trimIndent()
        val withoutBodies = HarParser.parse(har, includeBodies = false, redactor)
        val req = withoutBodies.flows[0].requestHeaders
        assertEquals("[REDACTED]", req["Authorization"])
        assertEquals("[REDACTED]", req["X-Api-Key"])
        assertEquals("[REDACTED]", withoutBodies.flows[0].responseHeaders["Set-Cookie"])
        assertNull(withoutBodies.flows[0].requestBody)
        assertNull(withoutBodies.flows[0].responseBody)
        assertTrue(withoutBodies.redactionsApplied.contains("sensitive-header"))

        val withBodies = HarParser.parse(har, includeBodies = true, redactor)
        assertTrue(withBodies.flows[0].requestBody!!.contains("[REDACTED]"))
        assertTrue(withBodies.flows[0].responseBody!!.contains("[REDACTED]"))
    }

    @Test
    fun `parse flags pinning suspected when all flows have no response`() {
        val har =
            """
            {"log":{"entries":[
              {"request":{"method":"GET","url":"https://pinned.example.com/a","headers":[]},
               "response":{"status":0,"headers":[],"content":{}}},
              {"request":{"method":"GET","url":"https://pinned.example.com/b","headers":[]},
               "response":{"status":0,"headers":[],"content":{}}}
            ]}}
            """.trimIndent()
        val result = HarParser.parse(har, includeBodies = false, redactor)
        assertTrue(result.pinningSuspected)
        assertTrue(result.warnings.contains("pinning-or-tls-suspected"))
    }

    @Test
    fun `parse warns no-traffic-captured when entries is empty`() {
        val har = """{"log":{"entries":[]}}"""
        val result = HarParser.parse(har, includeBodies = false, redactor)
        assertTrue(result.warnings.contains("no-traffic-captured"))
        assertFalse(result.pinningSuspected)
    }
}
