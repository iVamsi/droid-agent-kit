package com.droidagentkit.perfetto

/**
 * Minimal recursive-descent JSON parser. Parses into Kotlin primitives (String, Long/Double,
 * Boolean, null, List, Map) without any third-party dependency, keeping perfetto-core dep-free.
 *
 * It is intentionally small: it parses the structured output emitted by trace_processor_shell and
 * the fixture JSON used in tests. It is not a general-purpose JSON library.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.error("trailing characters")
        return value
    }

    private class Parser(
        private val text: String,
    ) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) error("unexpected end of input")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                consume()
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> consume()
                    '}' -> {
                        consume()
                        return map
                    }
                    else -> error("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                consume()
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> consume()
                    ']' -> {
                        consume()
                        return list
                    }
                    else -> error("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (!atEnd()) {
                val c = text[index++]
                when (c) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) error("unterminated escape")
                        val esc = text[index++]
                        builder.append(
                            when (esc) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'u' -> parseUnicode()
                                else -> esc
                            },
                        )
                    }
                    else -> builder.append(c)
                }
            }
            error("unterminated string")
        }

        private fun parseUnicode(): Char {
            if (index + 4 > text.length) error("bad unicode escape")
            val hex = text.substring(index, index + 4)
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseBool(): Boolean {
            if (text.startsWith("true", index)) {
                index += 4
                return true
            }
            if (text.startsWith("false", index)) {
                index += 5
                return false
            }
            error("invalid literal")
        }

        private fun parseNull(): Any? {
            if (text.startsWith("null", index)) {
                index += 4
                return null
            }
            error("invalid literal")
        }

        private fun parseNumber(): Any {
            val start = index
            while (!atEnd()) {
                val c = text[index]
                if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    index++
                } else {
                    break
                }
            }
            val token = text.substring(start, index)
            if (token.isEmpty()) error("invalid number")
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDouble()
            } else {
                try {
                    token.toLong()
                } catch (_: NumberFormatException) {
                    token.toDouble()
                }
            }
        }

        fun skipWhitespace() {
            while (!atEnd() && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = if (atEnd()) error("unexpected end") else text[index]

        private fun consume() {
            if (atEnd()) error("unexpected end")
            index++
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (atEnd() || text[index] != c) error("expected '$c'")
            index++
        }

        fun error(message: String): Nothing = throw IllegalArgumentException("JSON parse error at $index: $message")
    }
}
