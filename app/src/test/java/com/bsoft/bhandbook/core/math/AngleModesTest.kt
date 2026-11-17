package com.bsoft.bhandbook.core.math

import org.junit.Assert.*
import org.junit.Test

class AngleModesTest {
    private val parser = DefaultMathParser()
    private fun eval(src: String, mode: AngleMode): CalcResult {
        val st = (parser.parse(src) as ParseResult.Success).statement as MathStatement.Expression
        return ExactCalculationEngine(mode).evaluate(st.expr)
    }
    private fun value(src: String, mode: AngleMode): Value = (eval(src, mode) as CalcResult.Success).value
    private fun exact(src: String, mode: AngleMode, num: Long, den: Long = 1) { val v = value(src, mode); assertTrue("$src in $mode = $v", v is Value.Exact); assertEquals("$src in $mode", Rational.of(num, den), (v as Value.Exact).value) }
    private fun approx(src: String, mode: AngleMode, expected: Double) { assertEquals("$src in $mode", expected, value(src, mode).toDouble(), 1e-9) }
    private fun fails(src: String, mode: AngleMode) { assertTrue("$src in $mode should fail but was ${eval(src, mode)}", eval(src, mode) is CalcResult.Failure) }
    private val D = AngleMode.DEGREES; private val R = AngleMode.RADIANS; private val G = AngleMode.GRADIANS

    // ---------------------------------------------------------------- degrees: exact where the answer is rational
    @Test fun degrees_sine_exactValues() {
        exact("sin(0)", D, 0); exact("sin(30)", D, 1, 2); exact("sin(90)", D, 1); exact("sin(150)", D, 1, 2); exact("sin(180)", D, 0)
        exact("sin(210)", D, -1, 2); exact("sin(270)", D, -1); exact("sin(330)", D, -1, 2); exact("sin(360)", D, 0)
    }
    @Test fun degrees_cosine_exactValues() {
        exact("cos(0)", D, 1); exact("cos(60)", D, 1, 2); exact("cos(90)", D, 0); exact("cos(120)", D, -1, 2); exact("cos(180)", D, -1); exact("cos(270)", D, 0); exact("cos(300)", D, 1, 2)
    }
    @Test fun degrees_tangent_exactValues() { exact("tan(0)", D, 0); exact("tan(45)", D, 1); exact("tan(135)", D, -1); exact("tan(180)", D, 0); exact("tan(225)", D, 1); exact("tan(315)", D, -1) }
    @Test fun degrees_negativeAndLargeAngles_areReduced() { exact("sin(-30)", D, -1, 2); exact("sin(390)", D, 1, 2); exact("cos(-60)", D, 1, 2); exact("sin(3600)", D, 0); exact("sin(3630)", D, 1, 2); exact("tan(-45)", D, -1) }
    @Test fun degrees_anExactArgumentThatIsNotWrittenAsAnInteger_isStillExact() { exact("sin(60/2)", D, 1, 2); exact("cos(120/2)", D, 1, 2); exact("sin(15*2)", D, 1, 2) }
    @Test fun degrees_otherAngles_areNumeric() { approx("sin(45)", D, Math.sqrt(2.0) / 2); approx("cos(45)", D, Math.sqrt(2.0) / 2); approx("sin(60)", D, Math.sqrt(3.0) / 2); approx("tan(30)", D, 1 / Math.sqrt(3.0)); approx("sin(30.5)", D, Math.sin(Math.toRadians(30.5))) }
    @Test fun degrees_tangentIsUndefinedWhereCosineIsZero() { fails("tan(90)", D); fails("tan(270)", D); fails("tan(-90)", D); fails("tan(450)", D); (eval("tan(90)", D) as CalcResult.Failure).let { assertEquals(CalcError.DOMAIN, it.error) } }

    // ---------------------------------------------------------------- gradians: 400 grad = 360 degrees
    @Test fun gradians_rightAngleIs100() { exact("sin(100)", G, 1); exact("cos(100)", G, 0); exact("sin(200)", G, 0); exact("cos(200)", G, -1); exact("cos(0)", G, 1); exact("sin(300)", G, -1); exact("tan(50)", G, 1); exact("tan(150)", G, -1); fails("tan(100)", G) }
    @Test fun gradians_thirtyDegrees_isThirtyThreeAndAThirdGrad() { exact("sin(100/3)", G, 1, 2); approx("sin(50)", G, Math.sqrt(2.0) / 2) }

