package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.core.recognition.*
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.solver.*
import org.junit.Assert.*
import org.junit.Test

class DefinitionsUseCaseTest {
    private val parser = DefaultMathParser()
    private val none = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = throw AssertionError("not used") }
    private val useCase = ProcessExpressionUseCase(none, parser, ExactCalculationEngine(), DefaultSolutionEngine())
    private fun defs(vararg src: String, calc: CalcSettings = CalcSettings()): Definitions =
        DefinitionResolver.resolve(src.mapIndexedNotNull { i, s -> DefinitionParser.parse((parser.parse(s) as ParseResult.Success).statement, i.toLong()) }, calc)
    private fun run(text: String, d: Definitions = Definitions.EMPTY, calc: CalcSettings = CalcSettings()) = useCase.fromText(text, 1f, ProcessingSettings(calc = calc, definitions = d))

    // ---------------------------------------------------------------- expressions use defined values and functions
    @Test fun aDefinedValueIsUsed() {
        val o = run("2a+1=", defs("a=5")) as ProcessingOutcome.Evaluated; assertEquals("11", o.result)
        assertTrue("the first step says what was substituted", o.steps.first().explanation.contains("a = 5")); assertEquals("11", o.steps.last().resulting)
    }
    @Test fun aDefinedFunctionIsCalled() { assertEquals("7", (run("f(3)=", defs("f(x)=2x+1")) as ProcessingOutcome.Evaluated).result); assertEquals("8", (run("f(2)+f(1)=", defs("f(x)=2x+1")) as ProcessingOutcome.Evaluated).result) }
    @Test fun valuesAndFunctionsTogether() { assertEquals("19", (run("f(a)+4=", defs("a=5", "f(x)=3x")) as ProcessingOutcome.Evaluated).result) }
    @Test fun definitionsFollowTheModes() {
        assertEquals("1/2", (run("s=", defs("s=sin(30)", calc = CalcSettings(angle = AngleMode.DEGREES)), CalcSettings(angle = AngleMode.DEGREES)) as ProcessingOutcome.Evaluated).result)
        assertEquals("₦2,500.00", (run("a*b=", defs("a=50", "b=50"), CalcSettings(places = 2, grouping = true, currency = "₦")) as ProcessingOutcome.Evaluated).result)
    }
    @Test fun withoutAValue_theMessageSaysHowToGiveItOne() {
        val o = run("2a+1=") as ProcessingOutcome.Failed; assertTrue(o.message, o.message.contains("no value yet") && o.message.contains("a ="))
        val u = run("h(3)=") as ProcessingOutcome.Failed; assertTrue(u.message, u.message.contains("'h'"))
        val x = run("2x+1=") as ProcessingOutcome.Failed; assertTrue(x.message, x.message.contains("equation"))
    }
    @Test fun withoutAnEqualsSign_anUndefinedNameIsJustRecognised_asBefore() { assertTrue(run("2a+1") is ProcessingOutcome.RecognizedOnly) }
    @Test fun definitionsDoNotAffectOrdinaryArithmetic() { val d = defs("a=5", "f(x)=2x+1"); assertEquals("40", (run("25+15=", d) as ProcessingOutcome.Evaluated).result); assertEquals("4", (run("sqrt(16)=", d) as ProcessingOutcome.Evaluated).result) }

    // ---------------------------------------------------------------- writing a definition
    @Test fun aFunctionDefinition_isRecordedNotSolved() {
        val o = run("f(x)=2x+1"); assertTrue("$o", o is ProcessingOutcome.RecognizedOnly); assertEquals("f(x) = 2x + 1", (o as ProcessingOutcome.RecognizedOnly).displayText)
    }
    @Test fun aValueDefinition_isStillShownAsItsAnswer() { val o = run("a=5") as ProcessingOutcome.Solved; assertEquals("a = 5", o.finalExpression) }
    @Test fun aValueDefinedFromOtherValues() { val o = run("b=a*2", defs("a=5")) as ProcessingOutcome.Solved; assertEquals("b = 10", o.finalExpression); assertTrue(o.steps.first().explanation.contains("a = 5")) }

    // ---------------------------------------------------------------- equations: defined names are known, the rest are solved for
    @Test fun anEquationWithADefinedName_isSolvedForTheOtherUnknown() {
        val o = run("x+a=10", defs("a=3")) as ProcessingOutcome.Solved; assertEquals("x = 7", o.finalExpression)
        assertTrue(o.steps.first().explanation.contains("a = 3")); assertEquals("x + 3 = 10", o.steps.first().resulting)
        assertEquals("x = 3", (run("2x+a=7", defs("a=1")) as ProcessingOutcome.Solved).finalExpression)
    }
    @Test fun aFunctionCallInAnEquation() { assertEquals("x = 7", (run("x=f(3)", defs("f(x)=2x+1")) as ProcessingOutcome.Solved).finalExpression); assertEquals("x = 3", (run("2x+1=7", defs("f(x)=2x+1")) as ProcessingOutcome.Solved).finalExpression) }
    @Test fun anEquationInOneUnknown_isSolvedAsBefore_evenIfThatNameIsDefined() { assertEquals("a = 7", (run("a+2=9", defs("a=5")) as ProcessingOutcome.Solved).finalExpression) }
    @Test fun whenNothingIsLeftToSolve_theAnswerIsTrueOrFalse() {
        assertEquals("true", (run("a+b=8", defs("a=3", "b=5")) as ProcessingOutcome.Solved).finalExpression)
        assertEquals("false", (run("a+b=9", defs("a=3", "b=5")) as ProcessingOutcome.Solved).finalExpression)
    }
    @Test fun systemsAndOrdinaryEquations_areUnaffected() { assertEquals("x = 5", (run("2x+5=15", defs("a=5")) as ProcessingOutcome.Solved).finalExpression) }
    @Test fun aSolvedEquationFollowsTheResultFormat() { assertEquals("x = 3.33", (run("3x+a=10", defs("a=0"), CalcSettings(places = 2)) as ProcessingOutcome.Solved).finalExpression) }

    // ---------------------------------------------------------------- re-evaluating
    @Test fun reevaluating_usesTheCurrentDefinitions() {
        val a = useCase.reevaluate("2a+1", 1f, resultInline = true, ProcessingSettings(definitions = defs("a=5"))) as ProcessingOutcome.Evaluated
        val b = useCase.reevaluate("2a+1", 1f, resultInline = true, ProcessingSettings(definitions = defs("a=6"))) as ProcessingOutcome.Evaluated
        assertEquals("11", a.result); assertEquals("13", b.result)
    }
}
