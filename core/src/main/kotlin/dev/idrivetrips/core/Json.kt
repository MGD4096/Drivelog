package dev.idrivetrips.core

/**
 * A tiny JSON reader/writer with no dependencies, so the same code runs on Android and in plain JVM unit tests
 * (Android's org.json is not available in local unit tests).
 *
 * Values are mapped to: Map<String, Any?> (object), List<Any?> (array), String, Double (every number),
 * Boolean and null. Input is limited in nesting depth because /auto values come from the local network.
 */
object Json {

    class JsonException(message: String) : Exception(message)

    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.readValue(0)
        p.skipWhitespace()
        if (p.pos != text.length) throw JsonException("Unexpected data after JSON value at ${p.pos}")
        return value
    }

    /** Parses a JSON object, or returns null when the text is not valid JSON or not an object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? =
        try { parse(text) as? Map<String, Any?> } catch (e: JsonException) { null }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    // ---- writer ------------------------------------------------------------------------------------------------

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Number -> writeNumber(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeNumber(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")                       // JSON has no NaN/Infinity
            d == Math.rint(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong())      // 1700000000000, not 1.7E12
            else -> sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---- reader ------------------------------------------------------------------------------------------------

    private class Parser(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(what: String): Nothing = throw JsonException("$what at $pos")

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) fail("Expected '$c'")
            pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nesting too deep")
            if (pos >= s.length) fail("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') fail("Expected string key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (pos >= s.length) fail("Unexpected end")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (pos >= s.length) fail("Unexpected end")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Short \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("Bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            val digitsStart = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digitsStart) fail("Bad number")
            if (pos < s.length && s[pos] == '.') {
                pos++
                val fracStart = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == fracStart) fail("Bad number")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val expStart = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == expStart) fail("Bad number")
            }
            return s.substring(start, pos).toDouble()
        }
    }
}
