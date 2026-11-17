package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.ProcessingStatus
import com.bsoft.bhandbook.domain.solver.SolutionStep
import org.junit.Assert.*
import org.junit.Test

class OutcomeMapperTest {
    private val base = ExpressionObject("e1")
    private val steps = listOf(SolutionStep(1, "25 + 15", "Add", "40"))

    @Test fun explicitEvaluation_putsResultInlineAfterTheEquals() {
        val e = ProcessingOutcome.Evaluated("25+15=", 0.9f, "25 + 15", "40", null, explicit = true, steps = steps).applyTo(base, 5L)
        assertEquals("40", e.result); assertTrue(e.resultInline); assertEquals(ProcessingStatus.EVALUATED, e.status)
        assertEquals("25+15=", e.recognized); assertEquals("25 + 15", e.displayText); assertEquals(steps, e.steps); assertEquals(5L, e.modifiedAt)
    }
    @Test fun automaticEvaluation_putsResultBelow() {
        val e = ProcessingOutcome.Evaluated("25+15", 0.9f, "25 + 15", "40", null, explicit = false, steps = steps).applyTo(base, 5L)
        assertFalse(e.resultInline)
    }
    @Test fun fractionResult_carriesDecimalCompanion() {
        val e = ProcessingOutcome.Evaluated("7/2", 0.9f, "7/2", "7/2", "3.5", explicit = true, steps = emptyList()).applyTo(base, 1L)
        assertEquals("7/2 ≈ 3.5", e.result)
    }
    @Test fun solvedEquation_showsFinalAnswerAndSteps() {
        val e = ProcessingOutcome.Solved("2x+5=15", 0.9f, "2x + 5 = 15", "x = 5", listOf("5"), steps).applyTo(base, 1L)
        assertEquals("x = 5", e.result); assertFalse(e.resultInline); assertEquals(steps, e.steps); assertEquals(ProcessingStatus.EVALUATED, e.status)
    }
    @Test fun needsReview_hasNoResult_andKeepsTheCandidateText() {
        val e = ProcessingOutcome.NeedsReview("2?5", 0.3f, listOf("low confidence")).applyTo(base.copy(result = "old", steps = steps), 1L)
        assertNull(e.result); assertTrue(e.steps.isEmpty()); assertEquals(ProcessingStatus.NEEDS_REVIEW, e.status); assertEquals("2?5", e.recognized)
    }
    @Test fun invalid_isAnError_withTheMessage() {
        val e = ProcessingOutcome.Invalid("25+", 0.9f, "incomplete expression", 3).applyTo(base, 1L)
        assertEquals(ProcessingStatus.ERROR, e.status); assertEquals("incomplete expression", e.error); assertNull(e.result)
    }
    @Test fun failed_isAnError_withTheMessage() {
        val e = ProcessingOutcome.Failed("5/0=", 0.9f, "5 / 0", "division by zero").applyTo(base, 1L)
        assertEquals(ProcessingStatus.ERROR, e.status); assertEquals("division by zero", e.error)
    }
    @Test fun recognizedOnly_isShownButNotEvaluated() {
        val e = ProcessingOutcome.RecognizedOnly("25+15", 0.9f, "25 + 15").applyTo(base, 1L)
        assertEquals(ProcessingStatus.RECOGNIZED, e.status); assertNull(e.result)
    }
    @Test fun applying_preservesUserSolutionVisibility() {
        val e = ProcessingOutcome.Solved("x=1", 1f, "x = 1", "x = 1", listOf("1"), steps).applyTo(base.copy(solutionVisible = false), 1L)
        assertFalse(e.solutionVisible)
    }
}
