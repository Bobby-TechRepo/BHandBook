package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.core.recognition.HandwritingMathRecognizer
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import org.junit.Assert.*
import org.junit.Test

/** End-to-end: ink -> recognition -> parsing -> evaluation / solving. Uses clean writing so recognition is not the variable under test. */
class ProcessExpressionTest {
    private val useCase = ProcessExpressionUseCase(HandwritingMathRecognizer(), DefaultMathParser(), ExactCalculationEngine(), DefaultSolutionEngine())
    private fun clean(seed: Long = 3) = Pen(seed, sloppiness = 0.5f)
    private fun run(strokes: List<Stroke>, auto: Boolean = true) = useCase(strokes, ProcessingSettings(autoEvaluate = auto))

    @Test fun explicitEquals_evaluates_andShowsFullEquation() {
        val o = run(clean().text("25+15=").strokes)
        assertTrue("$o", o is ProcessingOutcome.Evaluated)
        o as ProcessingOutcome.Evaluated
        assertEquals("40", o.result); assertTrue(o.explicit); assertEquals("25 + 15", o.displayText)
    }
    @Test fun noEquals_autoEvaluate_evaluates() {
        val o = run(clean().text("25+15").strokes); assertTrue("$o", o is ProcessingOutcome.Evaluated)
        o as ProcessingOutcome.Evaluated; assertEquals("40", o.result); assertFalse(o.explicit)
    }
    @Test fun noEquals_autoEvaluateOff_onlyRecognises() {
        val o = run(clean().text("25+15").strokes, auto = false); assertTrue("$o", o is ProcessingOutcome.RecognizedOnly)
    }
    @Test fun explicitEquals_evaluates_evenWhenAutoEvaluateIsOff() {
        val o = run(clean().text("25+15=").strokes, auto = false); assertTrue("$o", o is ProcessingOutcome.Evaluated)
    }
    @Test fun precedenceIsRespected_endToEnd() {
        val o = run(clean(4).text("25+15*2=").strokes) as ProcessingOutcome.Evaluated
        assertEquals("55", o.result)
        assertEquals(listOf("25 + 30", "55"), o.steps.map { it.resulting })       // steps are produced alongside the answer
    }
    @Test fun fractions_endToEnd() {
        val o = run(clean(5).frac("1", "2").text("+").frac("1", "3").text("=").strokes)
        assertTrue("$o", o is ProcessingOutcome.Evaluated); assertEquals("5/6", (o as ProcessingOutcome.Evaluated).result)
    }
    @Test fun squareRoot_endToEnd() {
        val o = run(clean(2).sqrt("9").text("+2=").strokes); assertTrue("$o", o is ProcessingOutcome.Evaluated)
        assertEquals("5", (o as ProcessingOutcome.Evaluated).result)
    }
    @Test fun exponent_endToEnd() {
        val o = run(clean(2).text("2").sup("3").text("=").strokes); assertTrue("$o", o is ProcessingOutcome.Evaluated)
        assertEquals("8", (o as ProcessingOutcome.Evaluated).result)
    }
    @Test fun equation_isSolvedWithSteps() {
        val o = run(clean(3).text("2x+5=15").strokes)
        assertTrue("$o", o is ProcessingOutcome.Solved); o as ProcessingOutcome.Solved
        assertEquals("x = 5", o.finalExpression); assertEquals(4, o.steps.size)
    }
    @Test fun incompleteExpression_isReportedNotEvaluated() {
        val o = run(clean().text("25+").strokes); assertTrue("$o", o is ProcessingOutcome.Invalid)
        assertEquals("25+", (o as ProcessingOutcome.Invalid).recognized)
    }
    @Test fun divisionByZero_isReportedAsFailure() {
        val o = run(clean(2).text("5D0=").strokes); assertTrue("$o", o is ProcessingOutcome.Failed)
    }
    @Test fun lowConfidence_isNeverEvaluated_andAsksForReview() {
        val rnd = java.util.Random(7)
        val scribble = (0 until 4).map { s -> Stroke("z$s", (0 until 25).map { i -> StrokePoint(100f + s * 40f + rnd.nextFloat() * 30f, 300f + i * 2f + rnd.nextFloat() * 20f, i * 5L) }, 4f, 0) }
        val o = run(scribble); assertTrue("$o", o is ProcessingOutcome.NeedsReview)
    }
    @Test fun emptyInk_isNothingToDo() { assertTrue(run(emptyList()) is ProcessingOutcome.NeedsReview) }

    // user-corrected text is trusted over ink and goes through the same parse/evaluate path
    @Test fun correctedText_isEvaluatedWithoutRecognition() {
        val o = useCase.fromText("25+16=", confidence = 1f, settings = ProcessingSettings())
        assertEquals("41", (o as ProcessingOutcome.Evaluated).result)
    }
    @Test fun editingTheInk_changesTheResult() {
        val before = run(clean(3).text("25+15=").strokes) as ProcessingOutcome.Evaluated
        val after = run(clean(3).text("25+16=").strokes) as ProcessingOutcome.Evaluated
        assertEquals("40", before.result); assertEquals("41", after.result)
    }
}
