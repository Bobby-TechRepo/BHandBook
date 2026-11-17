package com.bsoft.bhandbook.core.math

import org.junit.Assert.*
import org.junit.Test

class MathCalculationTest {
    private val parser = DefaultMathParser()
    private val engine = ExactCalculationEngine()

    private fun calc(src: String): CalcResult {
        val s = (parser.parse(src) as ParseResult.Success).statement as MathStatement.Expression
        return engine.evaluate(s.expr)
    }
    private fun show(src: String): String = when (val r = calc(src)) {
        is CalcResult.Success -> NumberFormatter.format(r.value).primary
        is CalcResult.Failure -> "ERR:${r.error}"
    }

    @Test fun addition() { assertEquals("40", show("25+15")) }
    @Test fun subtraction_negativeResult() { assertEquals("-10", show("5-15")) }
    @Test fun multiplication() { assertEquals("375", show("25×15")) }
    @Test fun division_exactFraction() { assertEquals("7/2", show("7÷2")) }
    @Test fun division_integerResult() { assertEquals("5", show("10÷2")) }
    @Test fun precedence() { assertEquals("55", show("25 + 15 × 2")) }
    @Test fun parentheses() { assertEquals("80", show("(25 + 15) × 2")) }
    @Test fun decimalArithmetic_hasNoBinaryFloatError() {
        assertEquals("0.3", show("0.1+0.2"))      // decimal in -> decimal out, exact underneath
        assertEquals("0.5", show("0.25+0.25"))
        assertEquals("7/2", show("7÷2"))           // integers in -> exact fraction out
        assertEquals(true, (calc("0.1+0.2") as CalcResult.Success).value is Value.Exact)
    }
    @Test fun fractions_addExactly() { assertEquals("5/6", show("1/2+1/3")) ; assertEquals("1/2", show("1/3+1/6")) }
    @Test fun powers() { assertEquals("1024", show("2^10")); assertEquals("1/8", show("2^-3")); assertEquals("-4", show("-2^2")); assertEquals("4", show("(-2)^2")) }
    @Test fun power_rightAssociative() { assertEquals("512", show("2^3^2")) }
    @Test fun roots_exactWhenPerfect() { assertEquals("4", show("√16")); assertEquals("2/3", show("√(4/9)")); assertEquals("3", show("root(3,27)")) }
    @Test fun roots_approximateOtherwise() { assertEquals("1.414213562", show("√2")) }
    @Test fun divisionByZero_isReported() { assertEquals("ERR:DIVISION_BY_ZERO", show("5÷0")); assertEquals("ERR:DIVISION_BY_ZERO", show("1/(2-2)")) }
    @Test fun negativeSquareRoot_isDomainError() { assertEquals("ERR:DOMAIN", show("√(-4)")) }
    @Test fun undefinedVariable_isReported() { assertEquals("ERR:UNDEFINED_VARIABLE", show("2x+1")) }
    @Test fun variablesCanBeSupplied() {
        val s = (parser.parse("2x+1") as ParseResult.Success).statement as MathStatement.Expression
        val r = engine.evaluate(s.expr, mapOf("x" to Value.Exact(Rational.of(4)))) as CalcResult.Success
        assertEquals("9", NumberFormatter.format(r.value).primary)
    }
    @Test fun percent() { assertEquals("1/2", show("50%")); assertEquals("20", show("200×10%")) }
    @Test fun functions() {
        assertEquals("0", show("sin(0)")); assertEquals("1", show("cos(0)"))
        assertEquals("2", show("log(100)")); assertEquals("1", show("ln(2.718281828459045)"))
        assertEquals("3", show("|3-6|")); assertEquals("3.141592654", show("π"))
    }
    @Test fun hugePower_isRejectedNotHung() { assertEquals("ERR:OVERFLOW", show("9^9^9^9")) }
    @Test fun largeExactIntegers_stayExact_butDisplayScientific() {
        val v = (calc("2^100") as CalcResult.Success).value as Value.Exact
        assertEquals("1267650600228229401496703205376", v.value.num.toString())
        assertEquals("1.2676506×10^30", show("2^100"))
    }
}

class NumberFormatterTest {
    private fun ex(n: Long, d: Long = 1) = Value.Exact(Rational.of(n, d))
    private fun ap(x: Double) = Value.Approx(x)

    @Test fun integers() { assertEquals("40", NumberFormatter.format(ex(40)).primary); assertEquals("-7", NumberFormatter.format(ex(-7)).primary) }
    @Test fun fractions_showDecimalCompanion() {
        val f = NumberFormatter.format(ex(1, 2)); assertEquals("1/2", f.primary); assertEquals("0.5", f.decimal)
        val g = NumberFormatter.format(ex(1, 3)); assertEquals("1/3", g.primary); assertEquals("0.333333333", g.decimal)
        assertEquals("-7/4", NumberFormatter.format(ex(-7, 4)).primary)
    }
    @Test fun approx_trimsNoise() {
        assertEquals("0.3", NumberFormatter.format(ap(0.1 + 0.2)).primary)
        assertEquals("1.414213562", NumberFormatter.format(ap(Math.sqrt(2.0))).primary)
        assertEquals("2.5", NumberFormatter.format(ap(2.5)).primary)
        assertEquals("3", NumberFormatter.format(ap(3.0000000000001)).primary)
        assertEquals("0", NumberFormatter.format(ap(-0.0)).primary)
    }
    @Test fun scientific_forLargeAndTiny() {
        assertEquals("1.5×10^20", NumberFormatter.format(ap(1.5e20)).primary)
        assertEquals("1.23×10^-6", NumberFormatter.format(ap(0.00000123)).primary)
        assertEquals("-2×10^15", NumberFormatter.format(ap(-2e15)).primary)
    }
    @Test fun hugeExactIntegers_useScientificPrimary_butValueUntouched() {
        val v = Value.Exact(Rational.of(10).pow(25) * Rational.of(3))
        assertEquals("3×10^25", NumberFormatter.format(v).primary)
        assertEquals(Rational.of(10).pow(25) * Rational.of(3), (v as Value.Exact).value)
    }
    @Test fun nonFinite() { assertEquals("undefined", NumberFormatter.format(ap(Double.NaN)).primary); assertEquals("∞", NumberFormatter.format(ap(Double.POSITIVE_INFINITY)).primary) }
}
