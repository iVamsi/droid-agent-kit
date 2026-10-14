package com.droidagentkit.core

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * XML parser that refuses doctypes and external entities.
 *
 * Dumps and app data are not trusted. A default JDK factory follows a DOCTYPE to a local file
 * or a URL while it parses, so a hostile document can read files on the machine running the server.
 */
object SecureXml {
    fun factory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(DISALLOW_DOCTYPE, true)
            setFeature(EXTERNAL_GENERAL_ENTITIES, false)
            setFeature(EXTERNAL_PARAMETER_ENTITIES, false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    fun parse(xml: String): Document = factory().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
}
