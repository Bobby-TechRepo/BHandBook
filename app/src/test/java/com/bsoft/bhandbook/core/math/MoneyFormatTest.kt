package com.bsoft.bhandbook.core.math

import org.junit.Assert.*
import org.junit.Test

class MoneyFormatTest {
    private val group = CalcSettings(grouping = true)
    private val naira = CalcSettings(currency = "₦")
    private val both = CalcSettings(grouping = true, currency = "₦")
    private fun d(text: String, s: CalcSettings) = MoneyFormat.decorate(text, s)

    @Test fun nothingChanges_whenNothingIsTurnedOn() { for (t in listOf("1234567", "-1234.5", "5/2", "undefined")) assertEquals(t, d(t, CalcSettings())) }

    // ---------------------------------------------------------------- thousands separators
    @Test fun groupsTheWholePart_inThrees() { assertEquals("1,234,567", d("1234567", group)); assertEquals("1,234", d("1234", group)); assertEquals("123", d("123", group)); assertEquals("12,345", d("12345", group)); assertEquals("1,000,000,000", d("1000000000", group)) }
    @Test fun theFractionalPartIsNeverGrouped() { assertEquals("1,234.5678", d("1234.5678", group)); assertEquals("1,234,567.50", d("1234567.50", group)); assertEquals("0.12345", d("0.12345", group)) }
    @Test fun negativeNumbers() { assertEquals("-1,234.5", d("-1234.5", group)); assertEquals("-12", d("-12", group)); assertEquals("-1,000", d("-1000", group)) }
    @Test fun zeroAndSmallNumbers() { assertEquals("0", d("0", group)); assertEquals("0.00", d("0.00", group)); assertEquals("999", d("999", group)) }

    // ---------------------------------------------------------------- currency
    @Test fun theCurrencyGoesBeforeTheNumber() { assertEquals("₦1234.5", d("1234.5", naira)); assertEquals("₦40.00", d("40.00", naira)); assertEquals("$5", d("5", CalcSettings(currency = "$"))) }
    @Test fun aNegativeAmountKeepsItsSignInFront() { assertEquals("-₦1234.5", d("-1234.5", naira)); assertEquals("-₦1,234.50", d("-1234.50", both)) }
    @Test fun groupingAndCurrencyTogether() { assertEquals("₦1,234,500.00", d("1234500.00", both)); assertEquals("₦12", d("12", both)) }

    // ---------------------------------------------------------------- only plain numbers are decorated
    @Test fun fractions_scientificNotation_andWordsAreLeftAlone() {
        for (t in listOf("5/2", "-10/3", "1.5×10^20", "6.02×10^23", "undefined", "∞", "-∞", "x = 5", "")) assertEquals("'$t'", t, d(t, both))
    }
    @Test fun alreadyDecoratedTextIsNotDecoratedTwice() { assertEquals("₦1,234.50", d("₦1,234.50", both)); assertEquals("1,234", d("1,234", group)) }

    // ---------------------------------------------------------------- settings
    @Test fun normalised_keepsShortCurrencySymbols_andDropsAnythingElse() {
        assertEquals("₦", CalcSettings(currency = " ₦ ").normalized().currency); assertEquals("GHS", CalcSettings(currency = "GHS").normalized().currency)
        assertEquals("", CalcSettings(currency = "NGN123").normalized().currency); assertEquals("", CalcSettings(currency = "1").normalized().currency); assertEquals("", CalcSettings(currency = "  ").normalized().currency)
        assertEquals("", CalcSettings(currency = "a\nb").normalized().currency)
    }
    @Test fun defaults_areOff() { val s = CalcSettings(); assertFalse(s.grouping); assertEquals("", s.currency) }
}
