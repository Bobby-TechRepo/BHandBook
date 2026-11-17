package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep
import org.junit.Assert.*
import org.junit.Test

class CanvasSummaryTest {
    private fun ink(id: String, left: Float, top: Float) = Stroke("s_$id", listOf(StrokePoint(left, top), StrokePoint(left + 200f, top + 60f)), 3f, 0, id)
    private fun expr(id: String, text: String?, result: String? = null, status: ProcessingStatus = ProcessingStatus.EVALUATED, steps: List<SolutionStep> = emptyList(),
                     display: String? = null, error: String? = null, visible: Boolean = true) =
        ExpressionObject(id, recognized = text, displayText = display, result = result, status = status, steps = steps, solutionVisible = visible, error = error)
    private fun canvas(vararg items: Triple<ExpressionObject, Float, Float>) = CanvasState(items.map { (e, x, y) -> ink(e.id, x, y) }, items.associate { (e, _, _) -> e.id to e })
    private val none = Insights(emptyMap(), emptySet())
    private fun build(c: CanvasState, ins: Insights = none, steps: Boolean = false, title: String = "") = CanvasSummary.build(c, ins, title, steps)

    @Test fun anEmptyCanvas_hasNothingToShare() { assertEquals("", build(CanvasState())) }
    @Test fun aCanvasWithOnlyUnreadInk_hasNothingToShare() { assertEquals("", build(canvas(Triple(expr("a", null, status = ProcessingStatus.PENDING), 0f, 0f)))) }

    @Test fun anInlineResult_isWrittenWithAnEqualsSign() { assertEquals("25 + 15 = 40", build(canvas(Triple(expr("a", "25+15=", "40", display = "25 + 15"), 0f, 0f)))) }
    @Test fun aSolvedEquation_isWrittenWithAnArrow() { assertEquals("2x + 5 = 15  →  x = 5", build(canvas(Triple(expr("a", "2x+5=15", "x = 5", display = "2x + 5 = 15"), 0f, 0f)))) }
    @Test fun theDisplayTextIsPreferred_andTheRecognisedTextIsTheFallback_withoutItsRequestingEqualsSign() {
        assertEquals("7 - 4 = 3", build(canvas(Triple(expr("a", "7-4=", "3", display = "7 - 4"), 0f, 0f))))
        assertEquals("the '=' that asked for the answer is not printed twice", "7-4 = 3", build(canvas(Triple(expr("a", "7-4=", "3"), 0f, 0f))))
    }
    @Test fun aColumnSum_readsLikeASum() { assertEquals("105 - 37 = 68", build(canvas(Triple(expr("a", "105-37", "68", display = "105 - 37"), 0f, 0f)))) }
    @Test fun anExpressionWithNoResult_isStillListed() { assertEquals("12.5", build(canvas(Triple(expr("a", "12.5", status = ProcessingStatus.RECOGNIZED), 0f, 0f)))) }
    @Test fun needsReviewAndErrors_areFlagged_notHidden() {
        val c = canvas(Triple(expr("a", "3?5", status = ProcessingStatus.NEEDS_REVIEW), 0f, 0f), Triple(expr("b", "1/0", status = ProcessingStatus.ERROR, error = "Division by zero"), 0f, 200f))
        assertEquals("3?5  (needs review)\n1/0  (Division by zero)", build(c))
    }
    @Test fun expressionsAreListedInReadingOrder_topToBottom_thenLeftToRight() {
        val c = canvas(Triple(expr("c", "3+3=", "6"), 0f, 400f), Triple(expr("b", "2+2=", "4"), 500f, 100f), Triple(expr("a", "1+1=", "2"), 0f, 100f))
        assertEquals("1+1 = 2\n2+2 = 4\n3+3 = 6", build(c))
    }
    @Test fun stepsAreIncludedOnlyWhenAskedFor_andOnlyForExpressionsWhoseStepsAreShowing() {
        val steps = listOf(SolutionStep(1, "2x + 5 = 15", "Subtract 5 from both sides", "2x = 10"), SolutionStep(2, "2x = 10", "Divide both sides by 2", "x = 5"))
        val shown = canvas(Triple(expr("a", "2x+5=15", "x = 5", steps = steps, display = "2x + 5 = 15"), 0f, 0f))
        assertEquals("2x + 5 = 15  →  x = 5", build(shown, steps = false))
        assertEquals("2x + 5 = 15  →  x = 5\n    1. Subtract 5 from both sides: 2x = 10\n    2. Divide both sides by 2: x = 5", build(shown, steps = true))
        assertEquals("hidden steps stay hidden", "2x + 5 = 15  →  x = 5", build(canvas(Triple(expr("a", "2x+5=15", "x = 5", steps = steps, display = "2x + 5 = 15", visible = false), 0f, 0f)), steps = true))
    }
    @Test fun simultaneousEquations_areListedTogether_withTheirAnswerOnce() {
        val sys = SystemSolution(listOf("a", "b"), "b", "x = 3, y = 2", listOf(SolutionStep(1, "x + y = 5", "Subtract", "-2y = -4")), false)
        val c = canvas(Triple(expr("a", "x+y=5", status = ProcessingStatus.ERROR, display = "x + y = 5"), 0f, 0f), Triple(expr("b", "x-y=1", status = ProcessingStatus.ERROR, display = "x - y = 1"), 0f, 120f), Triple(expr("c", "5+5=", "10"), 0f, 400f))
        val ins = Insights(mapOf("a" to sys, "b" to sys), setOf("a", "b"))
        assertEquals("x + y = 5\nx - y = 1\n  →  x = 3, y = 2\n5+5 = 10", build(c, ins))
        assertEquals("x + y = 5\nx - y = 1\n  →  x = 3, y = 2\n    1. Subtract: -2y = -4\n5+5 = 10", build(c, ins, steps = true))
    }
    @Test fun aSystemThatCouldNotBeSolved_saysSo() {
        val sys = SystemSolution(listOf("a", "b"), "b", "not supported", emptyList(), true)
        val c = canvas(Triple(expr("a", "x*y=1", status = ProcessingStatus.ERROR), 0f, 0f), Triple(expr("b", "x+y=3", status = ProcessingStatus.ERROR), 0f, 120f))
        assertTrue(build(c, Insights(mapOf("a" to sys, "b" to sys), emptySet())).contains("not supported"))
    }
    @Test fun aTitleHeadsTheSummary() { assertEquals("Homework\n\n25 + 15 = 40", build(canvas(Triple(expr("a", "25+15=", "40", display = "25 + 15"), 0f, 0f)), title = "Homework")) }
    @Test fun aBlankTitleAddsNoHeader() { assertEquals("1+1 = 2", build(canvas(Triple(expr("a", "1+1=", "2"), 0f, 0f)), title = "  ")) }
    @Test fun expressionsWithoutInk_areIgnored() { assertEquals("", build(CanvasState(emptyList(), mapOf("a" to expr("a", "1+1=", "2"))))) }
}
