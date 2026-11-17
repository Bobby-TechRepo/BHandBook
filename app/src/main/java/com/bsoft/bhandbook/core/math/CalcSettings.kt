package com.bsoft.bhandbook.core.math

/** How results are shown: [AUTO] keeps exact fractions (1/3, with its decimal beside it); [DECIMAL] always shows decimals. */
enum class ResultStyle { AUTO, DECIMAL }

/**
 * Everything the user can change about how maths is read and shown (the "modes"): the unit of angles, whether results are fractions or decimals,
 * and an optional fixed number of decimal places (like a calculator's FIX mode; null = as many as are meaningful).
 */
data class CalcSettings(
    val angle: AngleMode = AngleMode.RADIANS,
    val style: ResultStyle = ResultStyle.AUTO,
    val places: Int? = null,
    /** Thousands separators in displayed answers: 1,234,567. */
    val grouping: Boolean = false,
    /** A short currency symbol shown before displayed answers (₦, $, GHS ...); empty for none. */
    val currency: String = "",
) {
    fun normalized() = copy(places = places?.let { if (it < 0) null else it.coerceAtMost(MAX_PLACES) }, currency = cleanCurrency(currency))

    companion object {
        const val MAX_PLACES = 9
        const val MAX_CURRENCY = 3
        /** One to three characters, no digits or spaces inside: otherwise no currency. */
        private fun cleanCurrency(raw: String): String {
            val t = raw.trim()
            return if (t.length in 1..MAX_CURRENCY && t.none { it.isDigit() || it.isWhitespace() || it.isISOControl() }) t else ""
        }
    }
}
