package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.math.*
import org.junit.Assert.*
import org.junit.Test

class LatexToMathTest {
    private val parser = DefaultMathParser()
    private val calc = ExactCalculationEngine()
    private fun tokens(s: String) = s.trim().split(" ").filter { it.isNotEmpty() }
    private fun linear(latex: String): String { val c = LatexToMath.convert(tokens(latex)); assertTrue("$latex -> $c", c is LatexConversion.Success); return (c as LatexConversion.Success).linear }

    /** LaTeX -> converter -> the app's REAL parser and evaluator. */
    private fun value(latex: String): String {
        val st = (parser.parse(linear(latex)) as ParseResult.Success).statement as MathStatement.Expression
        return NumberFormatter.format((calc.evaluate(st.expr) as CalcResult.Success).value).primary
    }

    // ---- the expressions listed in the specification
    @Test fun addition_digitsAreJoinedIntoNumbers() { assertEquals("25+15", linear("2 5 + 1 5")); assertEquals("40", value("2 5 + 1 5")) }
    @Test fun multiplication() { assertEquals("12×8", linear("1 2 \\times 8")); assertEquals("96", value("1 2 \\times 8")) }
    @Test fun division() { assertEquals("15÷3", linear("1 5 \\div 3")); assertEquals("5", value("1 5 \\div 3")) }
    @Test fun subtraction() { assertEquals("3", value("7 - 4")) }
    @Test fun exponent() { assertEquals("4", value("2 ^ { 2 }")); assertEquals("8", value("2 ^ 3")); assertEquals("1024", value("2 ^ { 1 0 }")) }
    @Test fun fraction() { assertEquals("3/4", value("\\frac { 3 } { 4 }")); assertEquals("5/6", value("\\frac { 1 } { 2 } + \\frac { 1 } { 3 }")) }
    @Test fun squareRoot() { assertEquals("12", value("\\sqrt { 1 4 4 }")); assertEquals("5", value("\\sqrt { 9 + 1 6 }")); assertEquals("3", value("\\sqrt 9")) }
    @Test fun linearEquation_isAnEquationStatement() {
        val r = parser.parse(linear("2 x + 5 = 1 5")) as ParseResult.Success
        assertTrue(r.statement is MathStatement.Equation)
    }
    @Test fun trailingEquals_isAnExplicitRequest() { assertTrue((parser.parse(linear("2 5 + 1 5 =")) as ParseResult.Success).requestedExplicitly) }

    // ---- more notation the calculator can handle
    @Test fun decimals() { assertEquals("4.75", value("3 . 5 + 1 . 2 5")) }
    @Test fun parenthesesAndPrecedence() { assertEquals("20", value("( 2 + 3 ) \\times 4")); assertEquals("14", value("2 + 3 \\times 4")) }
    @Test fun squareBracketsAreGrouping() { assertEquals("20", value("[ 2 + 3 ] \\times 4")) }
    @Test fun bracedGroupsAreGrouping() { assertEquals("14", value("2 \\times { 3 + 4 }")) }
    @Test fun nthRoot() { assertEquals("3", value("\\sqrt [ 3 ] { 2 7 }")) }
    @Test fun nestedFractions() { assertEquals("2", value("\\frac { \\frac { 1 } { 2 } } { \\frac { 1 } { 4 } }")) }
    @Test fun fractionInsideASum() { assertEquals("7/2", value("3 + \\frac { 1 } { 2 }")) }
    @Test fun powerOfAGroup() { assertEquals("25", value("( 2 + 3 ) ^ { 2 }")) }
    @Test fun implicitMultiplication() { assertEquals("2x", linear("2 x")) }
    @Test fun cdotIsMultiplication() { assertEquals("6", value("2 \\cdot 3")) }
    @Test fun trigonometryAndLogs() { assertEquals("0", value("\\sin ( 0 )")); assertEquals("1", value("\\cos 0")); assertEquals("2", value("\\log ( 1 0 0 )")) }
    @Test fun piConstant() { assertEquals("3.141592654", value("\\pi")) }
    @Test fun absoluteValue() { assertEquals("2", value("| 3 - 5 |")) }
    @Test fun slashDivision() { assertEquals("4", value("8 / 2")) }
    @Test fun unaryMinus() { assertEquals("-5", value("- 5")); assertEquals("8", value("5 - - 3")) }

