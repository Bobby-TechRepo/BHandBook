package com.bsoft.bhandbook.core.math

import org.junit.Assert.*
import org.junit.Test

class MathParserTest {
    private val parser = DefaultMathParser()

    /** Fully-parenthesised canonical form of the parsed tree, so precedence is visible. */
    private fun tree(src: String): String {
        val r = parser.parse(src)
        assertTrue("expected success for '$src' but got $r", r is ParseResult.Success)
        return when (val s = (r as ParseResult.Success).statement) {
            is MathStatement.Expression -> s.expr.debug()
            is MathStatement.Equation -> s.lhs.debug() + "=" + s.rhs.debug()
        }
    }
    private fun failure(src: String): ParseError {
        val r = parser.parse(src)
        assertTrue("expected failure for '$src' but got $r", r is ParseResult.Failure)
        return (r as ParseResult.Failure).error
    }

    @Test fun multiplicationBindsTighterThanAddition() {
        assertEquals("(25+(15*2))", tree("25 + 15 × 2"))
        assertEquals("((25*15)+2)", tree("25 × 15 + 2"))
    }
    @Test fun parenthesesOverridePrecedence() { assertEquals("((25+15)*2)", tree("(25+15)×2")) }
    @Test fun subtractionAndDivision_areLeftAssociative() {
        assertEquals("((10-2)-3)", tree("10-2-3"))
        assertEquals("((100/10)/5)", tree("100÷10÷5"))
        assertEquals("((8/4)*2)", tree("8/4*2"))
    }
    @Test fun power_isRightAssociative_andTighterThanMultiply() {
        assertEquals("(2^(3^2))", tree("2^3^2"))
        assertEquals("(2*(3^2))", tree("2*3^2"))
    }
    @Test fun unaryMinus_bindsLooserThanPower() {
        assertEquals("(-(2^2))", tree("-2^2"))
        assertEquals("((-2)^2)", tree("(-2)^2"))
        assertEquals("(3*(-2))", tree("3×-2"))
        assertEquals("(5-(-3))", tree("5--3"))
    }
    @Test fun unaryMinusAfterBinaryOperator() { assertEquals("(2+(-5))", tree("2+-5")) }
    @Test fun decimals_andLeadingDot() {
        assertEquals("(0.5+2.25)", tree("0.5+2.25"))
        assertEquals("(0.5+1)", tree(".5+1"))
    }
    @Test fun implicitMultiplication() {
        assertEquals("(2*x)", tree("2x"))
        assertEquals("(2*(x+1))", tree("2(x+1)"))
        assertEquals("(2*(x^2))", tree("2x^2"))
        assertEquals("((x+1)*(x-1))", tree("(x+1)(x-1)"))
    }
    @Test fun squareRoot_andFractionNotation() {
        assertEquals("sqrt(16)", tree("√16"))
        assertEquals("sqrt((9+16))", tree("√(9+16)"))
        assertEquals("((1+2)/(3+4))", tree("(1+2)/(3+4)"))
        assertEquals("root(3,27)", tree("root(3,27)"))
    }
    @Test fun functions_andConstants() {
        assertEquals("sin(0)", tree("sin(0)"))
        assertEquals("ln(10)", tree("ln(10)"))
        assertEquals("(2*pi)", tree("2π"))
    }
    @Test fun absoluteValueBars() { assertEquals("abs((3-5))", tree("|3-5|")) }
    @Test fun percent_isPostfixDivisionBy100() { assertEquals("(50/100)", tree("50%")) }
    @Test fun equation_isParsedIntoSides() { assertEquals("((2*x)+5)=15", tree("2x+5=15")) }
    @Test fun trailingEquals_isExplicitEvaluationRequest() {
        val r = parser.parse("25+15=") as ParseResult.Success
        assertTrue(r.statement is MathStatement.Expression)
        assertTrue(r.requestedExplicitly)
    }
    @Test fun unicodeMinusAndOperators_areAccepted() { assertEquals("(5-3)", tree("5 − 3")); assertEquals("(2*3)", tree("2·3")) }

    // ---- errors ----
    @Test fun incompleteExpression_reportsPosition() {
        val e = failure("25+"); assertEquals(3, e.position); assertTrue(e.message.isNotBlank())
    }
    @Test fun emptyInput_fails() { failure("") ; failure("   ") }
    @Test fun unbalancedParentheses_fail() { failure("(1+2"); failure("1+2)"); failure("()") }
    @Test fun danglingOperators_fail() { failure("*2"); failure("2*"); failure("2+*3") }
    @Test fun malformedNumbers_fail() { failure("1.2.3"); failure("2..3") }
    @Test fun adjacentNumbers_fail() { failure("2 3") }
    @Test fun unknownCharacters_fail() { failure("2 $ 3"); failure("2 # 3") }
    @Test fun multipleEquals_fail() { failure("1=2=3") }
    @Test fun emptyEquationSide_fails() { failure("=5") }
    @Test fun veryDeepNesting_isRejectedByDepthGuard_notStackOverflow() {
        val deep = "(".repeat(500) + "1" + ")".repeat(500)      // 1001 chars: under the length limit
        assertTrue(failure(deep).message.contains("nested too deeply"))
    }
    @Test fun veryLongInput_isRejectedByLengthGuard() {
        assertTrue(failure("1+".repeat(2000) + "1").message.contains("too long"))
    }
    @Test fun moderateNesting_stillParses() { tree("(".repeat(50) + "1" + ")".repeat(50)) }
}
