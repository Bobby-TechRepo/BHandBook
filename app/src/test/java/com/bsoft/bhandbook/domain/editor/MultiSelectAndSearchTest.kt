package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class MultiSelectAndSearchTest {
    // ---------------------------------------------------------------- the selection
    @Test fun startingFromAnExpression_keepsItSelected() { val m = MultiSelection().start("a"); assertTrue(m.active); assertEquals(setOf("a"), m.ids) }
    @Test fun startingWithNothing_isEmptyButActive() { val m = MultiSelection().start(null); assertTrue(m.active); assertTrue(m.ids.isEmpty()) }
    @Test fun tapping_addsAndRemoves() { var m = MultiSelection().start("a"); m = m.tap("b"); assertEquals(setOf("a", "b"), m.ids); m = m.tap("a"); assertEquals(setOf("b"), m.ids); m = m.tap("b"); assertTrue(m.ids.isEmpty() && m.active) }
    @Test fun tappingNothing_changesNothing() { val m = MultiSelection().start("a"); assertEquals(m, m.tap(null)) }
    @Test fun tapping_whenNotActive_doesNothing() { assertEquals(MultiSelection(), MultiSelection().tap("a")) }
    @Test fun selectAll_takesEveryExpression() { assertEquals(setOf("a", "b", "c"), MultiSelection().start("a").selectAll(listOf("a", "b", "c")).ids) }
    @Test fun pruning_forgetsDeletedExpressions() { assertEquals(setOf("a"), MultiSelection().start("a").tap("b").prune(setOf("a", "z")).ids) }
    @Test fun stopping_clearsEverything() { assertEquals(MultiSelection(), MultiSelection().start("a").tap("b").stop()) }

    // ---------------------------------------------------------------- batch operations are ONE undo step
    private fun stroke(id: String, e: String, x: Float) = Stroke("s$id", listOf(StrokePoint(x, 0f), StrokePoint(x + 10f, 10f)), 3f, 0, e)
    private fun editor(): CanvasEditor {
        var n = 0; val e = CanvasEditor(idGenerator = { "n${n++}" }, clock = { 1L })
        e.load(CanvasState(listOf(stroke("a", "a", 0f), stroke("b", "b", 100f), stroke("c", "c", 200f)), listOf("a", "b", "c").associateWith { ExpressionObject(it, recognized = "1+1=") }))
        return e
    }
    @Test fun movingSeveral_isOneUndoStep() {
        val e = editor(); e.moveExpressions(listOf("a", "b"), 5f, 7f)
        assertEquals(5f, e.state.strokes.first { it.id == "sa" }.points[0].x, 0f); assertEquals(105f, e.state.strokes.first { it.id == "sb" }.points[0].x, 0f); assertEquals(200f, e.state.strokes.first { it.id == "sc" }.points[0].x, 0f)
        e.undo(); assertEquals(0f, e.state.strokes.first { it.id == "sa" }.points[0].x, 0f); assertEquals(100f, e.state.strokes.first { it.id == "sb" }.points[0].x, 0f); assertFalse(e.canUndo)
    }
    @Test fun deletingSeveral_isOneUndoStep() {
        val e = editor(); e.deleteExpressions(setOf("a", "c")); assertEquals(setOf("b"), e.state.expressions.keys); assertEquals(listOf("sb"), e.state.strokes.map { it.id })
        e.undo(); assertEquals(setOf("a", "b", "c"), e.state.expressions.keys); assertEquals(3, e.state.strokes.size); assertFalse(e.canUndo)
    }
    @Test fun unknownIdsAndNoOps_areIgnored_andLeaveNoHistory() {
        val e = editor(); e.moveExpressions(listOf("zz"), 5f, 5f); e.moveExpressions(listOf("a"), 0f, 0f); e.moveExpressions(emptyList(), 1f, 1f); e.deleteExpressions(setOf("zz")); e.deleteExpressions(emptySet()); assertFalse(e.canUndo)
        e.deleteExpressions(setOf("a", "zz")); assertEquals(setOf("b", "c"), e.state.expressions.keys)
    }
    @Test fun nonFiniteMoves_areIgnored() { val e = editor(); e.moveExpressions(listOf("a"), Float.NaN, 1f); assertFalse(e.canUndo) }

    // ---------------------------------------------------------------- search
    private fun expr(id: String, text: String?, result: String? = null, display: String? = null) = ExpressionObject(id, recognized = text, result = result, displayText = display, status = ProcessingStatus.EVALUATED)
    private fun ink(id: String, x: Float, y: Float) = Stroke("s$id", listOf(StrokePoint(x, y), StrokePoint(x + 50f, y + 20f)), 3f, 0, id)
    private val state = CanvasState(
        listOf(ink("low", 0f, 500f), ink("top", 300f, 0f), ink("topLeft", 0f, 0f), ink("none", 0f, 900f)),
        mapOf("low" to expr("low", "12×8=", "96"), "top" to expr("top", "2x+5=15", "x = 5"), "topLeft" to expr("topLeft", "sin(30)=", "1/2"), "none" to expr("none", null)),
    )
    private fun find(q: String, name: String = "Homework", max: Int = 50) = CanvasSearch.inCanvas("c1", name, state, q, max)

    @Test fun normalisation_ignoresCaseSpacesAndOperatorVariants() { assertEquals(CanvasSearch.normalize("12 × 8"), CanvasSearch.normalize("12*8")); assertEquals(CanvasSearch.normalize("6÷3"), CanvasSearch.normalize("6/3")); assertEquals(CanvasSearch.normalize("SIN(30)"), CanvasSearch.normalize("sin( 30 )")); assertEquals(CanvasSearch.normalize("5−3"), CanvasSearch.normalize("5-3")) }
    @Test fun findsExpressionsByTheirText() { assertEquals(listOf("low"), find("12*8").map { it.expressionId }) }
    @Test fun findsExpressionsByTheirAnswer() { assertEquals(listOf("low"), find("96").map { it.expressionId }); assertEquals(listOf("top"), find("x = 5").map { it.expressionId }) }
    @Test fun matchesPartsOfText() { assertEquals(listOf("topLeft"), find("sin").map { it.expressionId }) }
    @Test fun resultsAreInReadingOrder_topThenLeft() { assertEquals(listOf("topLeft", "top", "low"), find("=").map { it.expressionId }) }
    @Test fun aCanvasNameMatchComesFirst_withNoExpression() { val h = find("home"); assertEquals(1, h.count { it.expressionId == null }); assertNull(h.first().expressionId); assertEquals("Homework", h.first().canvasName) }
    @Test fun nameAndContentMatchesBothAppear() { val h = find("o"); assertTrue(h.first().expressionId == null); assertTrue(h.drop(1).all { it.expressionId != null }) }
    @Test fun anEmptyOrBlankQuery_findsNothing() { assertTrue(find("").isEmpty()); assertTrue(find("   ").isEmpty()) }
    @Test fun expressionsWithNoTextAreSkipped() { assertTrue(find("").none { it.expressionId == "none" }); assertTrue(find("none").none { it.expressionId == "none" }) }
    @Test fun theLimitIsRespected() { assertEquals(2, find("=", max = 2).size) }
    @Test fun hitsCarryWhatToShow() { val h = find("12*8").single(); assertEquals("c1", h.canvasId); assertEquals("12×8=", h.text); assertEquals("96", h.result) }
    @Test fun noMatch_noHits() { assertTrue(find("zzz").isEmpty()) }
    @Test fun displayTextIsSearchedToo() { val s = CanvasState(listOf(ink("d", 0f, 0f)), mapOf("d" to expr("d", "2x+1=7", "x = 3", display = "2x + 1 = 7"))); assertEquals(1, CanvasSearch.inCanvas("c", "n", s, "2x + 1", 10).size) }
}
