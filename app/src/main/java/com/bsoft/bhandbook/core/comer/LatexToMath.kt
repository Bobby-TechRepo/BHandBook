package com.bsoft.bhandbook.core.comer

sealed interface LatexConversion {
    /** [linear] is the notation the app's own parser accepts, e.g. "((3)/(4))", "√(144)", "x^(2)". */
    data class Success(val linear: String) : LatexConversion
    /** Valid LaTeX the calculator cannot compute. [bestEffort] is a readable rendering to show the user instead. */
    data class Unsupported(val reason: String, val bestEffort: String) : LatexConversion
    data class Malformed(val reason: String) : LatexConversion
}

/**
 * Structured (recursive-descent) conversion of CoMER's LaTeX token stream into the app's linear math notation.
 * The result is only ever handed to the existing parser; this class performs no arithmetic and never evaluates anything.
 */
object LatexToMath {
    private const val MAX_DEPTH = 64

    private class Fail(val reason: String) : RuntimeException(null, null, false, false)

    private val glyph = mapOf(
        "\\times" to "×", "\\div" to "÷", "\\cdot" to "×", "\\pm" to "±", "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\infty" to "∞",
        "\\pi" to "π", "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\sigma" to "σ",
        "\\phi" to "φ", "\\Delta" to "Δ", "\\Pi" to "Π", "\\sum" to "∑", "\\int" to "∫", "\\lim" to "lim", "\\in" to "∈", "\\rightarrow" to "→",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\prime" to "′", "\\exists" to "∃", "\\forall" to "∀", "\\{" to "{", "\\}" to "}",
    )
    private val functions = setOf("\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tan", "\\log" to "log").toMap()

    fun convert(tokens: List<String>): LatexConversion {
        if (tokens.isEmpty()) return LatexConversion.Malformed("nothing was recognised")
        return try {
            val c = Converter(tokens)
            val out = c.sequence(emptySet(), 0)
            if (c.pos < tokens.size) throw Fail("unmatched '${tokens[c.pos]}'")
            if (c.unsupported.isNotEmpty()) LatexConversion.Unsupported("unsupported notation: ${c.unsupported.distinct().joinToString(", ")}", out)
            else LatexConversion.Success(out)
        } catch (f: Fail) { LatexConversion.Malformed(f.reason) }
    }

    /** A human-readable rendering of ANY token sequence (used to show text the calculator cannot process). Never throws. */
    fun readable(tokens: List<String>): String = tokens.joinToString("") { tok ->
        when (tok) { "{" -> "("; "}" -> ")"; "\\frac" -> "frac"; "\\sqrt" -> "√"; else -> glyph[tok] ?: tok }
    }

    private class Converter(val t: List<String>) {
        var pos = 0
        val unsupported = ArrayList<String>()

        private fun peek() = t.getOrNull(pos)
        private fun next(): String = t.getOrNull(pos++) ?: throw Fail("expression ends unexpectedly")
        private fun flag(token: String) { unsupported += token }

        /** Reads tokens until one of [stop] (not consumed) or the end. */
        fun sequence(stop: Set<String>, depth: Int): String {
            if (depth > MAX_DEPTH) throw Fail("expression is nested too deeply")
            val sb = StringBuilder()
            while (pos < t.size) {
                val tok = t[pos]
                if (tok in stop) break
                pos++
                when {
                    tok.length == 1 && (tok[0].isDigit() || tok == "." || tok in "+-=/" ) -> sb.append(tok)
                    tok.length == 1 && tok[0] in 'a'..'z' -> sb.append(tok)
                    tok.length == 1 && tok[0] in 'A'..'Z' -> { flag(tok); sb.append(tok) }
                    tok == "(" || tok == "[" || tok == ")" || tok == "]" -> sb.append(if (tok == "(" || tok == "[") "(" else ")")
                    tok == "\\{" -> sb.append("(")
                    tok == "\\}" -> sb.append(")")
                    tok == "\\times" || tok == "\\cdot" -> sb.append("×")
                    tok == "\\div" -> sb.append("÷")
                    tok == "\\pi" -> sb.append("π")
                    tok == "{" -> { val inner = sequence(setOf("}"), depth + 1); expectClose("}"); sb.append("(").append(inner).append(")") }
                    tok == "}" -> throw Fail("unmatched '}'")
                    tok == "^" -> sb.append("^(").append(argument(depth + 1)).append(")")
                    tok == "_" -> { flag("_"); sb.append("_").append(argument(depth + 1)) }
                    tok == "\\frac" -> { val a = argument(depth + 1); val b = argument(depth + 1); sb.append("((").append(a).append(")/(").append(b).append("))") }
                    tok == "\\sqrt" -> sb.append(sqrt(depth + 1))
                    tok in functions -> sb.append(function(functions.getValue(tok), depth + 1))
                    tok == "|" -> { val inner = sequence(setOf("|"), depth + 1); expectClose("|"); sb.append("|").append(inner).append("|") }
                    else -> { flag(tok); sb.append(glyph[tok] ?: tok) }
                }
            }
            return sb.toString()
        }

        private fun expectClose(c: String) { if (peek() != c) throw Fail("missing closing '$c'"); pos++ }

        /** A braced group's content, or a single token (LaTeX semantics: the argument of ^, \frac, \sqrt, _ ). */
        private fun argument(depth: Int): String {
            if (depth > MAX_DEPTH) throw Fail("expression is nested too deeply")
            val tok = peek() ?: throw Fail("missing argument")
            if (tok == "{") { pos++; val inner = sequence(setOf("}"), depth + 1); expectClose("}"); return inner }
            if (tok == "}" ) throw Fail("missing argument")
            if (tok == "\\frac") { pos++; val a = argument(depth + 1); val b = argument(depth + 1); return "(($a)/($b))" }
            if (tok == "\\sqrt") { pos++; return sqrt(depth + 1) }
            pos++
            return when {
                tok.length == 1 && (tok[0].isDigit() || tok[0] in 'a'..'z' || tok in "+-.") -> tok
                tok == "\\pi" -> "π"
                else -> { flag(tok); glyph[tok] ?: tok }
            }
        }

        private fun sqrt(depth: Int): String {
            if (peek() == "[") {
                pos++
                val index = sequence(setOf("]"), depth + 1)
                expectClose("]")
                return "root($index,${argument(depth + 1)})"
            }
            return "√(${argument(depth + 1)})"
        }

        /** True if the next tokens are exactly [expected] (nothing is consumed). */
        private fun matches(vararg expected: String) = expected.indices.all { t.getOrNull(pos + it) == expected[it] }

        private fun function(base: String, depth: Int): String {
            // sin^{-1}(x), cos^{-1}(x), tan^{-1}(x) are the inverse functions (asin, acos, atan)
            var name = base
            if (base in setOf("sin", "cos", "tan") && matches("^", "{", "-", "1", "}")) { pos += 5; name = "a$base" }
            val tok = peek() ?: throw Fail("\\$base needs an argument")
            if (tok == "(") { pos++; val inner = sequence(setOf(")"), depth + 1); expectClose(")"); return "$name($inner)" }
            if (tok.length == 1 && (tok[0].isDigit() || tok == ".")) {
                val sb = StringBuilder()
                while (peek()?.let { it.length == 1 && (it[0].isDigit() || it == ".") } == true) sb.append(next())
                return "$name($sb)"
            }
            if (tok.length == 1 && tok[0] in 'a'..'z') return "$name(${next()})"
            if (tok == "\\pi") { pos++; return "$name(π)" }
            throw Fail("\\$base needs an argument")
        }
    }
}
