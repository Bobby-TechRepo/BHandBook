package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ParseResult
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ImplicitGraphTest {
    private val parser = DefaultMathParser()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun data(vararg src: String): GraphData = GraphModel.layout(GraphBuilder.build(src.map { st(it) })!!)
    private fun points(d: GraphData, i: Int = 0) = d.curves[i].segments.flatten()

    // ---------------------------------------------------------------- shape
    @Test fun aCircle_isOneClosedLoop_onTheCircle() {
        val d = data("x^2+y^2=25"); val seg = d.curves[0].segments
        assertEquals(1, seg.size); assertTrue("closed", hypot(seg[0].first().x - seg[0].last().x, seg[0].first().y - seg[0].last().y) < 0.2)
        for (p in points(d)) assertEquals("(${p.x}, ${p.y})", 5.0, hypot(p.x, p.y), 0.1)
        assertTrue(seg[0].size > 100)
    }
    @Test fun theCircleIsFramed_withEqualScale_soItLooksRound() {
        val d = data("x^2+y^2=25"); val v = d.viewport
        assertTrue(v.xMin <= -5 && v.xMax >= 5 && v.yMin <= -5 && v.yMax >= 5); assertTrue(d.equalScale)
        assertEquals("y span / x span matches the panel", GraphModel.PLOT_ASPECT, (v.yMax - v.yMin) / (v.xMax - v.xMin), 0.02)
        assertTrue("not absurdly zoomed out", v.xMax - v.xMin < 25)
    }
    @Test fun aHugeCircle_isFoundAndFramed() { val d = data("x^2+y^2=250000"); assertTrue(d.viewport.xMax >= 500 && d.viewport.xMax < 2000); assertEquals(500.0, hypot(points(d).first().x, points(d).first().y), 6.0) }
    @Test fun aTinyCircle_isFoundAndFramed() { val d = data("x^2+y^2=0.04"); assertTrue(d.viewport.xMax >= 0.2 && d.viewport.xMax < 2.0); assertTrue(points(d).isNotEmpty()) }
    @Test fun aCircleFarFromTheOrigin() { val d = data("(x-50)^2+(y-40)^2=25"); assertTrue(50.0 in d.viewport.xMin..d.viewport.xMax && 40.0 in d.viewport.yMin..d.viewport.yMax); assertTrue(points(d).isNotEmpty()) }
    @Test fun anEllipse() {
        val d = data("x^2/4+y^2/9=1")
        for (p in points(d)) assertEquals(1.0, p.x * p.x / 4 + p.y * p.y / 9, 0.05); assertTrue(d.viewport.yMax >= 3)
    }
    @Test fun aHyperbola_hasTwoBranches() {
        val d = data("x*y=1"); assertTrue("${d.curves[0].segments.size}", d.curves[0].segments.size >= 2)
        for (p in points(d)) assertEquals(1.0, p.x * p.y, 0.1)
    }
    @Test fun aSidewaysParabola() { val d = data("x=y^2"); for (p in points(d)) assertEquals(p.x, p.y * p.y, 0.1); assertTrue(points(d).any { it.y > 0 } && points(d).any { it.y < 0 }) }
    @Test fun twoHorizontalLines_fromYSquared() { val d = data("y^2=4"); assertEquals(setOf(-2.0, 2.0), points(d).map { Math.round(it.y).toDouble() }.toSet()) }
    @Test fun noRealPoints_isReported_notDrawnAsNothingSilently() {
        val d = data("x^2+y^2=-1"); assertTrue(points(d).isEmpty()); assertTrue(d.note!!.contains("No real", ignoreCase = true))
    }

    // ---------------------------------------------------------------- the curve is smooth and joined up
    @Test fun polylines_haveNoJumps() {
        val d = data("x^2+y^2=25"); val cell = (d.viewport.xMax - d.viewport.xMin) / GraphModel.IMPLICIT_GRID
        for (s in d.curves[0].segments) for (i in 1 until s.size) assertTrue("jump ${hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y)} vs cell $cell", hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y) <= 1.5 * cell)
    }
    @Test fun everyPointIsFinite() { for (f in listOf("x^2+y^2=25", "x*y=1", "y^2=x", "sin(x)=y^2")) for (p in points(data(f))) assertTrue(f, p.x.isFinite() && p.y.isFinite()) }

    // ---------------------------------------------------------------- mixed with other graphs
    @Test fun aCircleAndALine_markBothIntersections() {
        val m = GraphBuilder.build(listOf(st("x^2+y^2=25"), st("y=x")))!!.markers.sortedBy { it.x }
        assertEquals(2, m.size); assertEquals(-3.5355, m[0].x, 1e-3); assertEquals(-3.5355, m[0].y, 1e-3); assertEquals(3.5355, m[1].x, 1e-3); assertEquals("(3.54, 3.54)", m[1].label)
    }
    @Test fun aCircleAndAVerticalLine() { val m = GraphBuilder.build(listOf(st("x^2+y^2=25"), st("x=3")))!!.markers.sortedBy { it.y }; assertEquals(2, m.size); assertEquals(-4.0, m[0].y, 1e-6); assertEquals(4.0, m[1].y, 1e-6) }
    @Test fun aCircleAndAnImplicitCurve_haveNoNumericMarkers_yet() { assertTrue(GraphBuilder.build(listOf(st("x^2+y^2=25"), st("x*y=1")))!!.markers.isEmpty()) }
    @Test fun aCircleAndALine_areFramedTogether_withTheIntersectionsVisible() {
        val d = data("x^2+y^2=25", "y=x"); for (m in d.markers) assertTrue(m.x > d.viewport.xMin && m.x < d.viewport.xMax && m.y > d.viewport.yMin && m.y < d.viewport.yMax)
        assertEquals(2, d.curves.size)
    }
    @Test fun anExplicitGraphIsNotMarkedEqualScale() { assertFalse(data("y=2x+1").equalScale) }

    // ---------------------------------------------------------------- speed
    @Test fun aCircleIsFast() {
        val spec = GraphBuilder.build(listOf(st("x^2+y^2=25")))!!; GraphModel.layout(spec)
        val t0 = System.nanoTime(); repeat(5) { GraphModel.layout(spec) }; val ms = (System.nanoTime() - t0) / 5_000_000
        println("PERF implicit circle layout: $ms ms"); assertTrue("$ms ms", ms < 400)
    }
}