    // ---------------------------------------------------------------- radians: numeric, but noise is removed
    @Test fun radians_aretheDefault_andNumeric() { approx("sin(1)", R, Math.sin(1.0)); approx("cos(1)", R, Math.cos(1.0)); approx("tan(1)", R, Math.tan(1.0)); assertEquals(R, AngleMode.RADIANS) }
    @Test fun radians_floatingPointNoiseIsSnappedToZero() {
        assertEquals(0.0, value("sin(pi)", R).toDouble(), 0.0); assertEquals(0.0, value("cos(pi/2)", R).toDouble(), 0.0); assertEquals(0.0, value("sin(2*pi)", R).toDouble(), 0.0)
        assertEquals(0.0, value("tan(pi)", R).toDouble(), 0.0); assertEquals(-1.0, value("cos(pi)", R).toDouble(), 1e-12)
    }
    @Test fun radians_tangentIsUndefinedAtAHalfTurnOfPi() { fails("tan(pi/2)", R); fails("tan(3*pi/2)", R) }
    @Test fun smallButRealResultsAreNotSnapped() { assertEquals(Math.sin(1e-6), value("sin(0.000001)", R).toDouble(), 1e-20); assertTrue(value("sin(0.000001)", R).toDouble() != 0.0) }

    // ---------------------------------------------------------------- inverse functions answer in the chosen unit
    @Test fun inverse_degrees() { exact("asin(1/2)", D, 30); exact("asin(1)", D, 90); exact("asin(0)", D, 0); exact("asin(-1)", D, -90); exact("acos(0)", D, 90); exact("acos(1/2)", D, 60); exact("acos(-1)", D, 180); exact("atan(1)", D, 45); exact("atan(0)", D, 0); exact("atan(-1)", D, -45) }
    @Test fun inverse_degrees_otherValues_areNumeric() { approx("asin(0.3)", D, Math.toDegrees(Math.asin(0.3))); approx("atan(2)", D, Math.toDegrees(Math.atan(2.0))); approx("acos(0.3)", D, Math.toDegrees(Math.acos(0.3))) }
    @Test fun inverse_gradians() { exact("asin(1)", G, 100); exact("acos(0)", G, 100); exact("atan(1)", G, 50); exact("acos(-1)", G, 200) }
    @Test fun inverse_radians() { approx("asin(1)", R, Math.PI / 2); approx("acos(0)", R, Math.PI / 2); approx("atan(1)", R, Math.PI / 4); approx("acos(-1)", R, Math.PI) }
    @Test fun inverse_outsideItsDomain_isAnError() { fails("asin(2)", D); fails("acos(-1.5)", D); fails("asin(1.0001)", R); (eval("asin(2)", D) as CalcResult.Failure).let { assertEquals(CalcError.DOMAIN, it.error) } }
    @Test fun roundTrips() { exact("sin(asin(1/2))", D, 1, 2); approx("tan(atan(3))", D, 3.0); approx("cos(acos(0.25))", R, 0.25) }

    // ---------------------------------------------------------------- the same text in every mode
    @Test fun sin30_inEveryMode() { exact("sin(30)", D, 1, 2); approx("sin(30)", R, Math.sin(30.0)); approx("sin(30)", G, Math.sin(Math.toRadians(27.0))) }
    @Test fun withAngle_returnsAnEngineInThatMode() {
        val st = (parser.parse("sin(30)") as ParseResult.Success).statement as MathStatement.Expression
        val base: MathCalculationEngine = ExactCalculationEngine(); val deg = base.withAngle(D)
        assertEquals(0.5, ((deg.evaluate(st.expr)) as CalcResult.Success).value.toDouble(), 1e-12); assertNotEquals(0.5, ((base.evaluate(st.expr)) as CalcResult.Success).value.toDouble(), 1e-3)
    }

    // ---------------------------------------------------------------- parsing the inverse functions
    @Test fun parser_readsInverseFunctions_andTheirLongNames() {
        for ((src, name) in listOf("asin(0.5)" to "asin", "acos(0.5)" to "acos", "atan(1)" to "atan", "arcsin(0.5)" to "asin", "arccos(0.5)" to "acos", "arctan(1)" to "atan")) {
            val e = ((parser.parse(src) as ParseResult.Success).statement as MathStatement.Expression).expr
            assertEquals(src, name, (e as Func).name)
        }
    }
    @Test fun parser_stillReadsTheOldFunctions() { assertTrue(parser.parse("sin(x)+cos(x)") is ParseResult.Success); assertTrue(parser.parse("log(100)+ln(2)") is ParseResult.Success) }
}
