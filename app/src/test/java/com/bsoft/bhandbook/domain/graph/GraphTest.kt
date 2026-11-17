package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.MathStatement
import com.bsoft.bhandbook.core.math.ParseResult
import org.junit.Assert.*
import org.junit.Test

class GraphTest {
    private val parser = DefaultMathParser()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun spec(vararg src: String): GraphSpec? = GraphBuilder.build(src.map { st(it) })
    private fun data(vararg src: String): GraphData = GraphModel.layout(spec(*src)!!)
    private fun curveAt(s: GraphSpec, x: Double, i: Int = 0): Double = (s.series[i] as GraphSeries.Curve).valueAt(x)

    // ---------------------------------------------------------------- building curves from equations
    @Test fun explicitFunction_y_equals_fx() {
        val s = spec("y=2x+1")!!; assertEquals(1, s.series.size); assertEquals("y = 2x + 1", s.series[0].label)
        assertEquals(1.0, curveAt(s, 0.0), 1e-9); assertEquals(7.0, curveAt(s, 3.0), 1e-9)
    }
    @Test fun functionWrittenTheOtherWayRound() { assertEquals(5.0, curveAt(spec("2x+1=y")!!, 2.0), 1e-9) }
    @Test fun linearEquationInTwoUnknowns_isSolvedForY() {
        val s = spec("2x+y=4")!!; assertEquals("y = -2x + 4", s.series[0].label); assertEquals(-2.0, curveAt(s, 3.0), 1e-9)
        assertEquals("y = 0.5x - 1.5", spec("x-2y=3")!!.series[0].label)
    }
    @Test fun verticalLine() { val s = spec("x=3")!!; assertEquals(GraphSeries.VerticalLine("x = 3", 3.0), s.series[0]); assertTrue(spec("2x+0y=6")!!.series[0] is GraphSeries.VerticalLine) }
    @Test fun horizontalLine() { assertEquals(3.0, curveAt(spec("y=3")!!, 12.0), 1e-9) }
    @Test fun plainExpressionInX_isGraphedAsYequalsFx() {
        val s = spec("x^2-4")!!; assertEquals("y = x^2 - 4", s.series[0].label); assertEquals(0.0, curveAt(s, 2.0), 1e-9); assertEquals(-4.0, curveAt(s, 0.0), 1e-9)
        assertEquals(1.0, curveAt(spec("sin(0)+cos(x)*0+1")!!, 0.0), 1e-9)
    }
    @Test fun oneUnknownEquation_plotsBothSidesAndMarksTheRoot() {
        val s = spec("2x+5=15")!!; assertEquals(2, s.series.size)
        assertEquals(1, s.markers.size); assertEquals(5.0, s.markers[0].x, 1e-6); assertEquals(15.0, s.markers[0].y, 1e-6); assertEquals("(5, 15)", s.markers[0].label)
    }
    @Test fun quadraticEquation_marksBothRoots() {
        val xs = spec("x^2=4")!!.markers.map { it.x }.sorted(); assertEquals(2, xs.size); assertEquals(-2.0, xs[0], 1e-6); assertEquals(2.0, xs[1], 1e-6)
    }
    @Test fun aSystem_plotsEveryEquation_andMarksTheIntersection() {
        val s = spec("x+y=5", "x-y=1")!!; assertEquals(2, s.series.size)
        assertEquals(1, s.markers.size); assertEquals(3.0, s.markers[0].x, 1e-6); assertEquals(2.0, s.markers[0].y, 1e-6); assertEquals("(3, 2)", s.markers[0].label)
    }
    @Test fun aLineAndAVerticalLine_intersect() {
        val m = spec("y=2x", "x=3")!!.markers.single(); assertEquals(3.0, m.x, 1e-9); assertEquals(6.0, m.y, 1e-6)
    }
    @Test fun parallelLines_haveNoIntersection() { assertTrue(spec("y=x+1", "y=x+2")!!.markers.isEmpty()) }
    @Test fun nonLinearEquationsInXAndY_areImplicitCurves() {
        val c = spec("x^2+y^2=25")!!.series.single() as GraphSeries.Implicit; assertEquals("x^2 + y^2 = 25", c.label); assertEquals(0.0, c.valueAt(3.0, 4.0), 1e-9); assertTrue(c.valueAt(0.0, 0.0) < 0)
        assertTrue(spec("x*y=1")!!.series.single() is GraphSeries.Implicit); assertTrue(spec("x=y^2")!!.series.single() is GraphSeries.Implicit); assertTrue(spec("y^2=4")!!.series.single() is GraphSeries.Implicit)
    }
    @Test fun anExpressionInXAndYWithoutAnEquation_cannotBeGraphed() { assertNull(spec("x^2+y^2")) }
    @Test fun statementsWithOtherLettersOrNoVariable_cannotBeGraphed() { assertNull(spec("a+b=3")); assertNull(spec("5+3")); assertNull(spec("2z=4")) }
    @Test fun canGraph_matchesBuild() { assertTrue(GraphBuilder.canGraph(listOf(st("y=x")))); assertTrue(GraphBuilder.canGraph(listOf(st("x^2+y^2=1")))); assertFalse(GraphBuilder.canGraph(listOf(st("x^2+y^2")))) }

