package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.math.AngleMode
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ParseResult
import org.junit.Assert.*
import org.junit.Test

class GraphAngleTest {
    private val parser = DefaultMathParser()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun spec(angle: AngleMode, vararg src: String) = GraphBuilder.build(src.map { st(it) }, angle)!!
    private fun data(angle: AngleMode, vararg src: String) = GraphModel.layout(spec(angle, *src))
    private val D = AngleMode.DEGREES; private val R = AngleMode.RADIANS; private val G = AngleMode.GRADIANS

    @Test fun theCurveIsSampledInTheChosenUnit() {
        assertEquals(0.5, (spec(D, "y=sin(x)").series[0] as GraphSeries.Curve).valueAt(30.0), 1e-12)
        assertEquals(Math.sin(30.0), (spec(R, "y=sin(x)").series[0] as GraphSeries.Curve).valueAt(30.0), 1e-12)
        assertEquals(Math.sin(Math.toRadians(27.0)), (spec(G, "y=sin(x)").series[0] as GraphSeries.Curve).valueAt(30.0), 1e-12)
    }
    @Test fun theDefaultAngleIsRadians() { assertEquals(Math.sin(30.0), (GraphBuilder.build(listOf(st("y=sin(x)")))!!.series[0] as GraphSeries.Curve).valueAt(30.0), 1e-12) }

    // ---------------------------------------------------------------- framing: a few full periods in the chosen unit
    @Test fun trigInDegrees_isFramedToShowWholeWaves() { val v = data(D, "y=sin(x)").viewport; assertEquals(-360.0, v.xMin, 1e-9); assertEquals(360.0, v.xMax, 1e-9); assertTrue(v.yMin < -1.0 && v.yMax > 1.0 && v.yMax < 2.5) }
    @Test fun trigInGradians_isFramedToShowWholeWaves() { val v = data(G, "y=cos(x)").viewport; assertEquals(-400.0, v.xMin, 1e-9); assertEquals(400.0, v.xMax, 1e-9) }
    @Test fun trigInRadians_keepsTheUsualWindow() { val v = data(R, "y=sin(x)").viewport; assertEquals(-10.0, v.xMin, 1e-9); assertEquals(10.0, v.xMax, 1e-9) }
    @Test fun nonTrigGraphs_areNotAffectedByTheAngleUnit() { for (m in listOf(D, R, G)) { val v = data(m, "y=2x+1").viewport; assertEquals(-10.0, v.xMin, 1e-9); assertEquals(10.0, v.xMax, 1e-9) } }
    @Test fun aSineWaveInDegrees_isOneContinuousCurve_withTheRightPeriod() {
        val d = data(D, "y=sin(x)"); assertEquals(1, d.curves[0].segments.size)
        val pts = d.curves[0].segments[0]; assertEquals(-360.0, pts.first().x, 1e-9); assertEquals(360.0, pts.last().x, 1e-9)
        assertEquals(1.0, pts.minByOrNull { Math.abs(it.x - 90.0) }!!.y, 0.01); assertEquals(0.0, pts.minByOrNull { Math.abs(it.x - 180.0) }!!.y, 0.02)
    }
    @Test fun tangentInDegrees_breaksAtNinetyDegrees() { val seg = data(D, "y=tan(x)").curves[0].segments; assertTrue("${seg.size}", seg.size >= 4) }
    @Test fun trigPlusOtherFunctionsStillUseTheTrigWindow() { val v = data(D, "y=sin(x)", "y=x/100").viewport; assertEquals(-360.0, v.xMin, 1e-9) }

    // ---------------------------------------------------------------- intersections of periodic graphs stay inside the window
    @Test fun sineMeetingAHorizontalLine_marksTheCrossingsInTheWindow_andDoesNotStretchTheView() {
        val d = data(D, "y=sin(x)", "y=0.5"); val xs = d.markers.map { Math.round(it.x).toInt() }.sorted()
        assertEquals(listOf(-330, -210, 30, 150), xs); assertEquals(-360.0, d.viewport.xMin, 1e-9)
        assertTrue(d.markers.all { it.x >= d.viewport.xMin && it.x <= d.viewport.xMax }); assertEquals("(30, 0.5)", d.markers.first { Math.round(it.x) == 30L }.label)
    }
    @Test fun nonPeriodicIntersections_areUnchanged() { val m = GraphBuilder.build(listOf(st("x+y=5"), st("x-y=1")), D)!!.markers.single(); assertEquals(3.0, m.x, 1e-6); assertEquals(2.0, m.y, 1e-6) }
    @Test fun usesTrig_isReportedForPeriodicFunctionsOnly() {
        assertTrue(spec(D, "y=sin(x)").usesTrig); assertTrue(spec(D, "y=2cos(x)+1").usesTrig); assertTrue(spec(D, "y=tan(x)").usesTrig); assertFalse(spec(D, "y=asin(x)").usesTrig); assertFalse(spec(D, "y=x^2").usesTrig)
    }
    @Test fun implicitCurvesAreStillFramedByTheirShape() { val v = data(D, "x^2+y^2=25").viewport; assertTrue(v.xMax < 30) }
}
