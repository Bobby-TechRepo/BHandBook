package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import org.junit.Assert.*
import org.junit.Test

class SolverTest {
    private val parser = DefaultMathParser()
    private val calc = ExactCalculationEngine()
    private val solver: MathSolutionEngine = DefaultSolutionEngine()

    private fun stmt(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun solve(src: String) = solver.solve(stmt(src))
    private fun solved(src: String): SolveOutcome.Solved {
        val o = solve(src); assertTrue("expected Solved for '$src' but was $o", o is SolveOutcome.Solved); return o as SolveOutcome.Solved
    }
    private fun results(src: String) = solved(src).steps.map { it.resulting }

    private fun value(src: String): Double {
        val e = (stmt(src) as MathStatement.Expression).expr
        return (calc.evaluate(e) as CalcResult.Success).value.toDouble()
    }

    // ------------------------------------------------------------ arithmetic
    @Test fun precedence_example_fromSpec() {
        val s = solved("25 + 15 × 2")
        assertEquals(listOf("25 + 30", "55"), s.steps.map { it.resulting })
        assertEquals("25 + 15 × 2", s.steps.first().expression)
        assertEquals("55", s.finalExpression)
    }
    @Test fun stepsAreNumbered_fromOne_andChained() {
        val s = solved("2 + 3 × 4 - 5").steps
        assertEquals((1..s.size).toList(), s.map { it.number })
        for (i in 0 until s.size - 1) assertEquals(s[i].resulting, s[i + 1].expression)
        assertTrue(s.all { it.explanation.isNotBlank() })
    }
    @Test fun parentheses_areEvaluatedFirst_andUnwrapped() {
        assertEquals(listOf("40 × 2", "80"), results("(25 + 15) × 2"))
    }
    @Test fun nestedParentheses_innermostFirst() {
        assertEquals(listOf("2 × (2 + 12)", "2 × 14", "28"), results("2 × (2 + 3 × 4)"))
    }
    @Test fun leftToRight_forSameLevelOperators() {
        assertEquals(listOf("8 - 3", "5"), results("10 - 2 - 3"))
        assertEquals(listOf("2 × 2", "4"), results("8 / 4 × 2"))
    }
    @Test fun powersAndRoots_beforeMultiplication() {
        assertEquals(listOf("2 × 9", "18"), results("2 × 3^2"))
        assertEquals(listOf("4 + 2", "6"), results("√16 + 2"))
    }
    @Test fun negativeNumbers() {
        assertEquals(listOf("-6"), results("3 × -2"))
        assertEquals(listOf("8"), results("5 - -3"))
        assertEquals(listOf("-4"), results("-2^2"))
    }
    @Test fun fractions_stayExact() {
        val s = solved("1/2 + 1/3")
        assertEquals(listOf("5/6"), s.steps.map { it.resulting })
        assertEquals("1/2 + 1/3", s.steps.first().expression)
        assertEquals(listOf("3/2"), results("6 / 4"))
        assertEquals("7/2", solved("7 / 2").finalExpression)
    }
    @Test fun decimals_stayDecimal() { assertEquals("0.3", solved("0.1 + 0.2").finalExpression) }
    @Test fun alreadySimple_hasNoSteps() { assertEquals(emptyList<SolutionStep>(), solved("5").steps); assertEquals("5", solved("5").finalExpression) }
    @Test fun irrational_endsWithNumericApproximation() {
        val s = solved("√2 + 1")
        assertEquals("2.414213562", s.finalExpression)
        assertTrue(s.steps.last().explanation.contains("approx", ignoreCase = true))
    }
    @Test fun divisionByZero_isInvalid_notAStep() { assertTrue(solve("5 / (3 - 3)") is SolveOutcome.Invalid) }
    @Test fun expressionWithVariables_isUnsupportedWithoutEquals() { assertTrue(solve("2x + 1") is SolveOutcome.Unsupported) }

    @Test fun property_everyArithmeticStepPreservesTheValue() {
        val inputs = listOf("25+15×2", "(25+15)×2", "2×(2+3×4)", "10-2-3", "8/4×2", "2×3^2", "√16+2", "1/2+1/3", "(1+2)/(3+4)",
            "2^3^2", "-2^2+10", "(-3)^2-4×(2-5)", "100/(2+3)^2", "0.5×4+1.25", "root(3,27)+2×3", "|3-8|×2", "7-(2-(3-1))")
        for (src in inputs) {
            val s = solved(src)
            val original = value(src)
            for (step in s.steps) {
                if (step.explanation.contains("approx", true)) continue
                assertEquals("step '${step.expression}' -> '${step.resulting}' of $src", original, value(step.expression), 1e-9)
                assertEquals("step '${step.expression}' -> '${step.resulting}' of $src", original, value(step.resulting), 1e-9)
            }
            assertEquals(original, value(s.finalExpression.replace("×10^", "e")), 1e-6 * maxOf(1.0, Math.abs(original)))
        }
    }

    // --------------------------------------------------------------- linear
    @Test fun linear_example_fromSpec() {
        val s = solved("2x + 5 = 15")
        assertEquals(listOf("2x = 15 - 5", "2x = 10", "x = 10 / 2", "x = 5"), s.steps.map { it.resulting })
        assertEquals("2x + 5 = 15", s.steps.first().expression)
        assertEquals(listOf("5"), s.solutions); assertEquals("x = 5", s.finalExpression)
    }
    @Test fun linear_unitCoefficient_skipsDivision() { assertEquals(listOf("x = 10 - 3", "x = 7"), results("x + 3 = 10")) }
    @Test fun linear_noConstant_goesStraightToDivision() { assertEquals(listOf("x = 12 / 3", "x = 4"), results("3x = 12")) }
    @Test fun linear_negativeCoefficient() { assertEquals(listOf("-2x = 10 - 4", "-2x = 6", "x = 6 / (-2)", "x = -3"), results("-2x + 4 = 10")) }
    @Test fun linear_variableOnBothSides() { assertEquals(listOf("5x - 2x = 9 + 3", "3x = 12", "x = 12 / 3", "x = 4"), results("5x - 3 = 2x + 9")) }
    @Test fun linear_expandsBrackets_first() { assertEquals(listOf("2x + 6 = 14", "2x = 14 - 6", "2x = 8", "x = 8 / 2", "x = 4"), results("2(x + 3) = 14")) }
    @Test fun linear_withFractionCoefficient() { assertEquals(listOf("x/2 = 4 - 1", "x/2 = 3", "x = 3 / (1/2)", "x = 6"), results("x/2 + 1 = 4")) }
    @Test fun linear_fractionalSolution_isNotRepeatedTwice() {
        val s = solved("2x = 5"); assertEquals(listOf("x = 5 / 2"), s.steps.map { it.resulting }); assertEquals(listOf("5/2"), s.solutions)
    }
    @Test fun linear_reducibleFraction_getsSimplifiedStep() { assertEquals(listOf("x = 6 / 4", "x = 3/2"), results("4x = 6")) }
    @Test fun linear_variableOnRightOnly_swapsSides() {
        val s = solved("15 = 2x + 5")
        assertEquals("2x + 5 = 15", s.steps.first().resulting)
        assertTrue(s.steps.first().explanation.contains("swap", ignoreCase = true))
        assertEquals("x = 5", s.steps.last().resulting)
    }
    @Test fun linear_alreadySolved() { val s = solved("x = 5"); assertTrue(s.steps.isEmpty()); assertEquals("x = 5", s.finalExpression) }
    @Test fun linear_otherVariableName() { assertEquals("y = 3", solved("y + 1 = 4").finalExpression) }
    @Test fun linear_noSolution() { assertTrue(solve("x + 1 = x + 2") is SolveOutcome.NoSolution) }
    @Test fun linear_identity() { assertTrue(solve("2x + 2 = 2(x + 1)") is SolveOutcome.Identity) }
    @Test fun numericEquation_isTruthCheck() { assertTrue(solve("3 = 3") is SolveOutcome.Identity); assertTrue(solve("3 = 5") is SolveOutcome.NoSolution) }

    @Test fun property_finalAnswerSatisfiesEveryIntermediateEquation() {
        val cases = mapOf("2x+5=15" to 5.0, "x+3=10" to 7.0, "3x=12" to 4.0, "-2x+4=10" to -3.0, "5x-3=2x+9" to 4.0,
            "2(x+3)=14" to 4.0, "x/2+1=4" to 6.0, "2x=5" to 2.5, "4x=6" to 1.5, "15=2x+5" to 5.0, "0.5x+0.25=1.25" to 2.0, "3(x-1)=2(x+4)" to 11.0)
        for ((src, ans) in cases) {
            val s = solved(src)
            assertEquals(src, ans, s.solutions.single().let { sol -> value(sol) }, 1e-9)
            for (text in s.steps.flatMap { listOf(it.expression, it.resulting) }) {
                val eq = stmt(text) as MathStatement.Equation
                val vars = mapOf("x" to Value.Approx(ans), "y" to Value.Approx(ans))
                val l = (calc.evaluate(eq.lhs, vars) as CalcResult.Success).value.toDouble()
                val r = (calc.evaluate(eq.rhs, vars) as CalcResult.Success).value.toDouble()
                assertEquals("'$text' (from $src) is not satisfied by x=$ans", l, r, 1e-9)
            }
        }
    }

    // ------------------------------------------------------------- quadratic
    @Test fun quadratic_twoRationalRoots() {
        val s = solved("x^2 - 5x + 6 = 0")
        assertEquals(listOf("2", "3"), s.solutions)
        assertTrue(s.steps.any { it.resulting.startsWith("D = 1") || it.resulting == "D = 1" })
        assertEquals("x = 2 or x = 3", s.finalExpression)
    }
    @Test fun quadratic_bringsEverythingToStandardForm() {
        val s = solved("x^2 + 2 = 3x")
        assertEquals("x^2 - 3x + 2 = 0", s.steps.first().resulting)
        assertEquals(listOf("1", "2"), s.solutions)
    }
    @Test fun quadratic_repeatedRoot() { assertEquals(listOf("-3"), solved("x^2 + 6x + 9 = 0").solutions) }
    @Test fun quadratic_irrationalRoots_giveExactAndDecimal() {
        val s = solved("x^2 - 2x - 1 = 0"); assertEquals(2, s.solutions.size)
        assertTrue(s.finalExpression.contains("√2") || s.finalExpression.contains("√8"))
        assertEquals(2.4142135, s.solutions.maxOf { it.toApproxDouble() }, 1e-6)
    }
    @Test fun quadratic_noRealRoots() { assertTrue(solve("x^2 + 1 = 0") is SolveOutcome.NoSolution) }
    @Test fun quadratic_differenceOfSquares_viaProduct() { assertEquals(listOf("-1", "1"), solved("(x+1)(x-1) = 0").solutions) }

    // ----------------------------------------------------------- unsupported
    @Test fun higherDegree_isUnsupported() { assertTrue(solve("x^3 = 8") is SolveOutcome.Unsupported) }
    @Test fun variableInDenominator_isUnsupported() { assertTrue(solve("1/x = 2") is SolveOutcome.Unsupported) }
    @Test fun functionOfVariable_isUnsupported() { assertTrue(solve("sin(x) = 1") is SolveOutcome.Unsupported) }
    @Test fun twoVariables_isUnsupported() { assertTrue(solve("x + y = 3") is SolveOutcome.Unsupported) }
    @Test fun divisionByZeroInEquation_isInvalid() { assertTrue(solve("x / 0 = 1") is SolveOutcome.Invalid) }

    private fun String.toApproxDouble(): Double = value(this)   // solutions are plain parseable expressions, e.g. "(2 + √8) / 2"
}
