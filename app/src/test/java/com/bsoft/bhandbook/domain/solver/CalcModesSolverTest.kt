package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import org.junit.Assert.*
import org.junit.Test

class CalcModesSolverTest {
    private val parser = DefaultMathParser()
    private val engine = DefaultSolutionEngine()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun eq(src: String) = st(src) as MathStatement.Equation
    private val deg = CalcSettings(angle = AngleMode.DEGREES)
    private fun places(n: Int) = CalcSettings(places = n)
    private val decimal = CalcSettings(style = ResultStyle.DECIMAL)

    // ---------------------------------------------------------------- the angle unit reaches equations
    @Test fun trigConstantsInAnEquation_useTheAngleUnit() {
        val o = engine.solve(st("2*sin(30)*x=5"), deg) as SolveOutcome.Solved; assertEquals("x = 5", o.finalExpression)
        assertEquals("x = 3", (engine.solve(st("2*cos(60)*x=3"), deg) as SolveOutcome.Solved).finalExpression)
    }
    @Test fun inRadians_aTrigConstantIsNotExact_soTheEquationIsReportedUnsupported() { assertTrue(engine.solve(st("2*sin(30)*x=5")) is SolveOutcome.Unsupported) }
    @Test fun trigConstantsInASystem_useTheAngleUnit() {
        val o = engine.solveSystem(listOf(eq("2*sin(30)*x+y=5"), eq("x-y=1")), deg) as SolveOutcome.Solved; assertEquals("x = 3, y = 2", o.finalExpression)
    }
    @Test fun numericTrigStatements_useTheAngleUnit() {
        assertTrue(engine.solve(st("sin(30)=1/2"), deg) is SolveOutcome.Identity); assertTrue(engine.solve(st("sin(30)=1/2")) is SolveOutcome.NoSolution)
    }
    @Test fun arithmeticStepsAreEvaluatedInTheAngleUnit() {
        val o = engine.solve(st("sin(30)+cos(60)"), deg) as SolveOutcome.Solved; assertEquals("1", o.finalExpression)
    }

    // ---------------------------------------------------------------- the result format reaches final answers
    @Test fun arithmetic_finalAnswerFollowsTheFormat() {
        assertEquals("5/2", (engine.solve(st("10/4")) as SolveOutcome.Solved).finalExpression)
        assertEquals("2.5", (engine.solve(st("10/4"), decimal) as SolveOutcome.Solved).finalExpression)
        assertEquals("2.50", (engine.solve(st("10/4"), places(2)) as SolveOutcome.Solved).finalExpression)
    }
    @Test fun anEquationSolution_isWrittenAsADecimalWhenAsked_withOneExplainingStep() {
        val exactO = engine.solve(st("3x=10")) as SolveOutcome.Solved; assertEquals("x = 10/3", exactO.finalExpression); assertEquals(listOf("10/3"), exactO.solutions)
        val d = engine.solve(st("3x=10"), decimal) as SolveOutcome.Solved; assertEquals("x = 3.333333333", d.finalExpression); assertEquals(listOf("3.333333333"), d.solutions)
        assertEquals("one extra, explaining step", exactO.steps.size + 1, d.steps.size); assertTrue(d.steps.last().explanation.contains("decimal", ignoreCase = true)); assertEquals("x = 3.333333333", d.steps.last().resulting)
        assertEquals("x = 3.33", (engine.solve(st("3x=10"), places(2)) as SolveOutcome.Solved).finalExpression)
    }
    @Test fun aSolutionThatAlreadyReadsTheSameWay_getsNoExtraStep() {
        val a = engine.solve(st("2x+5=15")) as SolveOutcome.Solved; val b = engine.solve(st("2x+5=15"), decimal) as SolveOutcome.Solved
        assertEquals(a.steps.size, b.steps.size); assertEquals("x = 5", b.finalExpression)
        assertEquals("x = 5.00", (engine.solve(st("2x+5=15"), places(2)) as SolveOutcome.Solved).finalExpression)
    }
    @Test fun quadraticRoots_followTheFormat_butIrrationalOnesStayExact() {
        assertEquals("x = -3.0 or x = 3.0", (engine.solve(st("x^2=9"), places(1)) as SolveOutcome.Solved).finalExpression)
        val irr = engine.solve(st("x^2=2"), places(2)) as SolveOutcome.Solved; assertTrue(irr.finalExpression.contains("√"))
    }
    @Test fun systems_followTheFormat() {
        val sys = listOf(eq("x/2+y=4"), eq("x-y=1"))
        assertEquals("x = 10/3, y = 7/3", (engine.solveSystem(sys) as SolveOutcome.Solved).finalExpression)
        assertEquals("x = 3.333333333, y = 2.333333333", (engine.solveSystem(sys, decimal) as SolveOutcome.Solved).finalExpression)
        assertEquals(listOf("x = 3.33", "y = 2.33"), (engine.solveSystem(sys, places(2)) as SolveOutcome.Solved).solutions)
    }
    @Test fun dependentAndImpossibleSystems_areUnaffected() {
        assertTrue((engine.solveSystem(listOf(eq("x+y=2"), eq("2x+2y=4")), places(2)) as SolveOutcome.Solved).finalExpression.contains("infinitely many"))
        assertTrue(engine.solveSystem(listOf(eq("x+y=2"), eq("x+y=3")), places(2)) is SolveOutcome.NoSolution)
    }
    @Test fun noSettings_changesNothing() {
        for (src in listOf("3x=10", "2x+5=15", "x^2=9", "10/4", "1+2*3")) assertEquals(src, engine.solve(st(src)), engine.solve(st(src), CalcSettings()))
    }
}
