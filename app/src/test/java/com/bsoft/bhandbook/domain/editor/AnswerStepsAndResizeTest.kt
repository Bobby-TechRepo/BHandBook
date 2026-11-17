package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.core.recognition.InkSignature
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep
import org.junit.Assert.*
import org.junit.Test

class AnswerStepsAndResizeTest {
    private val ink = WorldRect(100f, 100f, 400f, 180f)
    private val steps = listOf(SolutionStep(1, "2x + 5 = 15", "Subtract 5", "2x = 10"), SolutionStep(2, "2x = 10", "Divide by 2", "x = 5"))
    private fun e(result: String? = "x = 5", visible: Boolean = true, steps: List<SolutionStep> = this.steps, inline: Boolean = false) =
        ExpressionObject("e", recognized = "2x+5=15", result = result, steps = steps, solutionVisible = visible, status = ProcessingStatus.EVALUATED, resultInline = inline)

    // ---------------------------------------------------------------- the answer and the steps are never both shown
    @Test fun stepsVisible_theAnswerIsHidden_becauseTheStepsEndWithIt() {
        val l = ExpressionLayout.of(ink, e(visible = true))!!; assertNull(l.result); assertEquals(2, l.stepLines.size); assertTrue(l.stepLines.last().text.contains("x = 5"))
    }
    @Test fun stepsHidden_theAnswerIsShown() { val l = ExpressionLayout.of(ink, e(visible = false))!!; assertEquals("x = 5", l.result!!.text); assertTrue(l.stepLines.isEmpty()) }
    @Test fun noSteps_theAnswerIsAlwaysShown() { for (v in listOf(true, false)) { val l = ExpressionLayout.of(ink, e(visible = v, steps = emptyList()))!!; assertEquals("x = 5", l.result!!.text); assertTrue(l.stepLines.isEmpty()) } }
    @Test fun stepsStartWhereTheAnswerWouldHaveBeen() {
        val hidden = ExpressionLayout.of(ink, e(visible = false))!!; val shown = ExpressionLayout.of(ink, e(visible = true))!!
        assertEquals(hidden.result!!.origin.y, shown.stepLines.first().origin.y, 0.01f)
    }
    @Test fun anInlineAnswer_isAlsoHiddenWhileStepsShow() { val l = ExpressionLayout.of(ink, e(inline = true, visible = true))!!; assertNull(l.result); assertTrue(l.stepLines.isNotEmpty()); assertNotNull(ExpressionLayout.of(ink, e(inline = true, visible = false))!!.result) }
    @Test fun withoutAnAnswer_nothingChanges() { val l = ExpressionLayout.of(ink, e(result = null, visible = true))!!; assertNull(l.result); assertTrue(l.stepLines.isEmpty()) }
    @Test fun theBoundsStillContainWhatIsShown() { for (v in listOf(true, false)) { val l = ExpressionLayout.of(ink, e(visible = v))!!; assertTrue(l.hitBounds.bottom > ink.bottom) } }

    // ---------------------------------------------------------------- resizing expressions
    private fun stroke(id: String, e: String?, x: Float, y: Float, w: Float = 4f) = Stroke(id, listOf(StrokePoint(x, y, 1L, 0.5f), StrokePoint(x + 40f, y + 20f, 2L, 0.7f)), w, 0, e)
    private fun editor(): CanvasEditor {
        var n = 0; val ed = CanvasEditor(idGenerator = { "n${n++}" }, clock = { 1L })
        val strokes = listOf(stroke("a1", "a", 100f, 100f), stroke("a2", "a", 160f, 100f), stroke("b1", "b", 400f, 300f), stroke("free", null, 0f, 0f))
        fun obj(id: String) = ExpressionObject(id, recognized = "1+1=", result = "2", status = ProcessingStatus.EVALUATED, strokeSignature = InkSignature.of(strokes.filter { it.expressionId == id }))
        ed.load(CanvasState(strokes, mapOf("a" to obj("a"), "b" to obj("b")))); return ed
    }
    private fun pt(ed: CanvasEditor, id: String, i: Int) = ed.state.strokes.first { it.id == id }.points[i]

