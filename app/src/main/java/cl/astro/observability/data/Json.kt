package cl.astro.observability.data

/**
 * Minimal JSON reader (RFC 8259) for the resolver responses. It is written in plain Kotlin rather
 * than using Android's org.json so that the parsing is exercised by the JVM unit tests.
 *
 * Values map to: object → Map<String, Any?>, array → List<Any?>, string → String,
 * number → Double, true/false → Boolean, null → null.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing characters at ${p.pos}")
        return v
    }

    /** Follows a path of object keys, e.g. path(root, "Preferred", "Position", "RA"). */
    fun path(root: Any?, vararg keys: String): Any? {
        var cur = root
        for (k in keys) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return cur
    }

    /** Numbers may arrive as JSON numbers or as numeric strings. */
    fun number(v: Any?): Double? = when (v) {
        is Double -> v
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun string(v: Any?): String? = when (v) {
        is String -> v
        is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Boolean -> v.toString()
        else -> null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(): Any? {
            if (atEnd()) throw ParseException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Bad literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return m
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw ParseException("Expected a key at $pos")
                val k = str()
                skipWs()
                if (atEnd() || s[pos] != ':') throw ParseException("Expected ':' at $pos")
                pos++
                skipWs()
                m[k] = value()
                skipWs()
                if (atEnd()) throw ParseException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return m
                    }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return l
            }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (atEnd()) throw ParseException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return l
                    }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            pos++ // opening quote
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("Bad escape")
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
                                if (pos + 4 > s.length) throw ParseException("Bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' ||
                    s[pos] == '+' || s[pos] == '-')
            ) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw ParseException("Bad number at $start")
        }
    }
}
