package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import com.bsoft.bhandbook.domain.model.rotated
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class OrientationTest {
    private fun write(text: String, seed: Long = 1) = Pen(seed, size = 70f, sloppiness = 0.5f).text(text).strokes
    private fun deg(rad: Double) = rad * 180.0 / PI
    /** Smallest absolute difference between two angles in degrees. */
    private fun diff(a: Double, b: Double): Double { var d = (a - b) % 360.0; if (d > 180) d -= 360; if (d < -180) d += 360; return abs(d) }

    // ---------------------------------------------------------------- rotation utility
    @Test fun rotatedCopy_movesPointsAndKeepsEverythingElse() {
        val s = Stroke("a", listOf(StrokePoint(10f, 0f, 5L, 0.7f), StrokePoint(20f, 0f, 6L)), 3f, 0xFF00FF, "e1")
        val r = listOf(s).rotated(PI / 2, 0.0, 0.0).single()          // 90 degrees clockwise on a y-down screen: (x, 0) -> (0, x)
        assertEquals(0f, r.points[0].x, 1e-3f); assertEquals(10f, r.points[0].y, 1e-3f); assertEquals(20f, r.points[1].y, 1e-3f)
        assertEquals("a", r.id); assertEquals(3f, r.width, 0f); assertEquals("e1", r.expressionId); assertEquals(5L, r.points[0].t); assertEquals(0.7f, r.points[0].pressure, 0f)
    }
    @Test fun rotationIsReversible() {
        val ink = write("25+15=")
        val back = ink.rotated(1.1, 50.0, 60.0).rotated(-1.1, 50.0, 60.0)
        for ((a, b) in ink.zip(back)) for ((p, q) in a.points.zip(b.points)) { assertEquals(p.x, q.x, 0.01f); assertEquals(p.y, q.y, 0.01f) }
    }
    @Test fun originalStrokesAreNeverModified() {
        val ink = write("25+15="); val x0 = ink.first().points.first().x
        ink.rotated(1.0, 0.0, 0.0); assertEquals(x0, ink.first().points.first().x, 0f)
    }

    // ---------------------------------------------------------------- reading direction from the order of writing
    @Test fun horizontalWriting_isEstimatedAsUpright() {
        for (seed in 1L..5L) {
            val e = OrientationEstimator.estimate(write("25+15=", seed))!!
            assertTrue("seed $seed: ${deg(e.angle)}", abs(deg(e.angle)) < 10)
        }
    }
    @Test fun rotatedWriting_isEstimated_atManyAngles() {
        for (angle in listOf(30.0, 60.0, 90.0, 120.0, 150.0, 180.0, -30.0, -60.0, -90.0, -120.0, -150.0)) for (text in listOf("25+15=", "12*8-3=")) {
            val ink = write(text).rotated(angle * PI / 180, 300.0, 300.0)
            val e = OrientationEstimator.estimate(ink)!!
            assertTrue("$text rotated $angle: estimated ${deg(e.angle)}", diff(deg(e.angle), angle) < 12)
        }
    }
    @Test fun upsideDownRightToLeftWriting_isDistinguishedFromUpright_byTheOrderOfDrawing() {
        val e = OrientationEstimator.estimate(write("25+15=").rotated(PI, 0.0, 0.0))!!
        assertTrue(diff(deg(e.angle), 180.0) < 12)
    }
    @Test fun estimateIsIndependentOfPositionOnTheCanvas() {
        val a = OrientationEstimator.estimate(write("25+15=").rotated(PI / 2, 0.0, 0.0))!!
        val b = OrientationEstimator.estimate(write("25+15=").map { it.translated(9000f, -4000f) }.rotated(PI / 2, 0.0, 0.0))!!
        assertEquals(deg(a.angle), deg(b.angle), 1.0)
    }
    @Test fun tooFewStrokes_giveNoEstimate() {
        assertNull(OrientationEstimator.estimate(write("7").take(1))); assertNull(OrientationEstimator.estimate(emptyList()))
    }
    @Test fun strokesWithoutTimestamps_giveNoDirection_soTheCallerKeepsTheUprightReading() {
        val noTime = write("25+15=").map { s -> s.copy(points = s.points.map { it.copy(t = 0L) }) }
        assertNull(OrientationEstimator.estimate(noTime))
    }
    @Test fun lineLikeness_isHighForWrittenLines_andLowForABlob() {
        assertTrue(OrientationEstimator.estimate(write("25+15="))!!.linearity > 0.9)
        val blob = (0 until 8).map { i -> Stroke("b$i", listOf(StrokePoint(100f + (i % 3) * 20f, 100f + (i / 3) * 20f, i * 100L), StrokePoint(110f + (i % 3) * 20f, 110f + (i / 3) * 20f, i * 100L + 10)), 3f, 0) }
        assertTrue(OrientationEstimator.estimate(blob)!!.linearity < 0.9)
    }


    // ---------------------------------------------------------------- how strongly the drawing order follows the line of text
    @Test fun orderCorrelation_isHighWhenWrittenInOrder_andLowWhenTheOrderIsScrambled() {
        val ordered = write("25+15=")
        assertTrue(OrientationEstimator.estimate(ordered)!!.orderCorrelation > 0.8)
        val rnd = java.util.Random(3); val times = ordered.indices.shuffled(rnd)
        val scrambled = ordered.mapIndexed { i, s -> s.copy(points = s.points.mapIndexed { k, p -> p.copy(t = times[i] * 1000L + k) }) }
        assertTrue("${OrientationEstimator.estimate(scrambled)!!.orderCorrelation}", OrientationEstimator.estimate(scrambled)!!.orderCorrelation < 0.6)
    }

    // ---------------------------------------------------------------- WritingDirection: what the layout uses to turn the answer
    private fun turned(text: String, degrees: Double) = write(text).rotated(degrees * PI / 180, 300.0, 300.0)

    @Test fun writingDirection_isZeroForOrdinaryWriting() {
        assertEquals(0.0, WritingDirection.of(write("25+15=")), 0.0)
        assertEquals(0.0, WritingDirection.of(turned("25+15=", 10.0)), 0.0)
        assertEquals(0.0, WritingDirection.of(turned("25+15=", -15.0)), 0.0)
    }
    @Test fun writingDirection_followsRotatedWriting_snappedToFifteenDegrees() {
        for (a in listOf(90.0, -90.0, 180.0, 45.0, -45.0, 135.0, 60.0)) {
            val d = WritingDirection.of(turned("25+15=", a)); assertTrue("$a -> $d", diff(d, a) <= 15.0); assertEquals("snapped", 0.0, d % 15.0, 1e-9)
        }
    }
    @Test fun writingDirection_ignoresFractionsAndColumns_becauseTheyHaveABar() {
        assertEquals(0.0, WritingDirection.of(Pen(1, size = 70f).frac("123", "456").strokes), 0.0)
        val bar = Stroke("bar", (0..12).map { StrokePoint(60f + 420f * it / 12f, 345f, 900_000L + it) }, 4f, 0)
        assertEquals(0.0, WritingDirection.of(Pen(1, size = 70f).at(100f, 200f).text("105").strokes + Pen(2, size = 70f).at(100f, 310f).text("-37").strokes + bar), 0.0)
    }
    @Test fun writingDirection_ignoresScrambledDrawingOrder() {
        val ordered = write("25+15=").rotated(PI / 2, 0.0, 0.0); val rnd = java.util.Random(5); val times = ordered.indices.shuffled(rnd)
        assertEquals(0.0, WritingDirection.of(ordered.mapIndexed { i, s -> s.copy(points = s.points.mapIndexed { k, p -> p.copy(t = times[i] * 1000L + k) }) }), 0.0)
    }
    @Test fun writingDirection_needsEnoughInk() { assertEquals(0.0, WritingDirection.of(write("7").take(1)), 0.0); assertEquals(0.0, WritingDirection.of(emptyList()), 0.0) }


    // ---------------------------------------------------------------- does the order of drawing run ALONG the ink? (turned text: yes; two lines of text: no)
    @Test fun axisAlignment_isHighForTurnedText_atAnyAngle() {
        for (a in listOf(90.0, -90.0, 60.0, 135.0, 180.0)) {
            val e = OrientationEstimator.estimate(write("25+15=").rotated(a * PI / 180, 300.0, 300.0))!!
            assertTrue("$a: ${e.axisAlignment}", e.axisAlignment > 0.9)
        }
    }
    @Test fun twoLinesOfText_areStillEstimatedAsHorizontalWriting() {
        val two = Pen(1, size = 70f, sloppiness = 0.5f).at(100f, 200f).text("12+34=5").strokes + Pen(2, size = 70f, sloppiness = 0.5f).at(100f, 310f).text("12-34=1").strokes
        assertTrue(Math.abs(deg(OrientationEstimator.estimate(two)!!.angle)) < 20)
    }
}
