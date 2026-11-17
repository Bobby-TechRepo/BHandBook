package com.bsoft.bhandbook.core.math

import org.junit.Assert.*
import org.junit.Test

class CalcSettingsTest {
    private fun q(n: Long, d: Long = 1, decimalStyle: Boolean = false) = Value.Exact(Rational.of(n, d), decimalStyle)
    private val auto = CalcSettings()
    private val decimal = CalcSettings(style = ResultStyle.DECIMAL)
    private fun places(n: Int, style: ResultStyle = ResultStyle.AUTO) = CalcSettings(style = style, places = n)

    @Test fun defaults_areRadiansAutoAndNoFixedPlaces() { val s = CalcSettings(); assertEquals(AngleMode.RADIANS, s.angle); assertEquals(ResultStyle.AUTO, s.style); assertNull(s.places) }
    @Test fun normalised_keepsPlacesBetweenZeroAndNine() {
        assertNull(CalcSettings(places = -1).normalized().places); assertEquals(9, CalcSettings(places = 40).normalized().places); assertEquals(0, CalcSettings(places = 0).normalized().places); assertEquals(4, CalcSettings(places = 4).normalized().places)
    }

    // ---------------------------------------------------------------- Auto: exactly as before
    @Test fun auto_keepsExactFractions_withTheDecimalAsACompanion() { val f = NumberFormatter.format(q(1, 3), auto); assertEquals("1/3", f.primary); assertEquals("0.333333333", f.decimal) }
    @Test fun noSettings_isTheSameAsAuto() { assertEquals(NumberFormatter.format(q(1, 3)), NumberFormatter.format(q(1, 3), auto)); assertEquals("1/3", NumberFormatter.format(q(1, 3)).primary) }

    // ---------------------------------------------------------------- Decimal
    @Test fun decimal_showsFractionsAsDecimals() {
        assertEquals("0.5", NumberFormatter.format(q(1, 2), decimal).primary); assertEquals("-1.75", NumberFormatter.format(q(-7, 4), decimal).primary)
        val third = NumberFormatter.format(q(1, 3), decimal); assertEquals("0.333333333", third.primary); assertNull("no second line: it is already a decimal", third.decimal)
        assertEquals("5", NumberFormatter.format(q(5), decimal).primary)
    }
    @Test fun decimal_leavesApproximationsAndHugeNumbersAlone() { assertEquals(NumberFormatter.format(Value.Approx(Math.PI)).primary, NumberFormatter.format(Value.Approx(Math.PI), decimal).primary); assertTrue(NumberFormatter.format(Value.Exact(Rational.of(12345678901234567L, 3), false), decimal).primary.isNotBlank()) }

    // ---------------------------------------------------------------- fixed decimal places (like a calculator's FIX mode)
    @Test fun places_roundHalfUp_andKeepTrailingZeros() {
        assertEquals("0.33", NumberFormatter.format(q(1, 3), places(2)).primary); assertEquals("0.67", NumberFormatter.format(q(2, 3), places(2)).primary)
        assertEquals("2.50", NumberFormatter.format(q(5, 2, decimalStyle = true), places(2)).primary); assertEquals("0.01", NumberFormatter.format(q(1, 200), places(2)).primary)
    }
    @Test fun places_applyToWholeNumbersToo_likeMoney() { assertEquals("5.00", NumberFormatter.format(q(5), places(2)).primary); assertEquals("40.00", NumberFormatter.format(q(40), places(2)).primary); assertEquals("-3.000", NumberFormatter.format(q(-3), places(3)).primary) }
    @Test fun places_zero_roundsToWholeNumbers() { assertEquals("3", NumberFormatter.format(q(5, 2), places(0)).primary); assertEquals("2", NumberFormatter.format(q(12, 5), places(0)).primary); assertEquals("7", NumberFormatter.format(q(7), places(0)).primary) }
    @Test fun places_applyToApproximateValuesToo() { assertEquals("3.14", NumberFormatter.format(Value.Approx(3.14159), places(2)).primary); assertEquals("0.00", NumberFormatter.format(Value.Approx(0.0), places(2)).primary); assertEquals("-0.71", NumberFormatter.format(Value.Approx(-0.70710678), places(2)).primary) }
    @Test fun places_neverShowANegativeZero() { assertEquals("0.00", NumberFormatter.format(Value.Approx(-0.001), places(2)).primary); assertEquals("0.00", NumberFormatter.format(q(-1, 1000), places(2)).primary) }
    @Test fun places_overrideTheFractionDisplay_evenInAuto_andNeedNoCompanion() { val f = NumberFormatter.format(q(1, 3), places(4)); assertEquals("0.3333", f.primary); assertNull(f.decimal) }
    @Test fun places_leaveVeryLargeNumbersInScientificNotation() { assertTrue(NumberFormatter.format(Value.Approx(1e20), places(2)).primary.contains("×10^")) }
    @Test fun places_onSineOfDegrees() {
        val st = (DefaultMathParser().parse("sin(45)") as ParseResult.Success).statement as MathStatement.Expression
        val v = (ExactCalculationEngine(AngleMode.DEGREES).evaluate(st.expr) as CalcResult.Success).value
        assertEquals("0.71", NumberFormatter.format(v, places(2)).primary); assertEquals("0.7071067812", NumberFormatter.format(v).primary)
    }
}
