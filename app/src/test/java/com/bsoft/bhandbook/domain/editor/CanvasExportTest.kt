package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CanvasExportTest {
    private fun stroke(id: String, x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 4f, expr: String? = "e1") =
        Stroke(id, listOf(StrokePoint(x0, y0), StrokePoint(x1, y1)), width, 0, expr)
    private fun layouts(vararg s: Stroke, graphs: Boolean = false): Map<String, ExpressionLayout> =
        ExpressionLayout.all(s.toList(), mapOf("e1" to ExpressionObject("e1", result = "40", resultInline = true, status = ProcessingStatus.EVALUATED)), graphs = if (graphs) setOf("e1") else emptySet())

    // ---------------------------------------------------------------- what is exported
    @Test fun nothingOnTheCanvas_isNothingToExport() { assertNull(CanvasExport.area(emptyList(), emptyMap())) }
    @Test fun inkWithoutAnyArea_isNothingToExport() { assertNull(CanvasExport.area(listOf(Stroke("a", emptyList(), 3f, 0, null)), emptyMap())) }
    @Test fun theAreaCoversTheInk_withAMargin() {
        val s = stroke("a", 100f, 200f, 400f, 300f); val a = CanvasExport.area(listOf(s), emptyMap())!!
        assertTrue(a.left < 100f && a.top < 200f && a.right > 400f && a.bottom > 300f); assertTrue("margin is modest", a.width < 300f * 1.4f)
    }
    @Test fun theMarginHasAFloor_andScalesWithBigContent() {
        val tiny = CanvasExport.area(listOf(stroke("a", 0f, 0f, 2f, 2f)), emptyMap())!!; assertTrue(tiny.width >= 2f + 2 * CanvasExport.MIN_MARGIN - 1f)
        val big = CanvasExport.area(listOf(stroke("a", 0f, 0f, 10_000f, 5_000f)), emptyMap())!!; assertTrue(big.left < -100f)
    }
    @Test fun theAreaCoversTheResultsWrittenBesideTheInk() {
        val s = (0 until 4).map { stroke("s$it", it * 50f, 100f, it * 50f + 30f, 160f) }; val l = layouts(*s.toTypedArray())
        val a = CanvasExport.area(s, l)!!; assertTrue("the answer sits to the right of the ink", a.right >= l.getValue("e1").result!!.origin.x)
    }
    @Test fun aGraphThatWasDraggedAway_isStillIncluded() {
        val s = (0 until 4).map { stroke("s$it", it * 50f, 100f, it * 50f + 30f, 160f) }
        val moved = ExpressionLayout.all(s, mapOf("e1" to ExpressionObject("e1", result = "40", resultInline = true, status = ProcessingStatus.EVALUATED)), graphs = setOf("e1"), graphOffsets = mapOf("e1" to Vec2(3000f, 2000f)))
        val a = CanvasExport.area(s, moved)!!; val g = moved.getValue("e1").graphRect!!
        assertTrue(a.right >= g.right && a.bottom >= g.bottom)
    }
    @Test fun aGraphThatIsHidden_isNotIncluded() {
        val s = (0 until 4).map { stroke("s$it", it * 50f, 100f, it * 50f + 30f, 160f) }
        val without = CanvasExport.area(s, layouts(*s.toTypedArray()))!!; val with = CanvasExport.area(s, layouts(*s.toTypedArray(), graphs = true))!!
        assertTrue(with.width > without.width)
    }

    // ---------------------------------------------------------------- how big the file is
    @Test fun plan_keepsTheShapeOfTheArea_andFitsTheLimit() {
        val p = CanvasExport.plan(WorldRect(0f, 0f, 2000f, 1000f), maxSide = 1000, maxScale = 2f)
        assertEquals(1000, p.width); assertEquals(500, p.height); assertEquals(0.5f, p.scale, 0.001f)
    }
    @Test fun plan_neverEnlargesSmallContentBeyondTheScaleCap() {
        val p = CanvasExport.plan(WorldRect(0f, 0f, 300f, 100f), maxSide = 4000, maxScale = 2f); assertEquals(2f, p.scale, 0.0001f); assertEquals(600, p.width); assertEquals(200, p.height)
    }
    @Test fun plan_neverProducesAnEmptyOrOversizedImage() {
        for (r in listOf(WorldRect(0f, 0f, 0.001f, 0.001f), WorldRect(0f, 0f, 1e7f, 1f), WorldRect(0f, 0f, 1f, 1e7f), WorldRect(-1e6f, -1e6f, 1e6f, 1e6f))) {
            val p = CanvasExport.plan(r, 3072, 2f); assertTrue("$r -> ${p.width}x${p.height}", p.width in 1..3072 && p.height in 1..3072); assertTrue(p.scale > 0f && p.scale.isFinite())
        }
    }
    @Test fun plan_mapsTheAreaToTheOriginOfTheImage() {
        val r = WorldRect(-500f, 300f, 700f, 900f); val p = CanvasExport.plan(r, 3000, 2f)
        assertEquals(0f, (r.left * p.scale + p.offsetX), 0.01f); assertEquals(0f, (r.top * p.scale + p.offsetY), 0.01f)
        assertEquals(p.width.toFloat(), r.right * p.scale + p.offsetX, 1.5f)
    }

    // ---------------------------------------------------------------- file names
    @Test fun fileName_isSafeAndReadable() {
        assertEquals("Canvas_1.png", CanvasExport.fileName("Canvas 1", "png"))
        assertEquals("Maths_homework.pdf", CanvasExport.fileName("  Maths / homework  ", "pdf"))
        assertEquals("a_b_c.png", CanvasExport.fileName("a:b*c", "png"))
    }
    @Test fun fileName_neverEmpty_neverHuge_neverEscapesTheFolder() {
        assertEquals("canvas.png", CanvasExport.fileName("", "png")); assertEquals("canvas.png", CanvasExport.fileName("///", "png")); assertEquals("canvas.png", CanvasExport.fileName("😀", "png"))
        assertTrue(CanvasExport.fileName("x".repeat(500), "pdf").length <= 40 + 4)
        assertFalse(CanvasExport.fileName("../../etc/passwd", "png").contains("/")); assertFalse(CanvasExport.fileName("..", "png").startsWith("."))
    }
    @Test fun fileName_keepsLettersFromAnyLanguage() { assertEquals("Café_Ørsted.png", CanvasExport.fileName("Café Ørsted", "png")) }


    // ---------------------------------------------------------------- naming an imported canvas
    @Test fun uniqueName_keepsAFreeName() { assertEquals("Homework", CanvasExport.uniqueName("Homework", listOf("Canvas 1", "Notes"))) }
    @Test fun uniqueName_numbersATakenName() {
        assertEquals("Homework (2)", CanvasExport.uniqueName("Homework", listOf("Homework")))
        assertEquals("Homework (3)", CanvasExport.uniqueName("Homework", listOf("Homework", "Homework (2)")))
        assertEquals("Homework (2)", CanvasExport.uniqueName("Homework", listOf("Homework", "Homework (3)")))
    }
    @Test fun uniqueName_comparesIgnoringCase() { assertEquals("homework (2)", CanvasExport.uniqueName("homework", listOf("Homework"))) }
    @Test fun uniqueName_neverReturnsABlankName() { assertEquals("Imported canvas", CanvasExport.uniqueName("   ", emptyList())); assertEquals("Imported canvas (2)", CanvasExport.uniqueName("", listOf("Imported canvas"))) }
}