    @Test fun scaling_movesPointsAboutTheAnchor_andKeepsTheAnchorFixed() {
        val ed = editor(); ed.scaleExpressions(listOf("a"), 2f, 100f, 100f)
        assertEquals(100f, pt(ed, "a1", 0).x, 1e-4f); assertEquals(100f, pt(ed, "a1", 0).y, 1e-4f); assertEquals(180f, pt(ed, "a1", 1).x, 1e-4f); assertEquals(140f, pt(ed, "a1", 1).y, 1e-4f)
        assertEquals(220f, pt(ed, "a2", 0).x, 1e-4f)                                                        // 100 + (160 - 100) * 2
    }
    @Test fun scaling_scalesTheStrokeWidth_andLeavesTimeAndPressureAlone() { val ed = editor(); ed.scaleExpressions(listOf("a"), 2f, 100f, 100f); assertEquals(8f, ed.state.strokes.first { it.id == "a1" }.width, 1e-4f); assertEquals(1L, pt(ed, "a1", 0).t); assertEquals(0.7f, pt(ed, "a1", 1).pressure, 0f) }
    @Test fun scaling_onlyTouchesTheChosenExpressions() { val ed = editor(); val before = ed.state.strokes; ed.scaleExpressions(listOf("a"), 3f, 0f, 0f); for (id in listOf("b1", "free")) assertEquals(before.first { it.id == id }, ed.state.strokes.first { it.id == id }) }
    @Test fun scalingSeveralTogether_isOneUndoStep_andUndoIsExact() {
        val ed = editor(); val before = ed.state; ed.scaleExpressions(listOf("a", "b"), 1.5f, 50f, 50f); assertNotEquals(before, ed.state)
        ed.undo(); assertEquals(before, ed.state); assertFalse(ed.canUndo); ed.redo(); assertEquals(1.5f * (400f - 50f) + 50f, pt(ed, "b1", 0).x, 1e-3f)
    }
    @Test fun aScaledExpression_isNotReRecognised() {
        val ed = editor(); ed.scaleExpressions(listOf("a"), 2f, 100f, 100f)
        assertEquals(InkSignature.of(ed.state.strokesOf("a")), ed.state.expressions.getValue("a").strokeSignature); assertTrue("a" !in ed.expressionsNeedingRecognition()); assertEquals("2", ed.state.expressions.getValue("a").result)
    }
    @Test fun anExpressionThatWasAlreadyStale_staysStale() {
        val ed = editor(); ed.updateExpression("a") { it.copy(strokeSignature = 12345) }; ed.scaleExpressions(listOf("a"), 2f, 100f, 100f); assertEquals(12345, ed.state.expressions.getValue("a").strokeSignature)
    }
    @Test fun badFactorsAndUnknownIdsAreIgnored() {
        val ed = editor(); for (f in listOf(Float.NaN, 0f, -2f, Float.POSITIVE_INFINITY, 1f, 1.0004f)) ed.scaleExpressions(listOf("a"), f, 0f, 0f)
        ed.scaleExpressions(listOf("zz"), 2f, 0f, 0f); ed.scaleExpressions(emptyList(), 2f, 0f, 0f); ed.scaleExpressions(listOf("a"), 2f, Float.NaN, 0f); assertFalse(ed.canUndo)
    }
    @Test fun oneStepIsBounded() { val big = editor(); big.scaleExpressions(listOf("a"), 1000f, 100f, 100f); assertEquals(100f + 60f * CanvasEditor.MAX_SCALE_STEP, pt(big, "a2", 0).x, 1e-2f); val small = editor(); small.scaleExpressions(listOf("a"), 0.0001f, 100f, 100f); assertEquals(100f + 60f * CanvasEditor.MIN_SCALE_STEP, pt(small, "a2", 0).x, 1e-3f) }

    // ---------------------------------------------------------------- the selection box and its handle
    private fun layouts(): Map<String, ExpressionLayout> {
        val ed = editor(); return ExpressionLayout.all(ed.state.strokes, ed.state.expressions)
    }
    @Test fun selectionBox_isTheUnionOfTheSelectedLayouts() {
        val l = layouts(); val one = SelectionBox.of(l, setOf("a"))!!; val both = SelectionBox.of(l, setOf("a", "b"))!!
        assertEquals(l.getValue("a").hitBounds, one.hit); assertEquals(l.getValue("a").ink, one.ink); assertTrue(both.hit.right >= l.getValue("b").hitBounds.right && both.hit.left <= l.getValue("a").hitBounds.left)
        assertEquals(both.ink.left, minOf(l.getValue("a").ink.left, l.getValue("b").ink.left), 0f)
    }
    @Test fun selectionBox_ofNothingKnown_isNull() { assertNull(SelectionBox.of(layouts(), emptySet())); assertNull(SelectionBox.of(layouts(), setOf("zz"))) }
    @Test fun theHandleSitsAtTheBottomRightCorner_andIsHitWithinItsRadius() {
        val box = SelectionBox(WorldRect(0f, 0f, 100f, 50f), WorldRect(10f, 10f, 90f, 40f))
        assertEquals(Vec2(100f, 50f), box.handle); assertTrue(box.hitsHandle(Vec2(103f, 48f), 10f)); assertFalse(box.hitsHandle(Vec2(50f, 25f), 10f)); assertFalse(box.hitsHandle(Vec2(Float.NaN, 1f), 10f))
    }
    @Test fun resizeFactor_isTheRatioOfTheHandleDistances() {
        val box = SelectionBox(WorldRect(0f, 0f, 200f, 100f), WorldRect(20f, 20f, 180f, 80f))        // anchor (20, 20); the handle is 180 right of it
        assertEquals(1f, box.factorFor(0f), 1e-6f); assertEquals(2f, box.factorFor(180f), 1e-5f); assertEquals(0.5f, box.factorFor(-90f), 1e-5f)
        assertEquals(SelectionBox.MIN_FACTOR, box.factorFor(-1e6f), 0f); assertEquals(SelectionBox.MAX_FACTOR, box.factorFor(1e6f), 0f); assertEquals(1f, box.factorFor(Float.NaN), 0f)
    }
}
