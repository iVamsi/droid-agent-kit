package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class SecureXmlTest {
    @Test
    fun `parses an ordinary document`() {
        val document = SecureXml.parse("<root><child>ok</child></root>")

        assertEquals("root", document.documentElement.tagName)
        assertEquals("ok", document.documentElement.textContent)
    }

    @Test
    fun `rejects a doctype instead of reading a local file`() {
        val xml =
            """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><root>&xxe;</root>"""
        try {
            val document = SecureXml.parse(xml)
            fail("expected the doctype to be rejected, parsed: ${document.documentElement.textContent}")
        } catch (error: Exception) {
            val text = error.toString()
            assertFalse(text.contains("root:"))
            assertFalse(text.contains("nobody:"))
        }
    }
}
