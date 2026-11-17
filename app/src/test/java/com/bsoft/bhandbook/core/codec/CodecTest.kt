package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep
import org.junit.Assert.*
import org.junit.Test

class CodecTest {
    // pressure values sit on the 1/255 grid the codec stores
    private fun stroke(id: String, n: Int, expr: String? = null) = Stroke(id,
        (0 until n).map { StrokePoint(it * 1.5f - 300.25f, 1e6f + it, it * 16L + 1_700_000_000_000L, ((it % 9) * 28) / 255f) }, 3.5f, 0xFF112233, expr)

    @Test fun strokePoints_roundTripExactly() {
        val s = stroke("a", 50)
        val back = StrokeCodec.decode(StrokeCodec.encode(s.points))!!
        assertEquals(s.points.size, back.size)
        for (i in s.points.indices) {
            assertEquals(s.points[i].x, back[i].x, 0f); assertEquals(s.points[i].y, back[i].y, 0f)
            assertEquals(s.points[i].t, back[i].t); assertEquals(s.points[i].pressure, back[i].pressure, 0.005f)
        }
    }
    @Test fun pressureQuantisation_isIdempotent_soRepeatedSavesNeverDrift() {
        val raw = (0 until 40).map { StrokePoint(it.toFloat(), 0f, it.toLong(), (it * 0.0237f) % 1f) }
        val once = StrokeCodec.decode(StrokeCodec.encode(raw))!!
        val twice = StrokeCodec.decode(StrokeCodec.encode(once))!!
        assertEquals(once, twice)
        for (i in raw.indices) assertEquals(raw[i].pressure, once[i].pressure, 1f / 255f)
    }
    @Test fun emptyStroke_roundTrips() { assertEquals(0, StrokeCodec.decode(StrokeCodec.encode(emptyList()))!!.size) }
    @Test fun corruptedOrTruncatedData_returnsNull_notCrash() {
        val bytes = StrokeCodec.encode(stroke("a", 20).points)
        assertNull(StrokeCodec.decode(bytes.copyOf(bytes.size - 5)))
        assertNull(StrokeCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(StrokeCodec.decode(ByteArray(0)))
    }
    @Test fun encoding_isCompact() { assertTrue(StrokeCodec.encode(stroke("a", 1000).points).size < 1000 * 17) }

    @Test fun solutionSteps_roundTrip_withUnicodeAndSeparatorsInText() {
        val steps = listOf(
            SolutionStep(1, "25 + 15 × 2", "Multiply 15 × 2 first, then add; √ ÷ ± ≈", "25 + 30", 5..6),
            SolutionStep(2, "25 + 30", "line1\nline2 | ; , \"quoted\"", "55", null))
        assertEquals(steps, StepsCodec.decode(StepsCodec.encode(steps)))
    }
    @Test fun emptySteps_roundTrip() { assertEquals(emptyList<SolutionStep>(), StepsCodec.decode(StepsCodec.encode(emptyList()))) }
    @Test fun garbageSteps_decodeToEmpty() { assertEquals(emptyList<SolutionStep>(), StepsCodec.decode("\u001Egarbage\u001F")) }

    @Test fun wholeCanvas_roundTrips_strokesExpressionsAndSolutions() {
        val state = CanvasState(
            strokes = listOf(stroke("s1", 10, "e1"), stroke("s2", 12, "e1"), stroke("s3", 5, null)),
            expressions = mapOf("e1" to ExpressionObject("e1", "25+15=", 0.91f, "40",
                listOf(SolutionStep(1, "25 + 15", "Add 25 + 15", "40")), solutionVisible = false, status = ProcessingStatus.EVALUATED, modifiedAt = 99L, strokeSignature = 12345, displayText = "25 + 15", resultInline = true)))
        val stored = CanvasCodec.toStored(state)
        assertEquals(state, CanvasCodec.fromStored(stored))
    }
    @Test fun restoredStrokeOrder_isPreserved() {
        val state = CanvasState(strokes = (0 until 30).map { stroke("s$it", 3) })
        assertEquals(state.strokes.map { it.id }, CanvasCodec.fromStored(CanvasCodec.toStored(state)).strokes.map { it.id })
    }
    @Test fun corruptStoredStroke_isSkipped_restOfCanvasSurvives() {
        val state = CanvasState(strokes = listOf(stroke("ok", 5), stroke("bad", 5)))
        val stored = CanvasCodec.toStored(state)
        val damaged = stored.copy(strokes = stored.strokes.map { if (it.id == "bad") it.copy(points = byteArrayOf(9)) else it })
        assertEquals(listOf("ok"), CanvasCodec.fromStored(damaged).strokes.map { it.id })
    }
}
