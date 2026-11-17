package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.core.math.NumberFormatter
import com.bsoft.bhandbook.core.math.Printer
import com.bsoft.bhandbook.core.math.Rational
import com.bsoft.bhandbook.core.math.Value

/**
 * Column arithmetic: the first row is the starting value and every following row is applied to the running total, in order
 * (no operator = add). Like a calculator tape, so `2 / +3 / ×4` is (2+3)×4 = 20. Exact rational arithmetic throughout.
 */
object ColumnTotal {
    /** One recognised row: its LaTeX tokens (CoMER) and/or its plain text (any engine). */
    class RowReading(val tokens: List<String>?, val text: String?)

    sealed interface Outcome
    data class Result(val total: String, val linear: String, val display: String) : Outcome
    data class Problem(val row: Int, val message: String) : Outcome

    private val OPS = mapOf("+" to '+', "-" to '-', "\\times" to '×', "×" to '×', "\\cdot" to '×', "\\div" to '÷', "÷" to '÷')

    fun compute(rows: List<RowReading>, calc: CalcSettings = CalcSettings()): Outcome {
        if (rows.isEmpty()) return Problem(0, "there are no rows")
        var acc = Rational.ZERO; var linear = ""; var display = ""; var decimal = false
        for ((i, r) in rows.withIndex()) {
            val tokens = r.tokens ?: tokenize(r.text ?: "")
            if (tokens.isEmpty()) return Problem(i, "row ${i + 1} is empty")
            var op: Char? = null; var body = tokens
            OPS[tokens[0]]?.let { op = it; body = tokens.drop(1) }
            val n = parseNumber(body) ?: return Problem(i, "row ${i + 1} is not a number")
            if (body.contains(".")) decimal = true
            val symbol = op ?: '+'
            if (i == 0) {
                if (symbol == '×' || symbol == '÷') return Problem(0, "a column cannot start with $symbol")
                acc = if (symbol == '-') -n else n
                linear = (if (symbol == '-') "-" else "") + Printer.number(n); display = linear
                continue
            }
            val shown = Printer.number(n)
            when (symbol) {
                '+' -> { acc += n; linear += "+$shown"; display += " + $shown" }
                '-' -> { acc -= n; linear += "-$shown"; display += " - $shown" }
                '×' -> { acc *= n; linear = "($linear)×$shown"; display += " × $shown" }
                '÷' -> { if (n.isZero) return Problem(i, "row ${i + 1} divides by zero"); acc /= n; linear = "($linear)÷$shown"; display += " ÷ $shown" }
            }
        }
        return Result(NumberFormatter.format(Value.Exact(acc, decimal), calc).primary, linear, display)
    }

    /** Digits with at most one decimal point and optional thousands commas (1,500.50); null for anything else. */
    private fun parseNumber(t: List<String>): Rational? {
        if (t.isEmpty() || t.any { it.length != 1 || !(it[0].isDigit() || it == "." || it == ",") }) return null
        val text = t.joinToString("")
        if (text.count { it == '.' } > 1) return null
        val intPart = text.substringBefore('.'); val frac = if ('.' in text) text.substringAfter('.') else ""
        if (',' in frac) return null
        if (',' in intPart) {
            val groups = intPart.split(',')
            if (groups.first().isEmpty() || groups.first().length > 3 || groups.drop(1).any { it.length != 3 }) return null
        }
        val digits = intPart.replace(",", "")
        if (digits.isEmpty() && frac.isEmpty()) return null
        return Rational.parse(if ('.' in text) "${digits.ifEmpty { "0" }}.${frac.ifEmpty { "0" }}" else digits)
    }

    /** Plain text from a non-LaTeX recogniser, split into the same token form. */
    private fun tokenize(s: String): List<String> = s.filter { !it.isWhitespace() }.map { it.toString() }
}
