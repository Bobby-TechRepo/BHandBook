package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CanvasViewTest {
    private fun ink(id: String, x: Float, y: Float, w: Float = 120f, h: Float = 60f, expr: String? = id) =
        Stroke("s_$id", listOf(StrokePoint(x, y), StrokePoint(x + w, y + h)), 4f, 0, expr)
    private fun expr(id: String) = id to ExpressionObject(id, recognized = "1+1=", result = "2", status = ProcessingStatus.EVALUATED, resultInline = true)
    private fun fit(strokes: List<Stroke>, w: Float = 1000f, h: Float = 700f, graphs: Set<String> = emptySet()): CanvasTransform {
        val ex = strokes.mapNotNull { it.expressionId }.distinct().associate { expr(it) }
        val layouts = ExpressionLayout.all(strokes, ex, graphs = graphs, graphable = graphs)
        return CanvasView.fitAll(strokes, layouts, w, h)
    }
    private fun screenBounds(t: CanvasTransform, s: Stroke): List<Vec2> = s.points.map { t.worldToScreen(Vec2(it.x, it.y)) }

    @Test fun anEmptyCanvas_showsTheDefaultView() { assertEquals(CanvasTransform(), CanvasView.fitAll(emptyList(), emptyMap(), 1000f, 700f)) }
    @Test fun everythingIsVisibleAfterwards_wherever_itIs() {
        for ((x, y) in listOf(0f to 0f, 50_000f to -30_000f, -1e6f to 2e5f, 300f to 9000f)) {
            val strokes = listOf(ink("a", x, y), ink("b", x + 1200f, y + 400f), ink("c", x - 800f, y + 900f))
            val t = fit(strokes)
            for (s in strokes) for (p in screenBounds(t, s)) assertTrue("($x,$y): $p", p.x in 0f..1000f && p.y in 0f..700f)
        }
    }
    @Test fun theContentIsCentred() {
        val strokes = listOf(ink("a", 4000f, 4000f), ink("b", 5000f, 4500f)); val t = fit(strokes)
        val all = strokes.flatMap { screenBounds(t, it) }; val cx = (all.minOf { it.x } + all.maxOf { it.x }) / 2; val cy = (all.minOf { it.y } + all.maxOf { it.y }) / 2
        assertEquals(500f, cx, 120f); assertEquals(350f, cy, 120f)                       // the answer drawn beside the ink shifts the centre slightly
    }
    @Test fun aSingleSmallThing_isNotBlownUpToFillTheScreen() { val t = fit(listOf(ink("a", 100f, 100f, 3f, 3f))); assertTrue(t.scale <= CanvasView.MAX_FIT_SCALE + 1e-4f) }
    @Test fun aHugeSpread_zoomsOutAsFarAsAllowedButNeverBeyond() {
        val t = fit(listOf(ink("a", 0f, 0f), ink("b", 3_000_000f, 2_000_000f)))
        assertTrue(t.scale >= CanvasTransform.MIN_SCALE - 1e-9f); assertTrue(t.scale.isFinite())
    }
    @Test fun aWideSpread_isFittedByWidth_aTallOneByHeight() {
        val wide = fit(listOf(ink("a", 0f, 0f), ink("b", 8000f, 10f))); val tall = fit(listOf(ink("a", 0f, 0f), ink("b", 10f, 8000f)))
        assertEquals(wide.scale * 8000f, 1000f, 200f); assertEquals(tall.scale * 8000f, 700f, 150f)
    }
    @Test fun aGraphPanelIsPartOfTheContent() {
        val strokes = listOf(ink("a", 0f, 0f, 200f, 60f).copy(expressionId = "a"), ink("g", 100f, 60f, 200f, 60f).copy(expressionId = "g"))
        val ex = mapOf(expr("a"), "g" to ExpressionObject("g", recognized = "y=x", status = ProcessingStatus.EVALUATED))
        val layouts = ExpressionLayout.all(strokes, ex, graphs = setOf("g"), graphable = setOf("g"))
        val panel = layouts.getValue("g").graphRect!!; val t = CanvasView.fitAll(strokes, layouts, 1000f, 700f)
        val tl = t.worldToScreen(Vec2(panel.left, panel.top)); val br = t.worldToScreen(Vec2(panel.right, panel.bottom))
        assertTrue("$tl $br", tl.x >= 0f && tl.y >= 0f && br.x <= 1000f && br.y <= 700f)
    }
    @Test fun anInlineAnswer_isPartOfTheContent() {
        val strokes = listOf(ink("a", 0f, 0f, 200f, 60f)); val ex = mapOf(expr("a")); val layouts = ExpressionLayout.all(strokes, ex)
        val hit = layouts.getValue("a").hitBounds; val t = CanvasView.fitAll(strokes, layouts, 1000f, 700f)
        val br = t.worldToScreen(Vec2(hit.right, hit.bottom)); assertTrue("$br", br.x <= 1000f && br.y <= 700f)
    }
    @Test fun aDegenerateViewport_neverProducesNaN() {
        for ((w, h) in listOf(0f to 0f, 0f to 700f, -5f to 10f, 1f to 1f)) { val t = fit(listOf(ink("a", 0f, 0f)), w, h); assertTrue("$w x $h: $t", t.scale.isFinite() && t.offset.x.isFinite() && t.offset.y.isFinite()) }
    }
    @Test fun nonFiniteInk_isIgnored() { val bad = Stroke("bad", listOf(StrokePoint(Float.NaN, 1f), StrokePoint(2f, Float.POSITIVE_INFINITY)), 3f, 0, null); val t = fit(listOf(bad, ink("a", 0f, 0f))); assertTrue(t.scale.isFinite() && t.offset.x.isFinite()) }
}
