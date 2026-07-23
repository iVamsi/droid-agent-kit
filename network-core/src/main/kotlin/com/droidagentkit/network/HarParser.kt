package com.droidagentkit.network

import com.droidagentkit.core.Redactor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Parses a mitmproxy HAR dump into redacted [FlowSummary] entries. Bodies are disabled by default
 * and only included when the caller opts in; even then they pass through the project [Redactor].
 * Sensitive headers (Authorization, Cookie, Set-Cookie, API keys, etc.) are always redacted.
 *
 * Certificate pinning / proxy bypass is surfaced as an unsupported condition: when flows were
 * captured but none produced a response, [CaptureQueryResult.pinningSuspected] is set so the MCP
 * provider can report `unsupported` rather than pretending interception succeeded.
 */
object HarParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val sensitiveHeaders =
        setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "x-auth-token",
            "x-csrf-token",
            "proxy-cookie",
        )

    fun parse(
        harJson: String,
        includeBodies: Boolean,
        redactor: Redactor,
    ): CaptureQueryResult {
        if (harJson.isBlank()) return CaptureQueryResult(emptyList(), listOf("empty-capture"), emptyList(), false)
        val har =
            runCatching { json.decodeFromString<HAR>(harJson) }.getOrElse {
                return CaptureQueryResult(emptyList(), listOf("invalid-har"), emptyList(), false)
            }
        val entries = har.log?.entries ?: emptyList()
        val flows = mutableListOf<FlowSummary>()
        val applied = linkedSetOf<String>()
        var sensitiveHeaderSeen = false
        for (entry in entries) {
            val req = entry.request
            val res = entry.response
            val (scheme, host, path) = splitUrl(req?.url)
            val reqHeaders = redactHeaders(req?.headers ?: emptyList(), redactor, applied) { sensitiveHeaderSeen = true }
            val resHeaders = redactHeaders(res?.headers ?: emptyList(), redactor, applied) { sensitiveHeaderSeen = true }
            val reqBody = bodyText(includeBodies, req?.postData?.text, redactor, applied)
            val resBody = bodyText(includeBodies, res?.content?.text, redactor, applied)
            flows.add(
                FlowSummary(
                    method = req?.method ?: "",
                    scheme = scheme,
                    host = host,
                    path = path,
                    status = res?.status ?: 0,
                    contentType = res?.content?.mimeType ?: "",
                    requestHeaders = reqHeaders,
                    responseHeaders = resHeaders,
                    requestBody = reqBody,
                    responseBody = resBody,
                    error = entry.response?.let { if (it.status == 0) "no-response" else null },
                ),
            )
        }
        if (sensitiveHeaderSeen) applied.add("sensitive-header")
        val pinningSuspected = flows.isNotEmpty() && flows.all { it.status == 0 }
        val warnings = mutableListOf<String>()
        if (pinningSuspected) warnings += "pinning-or-tls-suspected"
        if (flows.isEmpty()) warnings += "no-traffic-captured"
        return CaptureQueryResult(flows, warnings, applied.toList(), pinningSuspected)
    }

    private fun splitUrl(url: String?): Triple<String, String, String> {
        if (url.isNullOrBlank()) return Triple("", "", "")
        return runCatching {
            val uri = URI(url)
            val rawPath = uri.rawQuery?.let { "${uri.path}?$it" } ?: uri.path
            Triple(uri.scheme ?: "", uri.host ?: "", rawPath.ifBlank { "/" })
        }.getOrElse { Triple("", "", "") }
    }

    private fun bodyText(
        includeBodies: Boolean,
        raw: String?,
        redactor: Redactor,
        applied: MutableSet<String>,
    ): String? {
        if (!includeBodies || raw.isNullOrBlank()) return null
        val result = redactor.redact(raw)
        if (result.applied.isNotEmpty()) applied.addAll(result.applied)
        return result.text
    }

    private fun redactHeaders(
        headers: List<HarHeader>,
        redactor: Redactor,
        applied: MutableSet<String>,
        onSensitive: () -> Unit,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (h in headers) {
            val name = h.name ?: ""
            if (name.lowercase() in sensitiveHeaders) {
                onSensitive()
                out[name] = "[REDACTED]"
                continue
            }
            val value = h.value ?: ""
            val result = redactor.redact(value)
            if (result.applied.isNotEmpty()) applied.addAll(result.applied)
            out[name] = result.text
        }
        return out
    }
}

@Serializable
private data class HAR(
    @SerialName("log") val log: HarLog? = null,
)

@Serializable
private data class HarLog(
    @SerialName("entries") val entries: List<HarEntry> = emptyList(),
)

@Serializable
private data class HarEntry(
    @SerialName("request") val request: HarRequest? = null,
    @SerialName("response") val response: HarResponse? = null,
)

@Serializable
private data class HarRequest(
    @SerialName("method") val method: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("headers") val headers: List<HarHeader> = emptyList(),
    @SerialName("postData") val postData: HarPostData? = null,
)

@Serializable
private data class HarResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("headers") val headers: List<HarHeader> = emptyList(),
    @SerialName("content") val content: HarContent? = null,
)

@Serializable
private data class HarHeader(
    @SerialName("name") val name: String? = null,
    @SerialName("value") val value: String? = null,
)

@Serializable
private data class HarContent(
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("text") val text: String? = null,
)

@Serializable
private data class HarPostData(
    @SerialName("text") val text: String? = null,
)
