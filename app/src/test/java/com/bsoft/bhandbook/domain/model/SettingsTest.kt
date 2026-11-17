package com.bsoft.bhandbook.domain.model

import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    @Test fun defaultRecognitionDelay_isWithinTheSpecifiedWindow() { assertTrue(AppSettings().idleDelayMs in 800L..1200L) }


    // ---- graph settings
    @Test fun graphDefaults_areThinnerThanTheOldFixedWidth_andShowEverything() {
        val g = AppSettings().graph
        assertEquals(1f, g.lineWidth, 0f); assertTrue(g.showGrid && g.showGuides && g.showMarkerLabels && g.showLegend); assertEquals(1f, g.defaultSize, 0f)
        assertTrue("1.0 is the new, thinner baseline (2.2 -> 1.3 panel units)", GraphSettings.BASE_LINE_UNITS < 2.2f)
    }
    @Test fun graphSettings_areClampedToSensibleRanges() {
        val g = GraphSettings(lineWidth = 99f, defaultSize = -4f).normalized()
        assertEquals(GraphSettings.MAX_LINE_WIDTH, g.lineWidth, 0f); assertEquals(GraphSettings.MIN_SIZE, g.defaultSize, 0f)
        assertEquals(GraphSettings.MIN_LINE_WIDTH, GraphSettings(lineWidth = 0f).normalized().lineWidth, 0f); assertEquals(GraphSettings.MAX_SIZE, GraphSettings(defaultSize = 100f).normalized().defaultSize, 0f)
        assertEquals(1.7f, GraphSettings(lineWidth = 1.7f).normalized().lineWidth, 0f)
    }
    @Test fun nanAndInfinity_neverEscapeNormalisation() { val g = GraphSettings(lineWidth = Float.NaN, defaultSize = Float.POSITIVE_INFINITY).normalized(); assertEquals(1f, g.lineWidth, 0f); assertEquals(GraphSettings.MAX_SIZE, g.defaultSize, 0f) }
    @Test fun appSettings_keepTheirOtherValues_whenGraphSettingsChange() {
        val s = AppSettings(autoEvaluate = false, idleDelayMs = 1200L); val t = s.copy(graph = s.graph.copy(showGrid = false))
        assertFalse(t.autoEvaluate); assertEquals(1200L, t.idleDelayMs); assertFalse(t.graph.showGrid)
    }


    // ---- modes
    @Test fun anglesDefaultToDegrees_inTheApp_whileTheMathsEngineStaysNeutral() {
        assertEquals(com.bsoft.bhandbook.core.math.AngleMode.DEGREES, AppSettings().calc.angle)
        assertEquals(com.bsoft.bhandbook.core.math.AngleMode.RADIANS, com.bsoft.bhandbook.core.math.CalcSettings().angle)
        assertEquals(com.bsoft.bhandbook.core.math.ResultStyle.AUTO, AppSettings().calc.style); assertNull(AppSettings().calc.places)
    }
    @Test fun changingTheModes_keepsEverythingElse() { val s = AppSettings(idleDelayMs = 1100L); val t = s.copy(calc = s.calc.copy(places = 2)); assertEquals(1100L, t.idleDelayMs); assertEquals(2, t.calc.places) }


    @Test fun theTipsAreShownUntilTheyAreDismissed() { assertFalse(AppSettings().tipsSeen); assertTrue(AppSettings().copy(tipsSeen = true).tipsSeen) }
}
