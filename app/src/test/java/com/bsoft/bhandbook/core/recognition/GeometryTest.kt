package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class GeometryTest {
    private fun stroke(rnd: Random, cx: Float, cy: Float, n: Int = 12, spread: Float = 30f) =
        Stroke("s", (0 until n).map { StrokePoint(cx + rnd.nextFloat() * spread, cy + rnd.nextFloat() * spread) }, 3f, 0)

    @Test fun closerThan_agreesWithMinDistance_onRandomStrokes() {
        val rnd = Random(7)
        repeat(400) {
            val a = stroke(rnd, 0f, 0f); val b = stroke(rnd, rnd.nextFloat() * 120f, rnd.nextFloat() * 120f)
            val tol = 1f + rnd.nextFloat() * 20f
            assertEquals("min=${Geometry.minDistance(a, b)} tol=$tol", Geometry.minDistance(a, b) <= tol, Geometry.closerThan(a, b, tol))
        }
    }
    @Test fun closerThan_isTrueForCrossingStrokes_andFalseForDistantOnes() {
        val h = Stroke("h", listOf(StrokePoint(0f, 10f), StrokePoint(20f, 10f)), 3f, 0); val v = Stroke("v", listOf(StrokePoint(10f, 0f), StrokePoint(10f, 20f)), 3f, 0)
        assertTrue(Geometry.closerThan(h, v, 1f))
        assertFalse(Geometry.closerThan(h, Stroke("far", listOf(StrokePoint(500f, 500f), StrokePoint(520f, 500f)), 3f, 0), 50f))
    }
    @Test fun segmentationOfAHugeExpression_staysFast() {
        val strokes = Pen.write("12+34+56+78+90+12+34+56+78+90+12+34+56+78+90=", 3)
        val t0 = System.nanoTime(); repeat(5) { EqualsDetector.endsWithEquals(strokes) }
        val ms = (System.nanoTime() - t0) / 5_000_000
        println("PERF EqualsDetector on ${strokes.size} strokes: ${ms} ms")
        assertTrue("took $ms ms for ${strokes.size} strokes", ms < 250)
    }
}
