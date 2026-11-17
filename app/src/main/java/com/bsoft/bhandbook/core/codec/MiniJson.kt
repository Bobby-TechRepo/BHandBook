package com.bsoft.bhandbook.core.codec

/**
 * Small strict JSON reader (objects -> Map, arrays -> List, numbers -> Double, strings, booleans, null) with no dependency,
 * so it runs unchanged in JVM unit tests and on Android. Never recurses deeper than [MAX_DEPTH] and never throws anything but [ParseFailure].
 */
object MiniJson {
    const val MAX_DEPTH = 64

    class ParseFailure : RuntimeException(null, null, false, false)

    fun parse(json: String): Any? = Parser(json).parseDocument()

    
    /** Minimal strict JSON reader: objects -> Map, arrays -> List, numbers -> Double, strings, booleans, null. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? { val v = value(0); ws(); if (i != s.length) throw ParseFailure(); return v }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char { ws(); if (i >= s.length) throw ParseFailure(); return s[i] }
        private fun expect(c: Char) { if (peek() != c) throw ParseFailure(); i++ }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseFailure()
            return when (val c = peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw ParseFailure()
            }
        }

        private fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) throw ParseFailure(); i += word.length; return v }

        private fun obj(depth: Int): Map<String, Any?> {
            expect('{'); val m = LinkedHashMap<String, Any?>()
            if (peek() == '}') { i++; return m }
            while (true) {
                val k = str(); expect(':'); m[k] = value(depth + 1)
                when (peek()) { ',' -> i++; '}' -> { i++; return m }; else -> throw ParseFailure() }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            expect('['); val l = ArrayList<Any?>()
            if (peek() == ']') { i++; return l }
            while (true) {
                l += value(depth + 1)
                when (peek()) { ',' -> i++; ']' -> { i++; return l }; else -> throw ParseFailure() }
            }
        }

        private fun str(): String {
            expect('"'); val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseFailure()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseFailure()
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r')
                            't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { if (i + 4 > s.length) throw ParseFailure(); sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseFailure()); i += 4 }
                            else -> throw ParseFailure()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ParseFailure()
        }
    }

}
