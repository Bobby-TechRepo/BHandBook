package com.bsoft.bhandbook.performance

import com.bsoft.bhandbook.core.codec.CanvasCodec
import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.recognition.HandwritingMathRecognizer
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.core.recognition.StrokeGrouper
import com.bsoft.bhandbook.domain.editor.CanvasEditor
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Scale tests. Budgets are generous (they guard against accidental O(n^2) regressions, not micro-timing).
 * Each prints its measured time so real numbers are visible in the test log.
 */
class PerformanceTest {
    private inline fun <T> timed(label: String, budgetMs: Long, block: () -> T): T {
        val t0 = System.nanoTime(); val r = block(); val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF $label: ${ms}ms (budget ${budgetMs}ms)")
        assertTrue("$label took ${ms}ms, budget ${budgetMs}ms", ms <= budgetMs)
        return r
    }

    /** A grid of expressions, each a short written sum, spaced so they stay separate. */
    private fun manyExpressions(count: Int, cols: Int = 20): List<Stroke> = (0 until count).flatMap { i ->
        Pen(i.toLong() + 1).at(100f + (i % cols) * 520f, 300f + (i / cols) * 260f).text("12+34").strokes
    }

    @Test fun hundredsOfExpressions_thousandsOfStrokes_areGroupedQuickly() {
        val strokes = manyExpressions(300)
        println("PERF strokes=${strokes.size}, points=${strokes.sumOf { it.points.size }}")
        assertTrue(strokes.size >= 1500)
        val groups = timed("group ${strokes.size} strokes", 5_000) { StrokeGrouper.group(strokes) }
        assertEquals(300, groups.size)
    }

    @Test fun drawingIntoABusyCanvas_staysResponsive() {
        var n = 0
        val ed = CanvasEditor(idGenerator = { "e${++n}" }, clock = { 0L })
        manyExpressions(150).forEach { ed.addStroke(it) }                                 // pre-fill (~800 strokes)
        val extra = Pen(999).at(100f, 20_000f).text("25+15=").strokes
        val perStroke = ArrayList<Long>()
        for (s in extra) { val t0 = System.nanoTime(); ed.addStroke(s); perStroke += (System.nanoTime() - t0) / 1_000_000 }
        println("PERF addStroke into ${ed.state.strokes.size - extra.size} strokes: max=${perStroke.max()}ms avg=${perStroke.average().toInt()}ms")
        assertTrue("slowest stroke ${perStroke.max()}ms", perStroke.max() < 250)          // well under a frame budget multiple
        assertEquals(151, ed.state.expressions.size)
    }

    @Test fun layoutsForEveryExpression_scaleLinearly() {
        val ed = CanvasEditor(idGenerator = { java.util.UUID.randomUUID().toString() }, clock = { 0L })
        manyExpressions(300).forEach { ed.addStroke(it) }
        val l = timed("layout 300 expressions", 500) { ExpressionLayout.all(ed.state.strokes, ed.state.expressions) }
        assertEquals(300, l.size)
    }

    @Test fun undoHistory_isBounded_soMemoryDoesNotGrowForever() {
        var n = 0
        val ed = CanvasEditor(idGenerator = { "e${++n}" }, clock = { 0L }, historyLimit = 500)
        manyExpressions(100).forEach { ed.addStroke(it) }                                  // ~700 strokes > limit
        var undone = 0; while (ed.undo() != null) undone++
        assertEquals(500, undone)
    }

    @Test fun rapidDrawingThenUndoAll_isFast() {
        var n = 0
        val ed = CanvasEditor(idGenerator = { "e${++n}" }, clock = { 0L }, historyLimit = 5_000)
        val strokes = manyExpressions(100)
        timed("draw ${strokes.size} strokes", 8_000) { strokes.forEach { ed.addStroke(it) } }
        timed("undo all", 3_000) { while (ed.undo() != null) { } }
        assertTrue(ed.state.strokes.isEmpty())
    }

    @Test fun continuousZoomAndPan_isCheap() {
        var t = CanvasTransform()
        timed("200k gesture steps", 1_000) {
            repeat(200_000) { i -> t = t.applyGesture(Vec2(500f + (i % 7), 800f), Vec2(((i % 5) - 2).toFloat(), 1f), if (i % 2 == 0) 1.002f else 0.998f) }
        }
        assertTrue(t.scale in CanvasTransform.MIN_SCALE..CanvasTransform.MAX_SCALE)
    }

    @Test fun largeCanvas_restorationFromStorageForm() {
        var n = 0
        val ed = CanvasEditor(idGenerator = { "e${++n}" }, clock = { 0L })
        manyExpressions(250).forEach { ed.addStroke(it) }
        val stored = timed("encode ${ed.state.strokes.size} strokes", 1_500) { CanvasCodec.toStored(ed.state) }
        val back = timed("decode", 1_500) { CanvasCodec.fromStored(stored) }
        assertEquals(ed.state.strokes.size, back.strokes.size)
        println("PERF stored bytes=${stored.strokes.sumOf { it.points.size }}")
    }

    @Test fun recognitionOfALongExpression_finishesInReasonableTime() {
        val strokes = Pen(1, sloppiness = 0.6f).text("(12+34)*56-78=").strokes
        val r = timed("recognise ${strokes.size} strokes", 3_000) { HandwritingMathRecognizer().recognize(strokes) }
        assertEquals("(12+34)×56-78=", r.expression.linear)
    }

    @Test fun recognitionRequests_canBeCancelledPromptly() {
        val strokes = Pen(1).text("12345678+12345678=").strokes
        var checks = 0
        val t0 = System.nanoTime()
        try { HandwritingMathRecognizer().recognize(strokes) { ++checks > 5 }; fail("expected cancellation") } catch (_: com.bsoft.bhandbook.core.recognition.RecognitionCancelled) {}
        println("PERF cancellation latency ${(System.nanoTime() - t0) / 1_000_000}ms after $checks checks")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
    }
}
