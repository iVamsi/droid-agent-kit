package com.droidagentkit.mcp.tools

/**
 * Turns an obfuscated release stack trace back into original symbols using an R8/ProGuard
 * `mapping.txt`.
 *
 * Release crashes are the ones that matter and the ones nobody can read: `a.b.c.a(Unknown Source)`
 * tells an agent nothing, so crash triage on a release build was previously guesswork. This is a
 * focused reimplementation rather than a call out to R8's `retrace`, because that would add a
 * multi-megabyte dependency for one text transformation, and the mapping format is stable and
 * small enough to parse directly.
 *
 * Deliberately conservative: a frame that cannot be mapped with confidence is left exactly as it
 * was. A wrong symbol in a stack trace is worse than an obfuscated one, because it sends the
 * reader somewhere real that had nothing to do with the crash.
 */
object RetraceEngine {
    internal data class MethodMapping(
        val originalName: String,
        val originalStartLine: Int,
        val originalEndLine: Int,
        val obfuscatedStartLine: Int,
        val obfuscatedEndLine: Int,
    )

    internal data class ClassMapping(
        val originalName: String,
        /** Obfuscated method name to its candidate original mappings, in file order. */
        val methods: Map<String, List<MethodMapping>>,
    )

    class Mapping internal constructor(
        internal val classes: Map<String, ClassMapping>,
    ) {
        val size: Int get() = classes.size
    }

    /**
     * Parses a `mapping.txt`.
     *
     * The format is line-oriented: a class line ends in `:`, and its members are indented beneath
     * it. Members can carry a line-range prefix (`12:15:void foo():30:33 -> a`) which is what makes
     * inlined frames recoverable.
     */
    fun parse(mappingText: String): Mapping {
        val classes = mutableMapOf<String, ClassMapping>()
        var currentObfuscated: String? = null
        var currentOriginal: String? = null
        var methods = mutableMapOf<String, MutableList<MethodMapping>>()

        fun flush() {
            val obf = currentObfuscated ?: return
            val orig = currentOriginal ?: return
            classes[obf] = ClassMapping(orig, methods)
        }

        mappingText.lineSequence().forEach { rawLine ->
            if (rawLine.isBlank() || rawLine.trimStart().startsWith("#")) return@forEach
            val isMember = rawLine.first().isWhitespace()
            val line = rawLine.trim()

            if (!isMember && line.endsWith(":")) {
                flush()
                val body = line.dropLast(1)
                val arrow = body.indexOf(" -> ")
                if (arrow < 0) {
                    currentObfuscated = null
                    currentOriginal = null
                    return@forEach
                }
                currentOriginal = body.substring(0, arrow).trim()
                currentObfuscated = body.substring(arrow + 4).trim()
                methods = mutableMapOf()
                return@forEach
            }

            if (currentObfuscated == null) return@forEach
            val arrow = line.indexOf(" -> ")
            if (arrow < 0) return@forEach
            val left = line.substring(0, arrow).trim()
            val obfuscatedMember = line.substring(arrow + 4).trim()
            // Fields have no parentheses; only methods appear in stack frames.
            if (!left.contains('(')) return@forEach

            val (obfStart, obfEnd, remainder) = stripLineRange(left)
            val signature = remainder.substringBefore('(').trim()
            val originalName = signature.substringAfterLast(' ').ifBlank { signature }
            val trailing = remainder.substringAfter(')', "")
            val (origStart, origEnd) = parseOriginalRange(trailing, obfStart, obfEnd)

            methods
                .getOrPut(obfuscatedMember) { mutableListOf() }
                .add(MethodMapping(originalName, origStart, origEnd, obfStart, obfEnd))
        }
        flush()
        return Mapping(classes)
    }

    /** `12:15:void foo()...` -> (12, 15, "void foo()..."); no prefix -> (0, 0, line). */
    private fun stripLineRange(left: String): Triple<Int, Int, String> {
        val match = Regex("^(\\d+):(\\d+):(.*)$").find(left) ?: return Triple(0, 0, left)
        return Triple(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3],
        )
    }

    /** Trailing `:30:33` after the signature gives the original line range. */
    private fun parseOriginalRange(
        trailing: String,
        obfStart: Int,
        obfEnd: Int,
    ): Pair<Int, Int> {
        val match = Regex("^:(\\d+)(?::(\\d+))?").find(trailing) ?: return obfStart to obfEnd
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].takeIf { it.isNotBlank() }?.toInt() ?: start
        return start to end
    }

    private val FRAME = Regex("^(\\s*at\\s+)([\\w$.]+)\\.([\\w$<>]+)\\((.*?)\\)(.*)$")

    /** Rewrites every `at ...` frame it can map, leaving everything else byte-for-byte unchanged. */
    fun retrace(
        stackTrace: String,
        mapping: Mapping,
    ): String =
        stackTrace
            .lineSequence()
            .joinToString("\n") { line -> retraceLine(line, mapping) }
            .let { if (stackTrace.endsWith("\n")) "$it\n" else it }

    private fun retraceLine(
        line: String,
        mapping: Mapping,
    ): String {
        val match = FRAME.find(line) ?: return retraceExceptionHeader(line, mapping)
        val (prefix, obfClass, obfMethod, source, suffix) = match.destructured
        val classMapping = mapping.classes[obfClass] ?: return line

        val lineNumber =
            Regex(":(\\d+)$")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        val candidates = classMapping.methods[obfMethod].orEmpty()
        val resolved =
            when {
                candidates.isEmpty() -> null
                lineNumber == null -> candidates.takeIf { it.size == 1 }?.first()
                else ->
                    candidates.firstOrNull { lineNumber in it.obfuscatedStartLine..it.obfuscatedEndLine }
                        ?: candidates.takeIf { it.size == 1 }?.first()
            }

        val originalMethod = resolved?.originalName ?: obfMethod
        val originalLine =
            if (resolved != null && lineNumber != null && resolved.obfuscatedStartLine > 0) {
                (resolved.originalStartLine + (lineNumber - resolved.obfuscatedStartLine))
                    .coerceIn(resolved.originalStartLine, maxOf(resolved.originalEndLine, resolved.originalStartLine))
            } else {
                lineNumber
            }

        val fileName = classMapping.originalName.substringAfterLast('.').substringBefore('$') + ".java"
        val newSource = if (originalLine != null) "$fileName:$originalLine" else source
        return "$prefix${classMapping.originalName}.$originalMethod($newSource)$suffix"
    }

    /** The `Caused by:`/exception-type line names a class too, and is just as unreadable. */
    private fun retraceExceptionHeader(
        line: String,
        mapping: Mapping,
    ): String {
        val match = Regex("^(\\s*(?:Caused by:\\s*)?)([\\w$.]+)(:.*|\\s*)$").find(line) ?: return line
        val (prefix, className, suffix) = match.destructured
        if (!className.contains('.')) return line
        val original = mapping.classes[className]?.originalName ?: return line
        return "$prefix$original$suffix"
    }
}
