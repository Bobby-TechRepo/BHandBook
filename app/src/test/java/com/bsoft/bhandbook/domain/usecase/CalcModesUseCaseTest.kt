package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.core.recognition.*
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

class CalcModesUseCaseTest {
    private val none = object : MathRecognitionEngine { override fun recognize(strokes: List<com.bsoft.bhandbook.domain.model.Stroke>, isCancelled: () -> Boolean) = throw AssertionError("not used") }
    private val useCase = ProcessExpressionUseCase(none, DefaultMathParser(), ExactCalculationEngine(), DefaultSolutionEngine())
    private fun run(text: String, calc: CalcSettings, auto: Boolean = true) = useCase.fromText(text, 1f, ProcessingSettings(autoEvaluate = auto, calc = calc))
    private fun result(text: String, calc: CalcSettings): String = (run(text, calc) as ProcessingOutcome.Evaluated).result
    private val deg = CalcSettings(angle = AngleMode.DEGREES); private val rad = CalcSettings(angle = AngleMode.RADIANS); private val grad = CalcSettings(angle = AngleMode.GRADIANS)

    @Test fun theSameTextGivesDifferentAnswersInEachUnit() {
        assertEquals("1/2", result("sin(30)=", deg)); assertEquals("-0.9880316241", result("sin(30)=", rad)); assertEquals("0.4539904997", result("sin(30)=", grad))
    }
    @Test fun settingsDefaultToRadians_asBefore() { assertEquals("-0.9880316241", (useCase.fromText("sin(30)=", 1f) as ProcessingOutcome.Evaluated).result) }
    @Test fun exactTrigValuesComeWithTheirDecimalBeside() { val o = run("sin(30)=", deg) as ProcessingOutcome.Evaluated; assertEquals("1/2", o.result); assertEquals("0.5", o.decimal) }
    @Test fun tangentOfANinetyDegreeAngle_isAnError_notAHugeNumber() { val o = run("tan(90)=", deg); assertTrue("$o", o is ProcessingOutcome.Failed); assertTrue((o as ProcessingOutcome.Failed).message.contains("undefined")) }
    @Test fun inverseFunctionsAnswerInTheUnit() { assertEquals("30", result("asin(1/2)=", deg)); assertEquals("100", result("asin(1)=", grad)); assertEquals("1.570796327", result("asin(1)=", rad)) }
    @Test fun resultFormat_isAppliedToTheAnswer() {
        assertEquals("5/2", result("10/4=", CalcSettings())); assertEquals("2.5", result("10/4=", CalcSettings(style = ResultStyle.DECIMAL))); assertEquals("2.50", result("10/4=", CalcSettings(places = 2)))
        assertEquals("0.71", result("sin(45)=", CalcSettings(angle = AngleMode.DEGREES, places = 2)))
    }
    @Test fun anEquationUsesTheModesToo() {
        assertEquals("x = 5", (run("2*sin(30)*x=5", deg) as ProcessingOutcome.Solved).finalExpression)
        assertEquals("x = 3.33", (run("3x=10", CalcSettings(places = 2)) as ProcessingOutcome.Solved).finalExpression)
    }
    @Test fun stepsAreInTheChosenFormat() {
        val o = run("sin(45)+sin(45)=", CalcSettings(angle = AngleMode.DEGREES, places = 2)) as ProcessingOutcome.Evaluated
        assertEquals("1.41", o.result); assertTrue(o.steps.isNotEmpty()); assertEquals("1.41", o.steps.last().resulting)
    }
    @Test fun aColumnTotalFollowsTheFormat() {
        val total = ColumnTotal.compute(listOf("1 2", "+ 7 . 5").map { ColumnTotal.RowReading(it.split(" "), null) }, CalcSettings(places = 2)) as ColumnTotal.Result
        assertEquals("19.50", total.total)
    }

    // ---------------------------------------------------------------- changing a mode re-evaluates what is already on the canvas
    @Test fun reevaluating_anExplicitExpression_usesTheNewMode() {
        val a = useCase.reevaluate("sin(30)=", 1f, resultInline = true, ProcessingSettings(calc = deg)) as ProcessingOutcome.Evaluated; assertEquals("1/2", a.result)
        val b = useCase.reevaluate("sin(30)=", 1f, resultInline = true, ProcessingSettings(calc = rad)) as ProcessingOutcome.Evaluated; assertEquals("-0.9880316241", b.result)
    }
    @Test fun reevaluating_aColumnTotal_keepsItsExplicitAnswer_whichHasNoEqualsSignInItsText() {
        val o = useCase.reevaluate("105-37", 1f, resultInline = true, ProcessingSettings(calc = CalcSettings(places = 2))) as ProcessingOutcome.Evaluated
        assertEquals("68.00", o.result); assertTrue(o.explicit)
    }
    @Test fun reevaluating_anEquation_isJustSolvedAgain() { assertEquals("x = 3.33", (useCase.reevaluate("3x=10", 1f, resultInline = false, ProcessingSettings(calc = CalcSettings(places = 2))) as ProcessingOutcome.Solved).finalExpression) }
    @Test fun reevaluating_doesNotInventAnAnswerForAnExpressionThatWasNeverEvaluated() {
        val o = useCase.reevaluate("sin(30)", 1f, resultInline = false, ProcessingSettings(autoEvaluate = false, calc = deg)); assertTrue("$o", o is ProcessingOutcome.RecognizedOnly)
    }
}