    // ---- structured: not a pile of regular expressions
    @Test fun fractionArgumentsMayBeSingleUnbracedTokens() { assertEquals("1/2", value("\\frac 1 2")) }
    @Test fun deeplyNestedInput_doesNotOverflowTheStack() {
        val deep = "{ ".repeat(3000) + "1" + " }".repeat(3000)
        assertTrue(LatexToMath.convert(tokens(deep)) is LatexConversion.Malformed)
    }

    // ---- notation the calculator does not support: kept and reported, never pretended
    @Test fun unsupportedNotation_isReportedWithAReadableBestEffort() {
        for ((latex, mention) in listOf("\\sum" to "\\sum", "\\int 1" to "\\int", "x _ { 1 }" to "_", "1 \\pm 2" to "\\pm", "a \\leq b" to "\\leq", "5 !" to "!", "\\lim" to "\\lim", "\\infty" to "\\infty", "A + B" to "A", "a < b" to "<")) {
            val c = LatexToMath.convert(tokens(latex))
            assertTrue("$latex should be unsupported but was $c", c is LatexConversion.Unsupported)
            assertTrue("reason should mention '$mention': ${(c as LatexConversion.Unsupported).reason}", c.reason.contains(mention))
            assertTrue(c.bestEffort.isNotBlank())
        }
    }
    @Test fun bestEffort_isReadableAndKeepsTheOriginalSymbols() {
        assertEquals("1±2", (LatexToMath.convert(tokens("1 \\pm 2")) as LatexConversion.Unsupported).bestEffort)
        assertEquals("a≤b", (LatexToMath.convert(tokens("a \\leq b")) as LatexConversion.Unsupported).bestEffort)
    }
    @Test fun malformedInput_isReported() {
        for (latex in listOf("\\frac { 1 }", "\\frac", "{ 1", "1 }", "2 ^", "\\sqrt", "\\sqrt [ 3", "| 3", "\\sin")) {
            assertTrue("$latex should be malformed", LatexToMath.convert(tokens(latex)) is LatexConversion.Malformed)
        }
    }
    @Test fun emptyInput_isMalformed() { assertTrue(LatexToMath.convert(emptyList()) is LatexConversion.Malformed) }
    @Test fun recognisedTextNeverReachesAnEvaluatorUnparsed() {
        // the converter output is only ever handed to the app's parser; text that does not parse is an error, never evaluated
        val c = LatexToMath.convert(tokens("2 5 + \\times 1")) as LatexConversion.Success
        assertTrue(parser.parse(c.linear) is ParseResult.Failure)
    }


    // ---- handwritten inverse functions: sin^{-1}, cos^{-1}, tan^{-1}
    @Test fun inverseFunctionsWrittenWithAMinusOneExponent() {
        assertEquals("asin(0.5)", linear("\\sin ^ { - 1 } ( 0 . 5 )")); assertEquals("acos(1)", linear("\\cos ^ { - 1 } 1")); assertEquals("atan(x)", linear("\\tan ^ { - 1 } ( x )"))
        assertEquals("asin(1/2)", linear("\\sin ^ { - 1 } ( 1 / 2 )"))
    }
    @Test fun inverseFunctions_evaluateInTheChosenUnit() {
        val st = { latex: String -> (parser.parse(linear(latex)) as ParseResult.Success).statement as MathStatement.Expression }
        val v = (ExactCalculationEngine(AngleMode.DEGREES).evaluate(st("\\sin ^ { - 1 } ( 1 / 2 )").expr) as CalcResult.Success).value
        assertEquals("30", NumberFormatter.format(v).primary)
        val w = (ExactCalculationEngine(AngleMode.DEGREES).evaluate(st("\\tan ^ { - 1 } 1").expr) as CalcResult.Success).value
        assertEquals("45", NumberFormatter.format(w).primary)
    }
    @Test fun ordinaryTrigIsUnchanged() { assertEquals("sin(x)", linear("\\sin ( x )")); assertEquals("cos(30)", linear("\\cos 3 0")) }
    @Test fun aSquaredFunctionIsStillNotMisreadAsInverse() { val c = LatexToMath.convert(tokens("\\sin ^ { 2 } ( x )")); assertFalse(c is LatexConversion.Success && (c as LatexConversion.Success).linear.startsWith("asin")) }
    @Test fun anIncompleteInverse_isMalformed_notACrash() { for (l in listOf("\\sin ^ { - 1", "\\sin ^ { - }", "\\sin ^ { - 1 }", "\\sin ^ { - 1 } +")) assertFalse(l, LatexToMath.convert(tokens(l)) is LatexConversion.Success) }
}
