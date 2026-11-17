package com.bsoft.bhandbook.domain.graph

import org.junit.Assert.*
import org.junit.Test

class GraphGuidesTest {
    private fun m(x: Double, y: Double) = GraphMarker(GraphMarker.label(x, y), x, y)
    private val around = GraphViewport(-10.0, 10.0, -8.0, 8.0)

    @Test fun aPoint_getsAVerticalGuideToTheXAxis_andAHorizontalGuideToTheYAxis_withItsValues() {
        val g = GraphGuides.of(listOf(m(3.0, 2.0)), around).single()
        assertEquals(3.0, g.x, 0.0); assertEquals(2.0, g.y, 0.0)
        assertEquals("the vertical guide ends on the x axis (y = 0)", 0.0, g.xAxisY, 0.0); assertEquals("the horizontal guide ends on the y axis (x = 0)", 0.0, g.yAxisX, 0.0)
        assertEquals("3", g.xLabel); assertEquals("2", g.yLabel); assertTrue(g.hasGuides)
    }
    @Test fun valuesUseAtMostThreeSignificantDigits() { val g = GraphGuides.of(listOf(m(10.0 / 3, 7.0 / 3)), around).single(); assertEquals("3.33", g.xLabel); assertEquals("2.33", g.yLabel) }
    @Test fun negativeValues() { val g = GraphGuides.of(listOf(m(-3.5355, -3.5355)), around).single(); assertEquals("-3.54", g.xLabel); assertEquals("-3.54", g.yLabel) }
    @Test fun whenAnAxisIsOffScreen_theGuideEndsOnTheNearestEdge_whereTheValueIsPrinted() {
        val v = GraphViewport(10.0, 30.0, 5.0, 25.0)                                   // neither axis is visible
        val g = GraphGuides.of(listOf(m(20.0, 15.0)), v).single()
        assertEquals("bottom edge", 5.0, g.xAxisY, 0.0); assertEquals("left edge", 10.0, g.yAxisX, 0.0)
        val w = GraphViewport(-30.0, -10.0, -25.0, -5.0); val h = GraphGuides.of(listOf(m(-20.0, -15.0)), w).single()
        assertEquals("axis is above the view: nearest edge is the top", -5.0, h.xAxisY, 0.0); assertEquals("axis is right of the view", -10.0, h.yAxisX, 0.0)
    }
    @Test fun aPointOnAnAxis_getsNoGuides_butItsValueIsStillPrintedOnTheAxis() {
        val onX = GraphGuides.of(listOf(m(4.0, 0.0)), around).single(); assertFalse(onX.hasGuides); assertEquals("4", onX.xLabel); assertNull(onX.yLabel)
        val onY = GraphGuides.of(listOf(m(0.0, 3.0)), around).single(); assertFalse(onY.hasGuides); assertNull(onY.xLabel); assertEquals("3", onY.yLabel)
        val origin = GraphGuides.of(listOf(m(0.0, 0.0)), around).single(); assertFalse(origin.hasGuides); assertNull(origin.xLabel); assertNull(origin.yLabel)
    }
    @Test fun aPointOutsideTheView_hasNoGuide() { assertTrue(GraphGuides.of(listOf(m(50.0, 2.0)), around).isEmpty()); assertTrue(GraphGuides.of(listOf(m(1.0, -80.0)), around).isEmpty()) }
    @Test fun everyMarkerGetsItsOwnGuide_inOrder() { assertEquals(listOf(-3.0, 3.0), GraphGuides.of(listOf(m(-3.0, 4.0), m(3.0, -4.0)), around).map { it.x }) }
    @Test fun noMarkers_noGuides() { assertTrue(GraphGuides.of(emptyList(), around).isEmpty()) }
    @Test fun aLabelIsNotRepeatedForTwoPointsAtTheSameX() {
        val gs = GraphGuides.of(listOf(m(3.0, 4.0), m(3.0, -4.0)), around); assertEquals("3", gs[0].xLabel); assertNull("same x already labelled", gs[1].xLabel)
    }
}
