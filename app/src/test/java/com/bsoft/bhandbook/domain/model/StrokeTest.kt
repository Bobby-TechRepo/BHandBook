package com.bsoft.bhandbook.domain.model

import com.bsoft.bhandbook.core.graphics.Vec2
import org.junit.Assert.*
import org.junit.Test

class StrokeTest {
    private fun stroke(id: String, vararg pts: Pair<Float, Float>, expr: String? = null) =
        Stroke(id, pts.mapIndexed { i, p -> StrokePoint(p.first, p.second, t = i * 10L) }, width = 4f, color = 0xFF000000, expressionId = expr)

    @Test fun bounds_coverAllPoints() {
        val b = stroke("a", 1f to 5f, 9f to 2f, 4f to 8f).bounds
        assertEquals(1f, b.left, 0f); assertEquals(2f, b.top, 0f)
        assertEquals(9f, b.right, 0f); assertEquals(8f, b.bottom, 0f)
    }

    @Test fun translated_movesEveryPoint_andKeepsMetadata() {
        val s = stroke("a", 0f to 0f, 10f to 10f, expr = "e1").translated(5f, -3f)
        assertEquals(5f, s.points[0].x, 0f); assertEquals(-3f, s.points[0].y, 0f)
        assertEquals("e1", s.expressionId); assertEquals(0L, s.points[0].t)
    }

    @Test fun withExpression_associatesStroke() {
        assertEquals("e9", stroke("a", 0f to 0f).withExpression("e9").expressionId)
    }

    @Test fun emptyStroke_hasEmptyBounds_andNoCrash() {
        val s = Stroke("x", emptyList(), 2f, 0)
        assertTrue(s.bounds.isEmpty)
    }

    @Test fun pressure_defaultsToOne_whenDeviceDoesNotSupportIt() {
        assertEquals(1f, StrokePoint(0f, 0f).pressure, 0f)
    }
}

class StrokeEraserTest {
    private fun horizontal(id: String, n: Int = 21) =
        Stroke(id, (0 until n).map { StrokePoint(it * 10f, 0f, t = it.toLong()) }, 4f, 0)

    @Test fun eraserFarAway_leavesStrokeUntouched() {
        val s = horizontal("a")
        val out = StrokeEraser.erase(s, Vec2(100f, 500f), 15f)
        assertEquals(listOf(s), out)
    }

    @Test fun eraserOverMiddle_splitsStrokeIntoTwoParts_withNewIds() {
        val out = StrokeEraser.erase(horizontal("a"), Vec2(100f, 0f), 15f)
        assertEquals(2, out.size)
        assertTrue(out[0].points.all { it.x < 100f - 15f + 0.01f })
        assertTrue(out[1].points.all { it.x > 100f + 15f - 0.01f })
        assertEquals(2, out.map { it.id }.toSet().size)
    }

    @Test fun eraserOverStart_trimsWithoutSplitting() {
        val out = StrokeEraser.erase(horizontal("a"), Vec2(0f, 0f), 15f)
        assertEquals(1, out.size)
        assertTrue(out[0].points.first().x > 14f)
    }

    @Test fun eraserCoveringWholeStroke_removesIt() {
        val tiny = Stroke("t", listOf(StrokePoint(0f, 0f), StrokePoint(2f, 0f)), 4f, 0)
        assertTrue(StrokeEraser.erase(tiny, Vec2(1f, 0f), 20f).isEmpty())
    }

    @Test fun erasedParts_keepExpressionAssociation() {
        val out = StrokeEraser.erase(horizontal("a").withExpression("e1"), Vec2(100f, 0f), 15f)
        assertTrue(out.all { it.expressionId == "e1" })
    }

    @Test fun strokeHit_detectsStrokeWithinRadius() {
        assertTrue(StrokeEraser.touches(horizontal("a"), Vec2(55f, 8f), 10f))
        assertFalse(StrokeEraser.touches(horizontal("a"), Vec2(55f, 30f), 10f))
    }
}
