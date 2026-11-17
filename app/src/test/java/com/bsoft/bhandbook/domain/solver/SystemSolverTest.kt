package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import org.junit.Assert.*
import org.junit.Test

class SystemSolverTest {
    private val parser = DefaultMathParser()
    private val calc = ExactCalculationEngine()
    private val engine = DefaultSolutionEngine()

    private fun eqs(vararg src: String) = src.map { (parser.parse(it) as ParseResult.Success).statement as MathStatement.Equation }
    private fun solve(vararg src: String) = engine.solveSystem(eqs(*src))
    private fun solved(vararg src: String): SolveOutcome.Solved { val o = solve(*src); assertTrue("expected Solved but was $o", o is SolveOutcome.Solved); return o as SolveOutcome.Solved }

    // ---------------------------------------------------------------- results
    @Test fun twoByTwo_uniqueSolution() {
        val s = solved("x+y=5", "x-y=1")
        assertEquals(listOf("x = 3", "y = 2"), s.solutions); assertEquals("x = 3, y = 2", s.finalExpression)
    }
    @Test fun coefficients_andOrderOfVariables_doNotMatter() { assertEquals(listOf("x = 2", "y = 1"), solved("3y+2x=7", "y-x=-1").solutions) }
    @Test fun fractionalSolution_isExact() { assertEquals(listOf("x = 10/3", "y = 7/3"), solved("x/2+y=4", "x-y=1").solutions) }
    @Test fun decimalInput_givesDecimalOutput() { assertEquals(listOf("x = 1.5", "y = 2"), solved("2x+y=5", "x-y=-0.5").solutions) }
    @Test fun threeByThree() { assertEquals(listOf("x = 1", "y = 2", "z = 3"), solved("x+y+z=6", "2x-y+z=3", "x+2y-z=2").solutions) }
    @Test fun threeVariables_overDetermined_butConsistent() { assertEquals(listOf("x = 1", "y = 2"), solved("x+y=3", "x-y=-1", "2x+y=4").solutions) }
    @Test fun otherVariableNames() { assertEquals(listOf("a = 2", "b = 3"), solved("a+b=5", "a-b=-1").solutions) }
    @Test fun bracketsAndBothSides_areExpanded() { assertEquals(listOf("x = 2", "y = 1"), solved("2(x+y)=6", "x=2y").solutions) }
    @Test fun anEquationWithOneVariable_isFine() { assertEquals(listOf("x = 2", "y = 3"), solved("x=2", "x+y=5").solutions) }

    @Test fun inconsistentSystem_hasNoSolution() { val o = solve("x+y=2", "x+y=3"); assertTrue("$o", o is SolveOutcome.NoSolution) }
    @Test fun falseNumericEquation_makesTheSystemInconsistent() { assertTrue(solve("x+y=2", "3=5") is SolveOutcome.NoSolution) }
    @Test fun dependentSystem_hasInfinitelyManySolutions_withAFreeVariable() {
        val s = solved("x+y=2", "2x+2y=4")
        assertTrue(s.finalExpression.contains("infinitely many", ignoreCase = true)); assertEquals(listOf("x = 2 - y"), s.solutions)
    }
    @Test fun trueNumericEquation_isIgnored() { assertEquals(listOf("x = 3", "y = 2"), solved("x+y=5", "x-y=1", "4=4").solutions) }
    @Test fun nonLinearEquations_areUnsupported() {
        assertTrue(solve("x*y=1", "x+y=3") is SolveOutcome.Unsupported); assertTrue(solve("x^2+y=3", "x-y=1") is SolveOutcome.Unsupported)
        assertTrue(solve("sin(x)+y=1", "x-y=0") is SolveOutcome.Unsupported); assertTrue(solve("1/x+y=1", "x-y=0") is SolveOutcome.Unsupported)
    }
    @Test fun divisionByZero_isInvalid() { assertTrue(solve("x/0+y=1", "x-y=0") is SolveOutcome.Invalid) }
    @Test fun tooManyUnknowns_areRejected() { assertTrue(solve("a+b+c+d+e=1", "a-b=0") is SolveOutcome.Unsupported) }
    @Test fun aSingleEquation_inOneUnknown_isJustSolved() { assertEquals(listOf("x = 5"), solved("2x+5=15").solutions) }

    // ---------------------------------------------------------------- steps are real, ordered and valid
    @Test fun stepsAreNumbered_chained_andExplained() {
        val s = solved("x+y=5", "x-y=1").steps
        assertTrue(s.isNotEmpty()); assertEquals((1..s.size).toList(), s.map { it.number }); assertTrue(s.all { it.explanation.isNotBlank() && it.resulting.isNotBlank() })
    }
    @Test fun finalStepsStateTheSolvedVariables() {
        val last = solved("x+y=5", "x-y=1").steps.takeLast(2).map { it.resulting }
        assertEquals(setOf("x = 3", "y = 2"), last.toSet())
    }
    @Test fun property_theAnswerSatisfiesEveryEquationShownInTheSteps() {
        val cases = mapOf(
            listOf("x+y=5", "x-y=1") to mapOf("x" to 3.0, "y" to 2.0), listOf("2x+3y=7", "x-y=1") to mapOf("x" to 2.0, "y" to 1.0),
            listOf("x/2+y=4", "x-y=1") to mapOf("x" to 10.0 / 3, "y" to 7.0 / 3), listOf("x+y+z=6", "2x-y+z=3", "x+2y-z=2") to mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0),
            listOf("0.5x+y=3", "x-2y=-4") to mapOf("x" to 1.0, "y" to 2.5), listOf("3x-2y=4", "5x+4y=14") to mapOf("x" to 2.0, "y" to 1.0),
        )
        for ((sys, answer) in cases) {
            val s = solved(*sys.toTypedArray())
            val vars = answer.mapValues { Value.Approx(it.value) as Value }
            for (text in s.steps.flatMap { listOf(it.expression, it.resulting) }) {
                val st = (parser.parse(text) as? ParseResult.Success)?.statement as? MathStatement.Equation ?: continue
                val l = (calc.evaluate(st.lhs, vars) as CalcResult.Success).value.toDouble(); val r = (calc.evaluate(st.rhs, vars) as CalcResult.Success).value.toDouble()
                assertEquals("'$text' (system $sys) is not satisfied by $answer", l, r, 1e-9)
            }
        }
    }
    @Test fun everyStepEquation_isParsableAsAnEquation() {
        for (s in solved("2x+3y=7", "x-y=1", ).steps) for (text in listOf(s.expression, s.resulting)) assertTrue("'$text'", (parser.parse(text) as? ParseResult.Success)?.statement is MathStatement.Equation)
    }
    @Test fun solutionAgreesWithSolvingByHand_forRandomSystems() {
        val rnd = java.util.Random(5)
        repeat(60) {
            val x = rnd.nextInt(11) - 5; val y = rnd.nextInt(11) - 5
            var a = 0; var b = 0; var c = 0; var d = 0
            while (a * d - b * c == 0) { a = rnd.nextInt(9) - 4; b = rnd.nextInt(9) - 4; c = rnd.nextInt(9) - 4; d = rnd.nextInt(9) - 4 }
            val s = solved("${a}x+${b}y=${a * x + b * y}".replace("+-", "-"), "${c}x+${d}y=${c * x + d * y}".replace("+-", "-"))
            assertEquals(listOf("x = $x", "y = $y"), s.solutions)
        }
    }
}
