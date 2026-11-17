package com.bsoft.bhandbook.core.math

/**
 * Presentation of money-like answers: thousands separators and a currency symbol. Only plain numbers ("1234.5", "-40.00") are decorated;
 * fractions, scientific notation, words and text that is already decorated are returned unchanged. It is applied to the DISPLAYED answer only,
 * never to values that are parsed again (an equation's solutions) or to the working shown in steps.
 */
object MoneyFormat {
    private val PLAIN = Regex("^(-?)(\\d+)(\\.\\d+)?$")

    fun decorate(text: String, settings: CalcSettings): String {
        if (!settings.grouping && settings.currency.isEmpty()) return text
        val m = PLAIN.matchEntire(text) ?: return text
        val sign = m.groupValues[1]; val frac = m.groupValues[3]
        val whole = if (settings.grouping) group(m.groupValues[2]) else m.groupValues[2]
        return sign + settings.currency + whole + frac
    }

    private fun group(digits: String): String {
        val sb = StringBuilder()
        digits.reversed().forEachIndexed { i, c -> if (i > 0 && i % 3 == 0) sb.append(','); sb.append(c) }
        return sb.reverse().toString()
    }
}