    // ---------------------------------------------------------------- sampling and the viewport
    @Test fun defaultViewport_isMinusTenToTen_inX_andContainsTheCurveInY() {
        val d = data("y=2x+1"); assertEquals(-10.0, d.viewport.xMin, 1e-9); assertEquals(10.0, d.viewport.xMax, 1e-9)
        assertTrue(d.viewport.yMin <= -19.0 && d.viewport.yMax >= 21.0)
    }
    @Test fun viewportIncludesTheIntersection_evenWhenItIsFarFromTheOrigin() {
        val d = data("x+y=50", "x-y=10"); val m = d.markers.single()                      // (30, 20)
        assertTrue(m.x > d.viewport.xMin && m.x < d.viewport.xMax && m.y > d.viewport.yMin && m.y < d.viewport.yMax)
    }
    @Test fun aConstantFunction_getsANonDegenerateYRange() { val d = data("y=3"); assertTrue(d.viewport.yMax - d.viewport.yMin > 1.0); assertTrue(3.0 in d.viewport.yMin..d.viewport.yMax) }
    @Test fun aVerticalLine_isInsideTheViewport() { val d = data("x=37"); assertEquals(1, d.verticalLines.size); assertTrue(37.0 in d.viewport.xMin..d.viewport.xMax) }
    @Test fun curvesAreSampledAcrossTheWholeViewport() {
        val seg = data("y=x").curves[0].segments; assertEquals(1, seg.size)
        assertEquals(-10.0, seg[0].first().x, 1e-9); assertEquals(10.0, seg[0].last().x, 1e-9); assertTrue(seg[0].size >= 200)
    }
    @Test fun asymptotes_breakTheCurve_insteadOfDrawingAVerticalJoin() {
        val seg = data("tan(x)").curves[0].segments; assertTrue("segments=${seg.size}", seg.size >= 4)
        val range = data("tan(x)").viewport.let { it.yMax - it.yMin }
        for (s in seg) for (i in 1 until s.size) assertTrue(Math.abs(s[i].y - s[i - 1].y) < range)
    }
    @Test fun reciprocal_breaksAtZero() { val seg = data("1/x").curves[0].segments; assertEquals(2, seg.size); assertTrue(seg[0].all { it.x < 0 } && seg[1].all { it.x > 0 }) }
    @Test fun squareRoot_hasNoPointsWhereItIsUndefined() { val seg = data("sqrt(x)").curves[0].segments; assertTrue(seg.flatten().all { it.x >= 0 }); assertTrue(seg.isNotEmpty()) }
    @Test fun everySampledPointIsFinite() {
        for (f in listOf("tan(x)", "1/x", "sqrt(x)", "log(x)", "2^x", "1/(x^2-4)")) for (s in data(f).curves.flatMap { it.segments }) assertTrue(f, s.all { it.x.isFinite() && it.y.isFinite() })
    }
    @Test fun samplingIsFast() {
        val t0 = System.nanoTime(); repeat(10) { data("y=x^2", "y=sin(x)", "y=2x+1", "y=1/x") }; val ms = (System.nanoTime() - t0) / 10_000_000
        println("PERF graph of 4 curves: $ms ms"); assertTrue("$ms ms", ms < 150)
    }

    // ---------------------------------------------------------------- ticks and labels
    @Test fun ticks_areNiceMultiples_includeZero_andAreFewEnough() {
        val t = NiceTicks.compute(-10.0, 10.0); assertTrue(t.size in 4..13); assertTrue(t.any { it.value == 0.0 })
        val step = t[1].value - t[0].value; assertTrue("step $step", step in listOf(1.0, 2.0, 2.5, 5.0, 10.0))
        for (x in t) assertEquals(0.0, Math.IEEEremainder(x.value, step), 1e-9)
    }
    @Test fun ticks_forSmallAndLargeRanges() {
        assertEquals(listOf("0", "0.2", "0.4", "0.6", "0.8", "1"), NiceTicks.compute(0.0, 1.0).map { it.label })
        assertTrue(NiceTicks.compute(0.0, 2_000_000.0).all { it.label.isNotBlank() })
        assertTrue(NiceTicks.compute(-1e-7, 1e-7).size >= 2)
    }
    @Test fun tickLabels_haveNoFloatingPointNoise() { for (t in NiceTicks.compute(-1.0, 1.0)) assertFalse(t.label, t.label.contains("0000") || t.label.contains("9999")) }
    @Test fun markerLabels_useAtMostThreeSignificantDigits() {
        assertEquals("(3.33, 2.33)", GraphBuilder.build(listOf(st("x/2+y=4"), st("x-y=1")))!!.markers.single().label)
        assertEquals("(-1.5, 0)", GraphMarker.label(-1.5, 0.0))
    }
}
