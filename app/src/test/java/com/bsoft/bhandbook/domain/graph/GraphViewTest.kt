package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.domain.solver.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class GraphViewTest {
    private val parser = DefaultMathParser()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun spec(vararg src: String) = GraphBuilder.build(src.map { st(it) })!!
    private val view = GraphViewport(-10.0, 10.0, -7.2, 7.2)

    // ---------------------------------------------------------------- plot geometry (shared by the renderer and the gestures)
    private val rect = WorldRect(100f, 200f, 100f + 1600f, 200f + 1152f)
    @Test fun plotArea_usesTheSameMarginsAsThePanelDrawing() {
        val a = GraphPlot.area(rect, view, equalScale = false); val label = rect.width / com.bsoft.bhandbook.domain.editor.ExpressionLayout.GRAPH_WIDTH_EM * 0.5f
        assertEquals(rect.left + label * 3.6f, a.left, 1e-3f); assertEquals(rect.right - label, a.right, 1e-3f); assertEquals(rect.top + label, a.top, 1e-3f); assertEquals(rect.bottom - label * 2.2f, a.bottom, 1e-3f)
    }
    @Test fun plotArea_mapsTheViewportCornersToThePlotCorners_andBack() {
        val a = GraphPlot.area(rect, view, false)
        assertEquals(a.left, a.screenX(-10.0), 1e-3f); assertEquals(a.right, a.screenX(10.0), 1e-3f); assertEquals(a.bottom, a.screenY(-7.2), 1e-3f); assertEquals(a.top, a.screenY(7.2), 1e-3f)
        for (x in listOf(-9.3, 0.0, 4.4)) assertEquals(x, a.graphX(a.screenX(x)), 1e-3); for (y in listOf(-6.0, 0.0, 3.3)) assertEquals(y, a.graphY(a.screenY(y)), 1e-3)
    }
    @Test fun plotArea_withEqualScalesKeepsOneScaleOnBothAxes() {
        val a = GraphPlot.area(rect, GraphViewport(-5.0, 5.0, -10.0, 10.0), equalScale = true)
        val dx = a.screenX(1.0) - a.screenX(0.0); val dy = a.screenY(0.0) - a.screenY(1.0); assertEquals(dx, dy, 1e-3f)
        assertEquals((a.left + a.right) / 2f, a.screenX(0.0), 1e-3f); assertEquals((a.top + a.bottom) / 2f, a.screenY(0.0), 1e-3f)
        assertEquals(0.25, a.graphX(a.screenX(0.25)), 1e-3)
    }
    @Test fun graphUnitsPerWorldUnit_matchTheMapping() {
        for (eq in listOf(false, true)) { val a = GraphPlot.area(rect, view, eq); assertEquals(a.xPerWorld * 10, a.graphX(a.screenX(0.0) + 10f) - a.graphX(a.screenX(0.0)), 1e-5); assertEquals(a.yPerWorld * 10, a.graphY(a.screenY(0.0)) - a.graphY(a.screenY(0.0) + 10f), 1e-5) }
    }

    // ---------------------------------------------------------------- zooming and panning a viewport
    @Test fun zoomingAboutAPoint_keepsThatPointWhereItIs() {
        val z = view.zoomedAbout(2.0, 4.0, 1.0)
        assertEquals((4.0 - view.xMin) / (view.xMax - view.xMin), (4.0 - z.xMin) / (z.xMax - z.xMin), 1e-12); assertEquals((1.0 - view.yMin) / (view.yMax - view.yMin), (1.0 - z.yMin) / (z.yMax - z.yMin), 1e-12)
        assertEquals((view.xMax - view.xMin) / 2, z.xMax - z.xMin, 1e-12)
    }
    @Test fun zoomingInThenOutByTheSameFactor_isTheIdentity() { val z = view.zoomedAbout(3.0, -2.0, 5.0).zoomedAbout(1.0 / 3.0, -2.0, 5.0); assertEquals(view.xMin, z.xMin, 1e-9); assertEquals(view.yMax, z.yMax, 1e-9) }
    @Test fun zoomingIsBounded_neverDegenerate() {
        var z = view; repeat(200) { z = z.zoomedAbout(10.0, 0.0, 0.0) }; assertTrue(z.xMax - z.xMin >= GraphViewport.MIN_SPAN * 0.999 && z.isValid)
        z = view; repeat(200) { z = z.zoomedAbout(0.1, 0.0, 0.0) }; assertTrue(z.xMax - z.xMin <= GraphViewport.MAX_SPAN * 1.001 && z.isValid)
        assertEquals(view, view.zoomedAbout(Double.NaN, 0.0, 0.0)); assertEquals(view, view.zoomedAbout(0.0, 0.0, 0.0)); assertEquals(view, view.zoomedAbout(-2.0, 0.0, 0.0)); assertEquals(view, view.zoomedAbout(2.0, Double.NaN, 0.0))
    }
    @Test fun panning_shiftsBothEnds() { val p = view.panned(3.0, -1.5); assertEquals(-7.0, p.xMin, 1e-12); assertEquals(13.0, p.xMax, 1e-12); assertEquals(-8.7, p.yMin, 1e-12); assertEquals(view.xMax - view.xMin, p.xMax - p.xMin, 1e-12); assertEquals(view, view.panned(Double.NaN, 1.0)) }
    @Test fun validity() { assertTrue(view.isValid); assertFalse(GraphViewport(1.0, 1.0, 0.0, 1.0).isValid); assertFalse(GraphViewport(2.0, 1.0, 0.0, 1.0).isValid); assertFalse(GraphViewport(Double.NaN, 1.0, 0.0, 1.0).isValid); assertFalse(GraphViewport(0.0, 1e300, 0.0, 1.0).isValid) }

    // ---------------------------------------------------------------- layout honours a chosen viewport
    @Test fun layout_withAView_samplesExactlyThatRange() {
        val v = GraphViewport(2.0, 4.0, 4.0, 10.0); val d = GraphModel.layout(spec("y=2x+1"), v)
        assertEquals(v, d.viewport); val pts = d.curves[0].segments.flatten(); assertEquals(2.0, pts.first().x, 1e-9); assertEquals(4.0, pts.last().x, 1e-9); assertEquals(5.0, pts.first().y, 1e-9)
    }
    @Test fun layout_withoutAView_isUnchanged() { val s = spec("y=2x+1"); assertEquals(GraphModel.layout(s).viewport, GraphModel.layout(s, null).viewport) }
    @Test fun layout_withAView_hasTicksForThatRange() { val d = GraphModel.layout(spec("y=x"), GraphViewport(100.0, 110.0, 100.0, 110.0)); assertTrue(d.xTicks.all { it.value in 100.0..110.0 } && d.xTicks.isNotEmpty()) }
    @Test fun layout_withAViewOnAnImplicitCurve_tracesItThere() {
        val d = GraphModel.layout(spec("x^2+y^2=25"), GraphViewport(2.0, 6.0, 0.0, 4.0)); val pts = d.curves[0].segments.flatten()
        assertTrue(pts.isNotEmpty()); assertTrue(pts.all { it.x in 1.99..6.01 && it.y in -0.01..4.01 }); assertTrue(pts.all { abs(hypot(it.x, it.y) - 5.0) < 0.05 })
    }
    @Test fun layout_withAnInvalidView_fallsBackToTheAutomaticFraming() { assertEquals(GraphModel.layout(spec("y=x")).viewport, GraphModel.layout(spec("y=x"), GraphViewport(1.0, 1.0, 0.0, 0.0)).viewport) }
    @Test fun layout_zoomedFarIn_stillDrawsTheCurve() { val d = GraphModel.layout(spec("y=x^2"), GraphViewport(1.0, 1.001, 0.9, 1.1)); assertTrue(d.curves[0].segments.flatten().size > 100) }

    // ---------------------------------------------------------------- trace
    private fun data(vararg src: String, v: GraphViewport? = null) = GraphModel.layout(spec(*src), v)
    @Test fun trace_findsThePointOnALine() { val d = data("y=2x+1"); val t = GraphTrace.at(d, 2.0, 5.3, 0.3, 0.3)!!; assertEquals(2.0, t.x, 0.2); assertEquals(5.0, t.y, 0.25); assertEquals(2.0 * t.x + 1, t.y, 0.02); assertFalse(t.snapped); assertEquals(0, t.curve) }
    @Test fun trace_snapsToAnIntersection() { val d = data("y=x", "y=2x+3"); val t = GraphTrace.at(d, -2.9, -2.8, 0.3, 0.3)!!; assertTrue(t.snapped); assertEquals(-3.0, t.x, 1e-9); assertEquals(-3.0, t.y, 1e-9) }
    @Test fun trace_picksTheNearerOfTwoCurves() { val d = data("y=x", "y=-x+8"); assertEquals(0, GraphTrace.at(d, 1.0, 1.3, 0.1, 0.1)!!.curve); assertEquals(1, GraphTrace.at(d, 1.0, 6.8, 0.1, 0.1)!!.curve) }
    @Test fun trace_followsAVerticalLine() { val d = data("x=3", "y=x"); val t = GraphTrace.at(d, 3.05, -4.0, 0.2, 0.2)!!; assertEquals(3.0, t.x, 1e-9); assertTrue(t.label.contains("x = 3")) }
    @Test fun trace_onACircle_staysOnTheCircle() { val d = data("x^2+y^2=9"); val t = GraphTrace.at(d, 2.2, 2.4, 0.3, 0.3)!!; assertEquals(3.0, hypot(t.x, t.y), 0.03) }
    @Test fun trace_farFromEverything_findsNothing() { assertNull(GraphTrace.at(data("y=2x+1", v = GraphViewport(-10.0, 10.0, -7.2, 7.2)), -9.0, 7.0, 0.1, 0.1)) }
    @Test fun trace_neverCrosses_anAsymptoteGap() { val d = data("y=1/x"); val t = GraphTrace.at(d, 0.02, 3.0, 0.2, 0.2); if (t != null) assertEquals(1.0, t.x * t.y, 0.2) }
    @Test fun trace_ofBadInput_isNull() { val d = data("y=x"); for ((x, y) in listOf(Double.NaN to 0.0, 0.0 to Double.NaN, Double.POSITIVE_INFINITY to 1.0)) assertNull(GraphTrace.at(d, x, y, 0.1, 0.1)); assertNull(GraphTrace.at(d, 1.0, 1.0, Double.NaN, 0.1)) }
    @Test fun trace_readoutShowsBothCoordinates() { val t = GraphTrace.at(data("y=2x+1"), 2.0, 5.0, 0.2, 0.2)!!; assertEquals("(2, 5)", GraphTrace.readout(t)); assertEquals("x = 3", GraphTrace.readout(TracePoint(3.0, 1.0, -2, "x = 3", false))) }
    @Test fun trace_sameSpotIsRecognised() { val a = TracePoint(1.0, 2.0, 0, "y", false); assertTrue(GraphTrace.same(a, TracePoint(1.01, 2.01, 0, "y", false), 0.1, 0.1)); assertFalse(GraphTrace.same(a, TracePoint(2.0, 2.0, 0, "y", false), 0.1, 0.1)) }
    @Test fun trace_inAZoomedView_isAccurate() { val v = GraphViewport(1.99, 2.01, 3.9, 4.1); val t = GraphTrace.at(GraphModel.layout(spec("y=x^2"), v), 2.0, 4.02, 0.002, 0.01)!!; assertEquals(t.x * t.x, t.y, 1e-4) }
}
