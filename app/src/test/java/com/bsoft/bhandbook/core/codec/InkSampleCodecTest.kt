package com.bsoft.bhandbook.core.codec

import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.core.recognition.SampleEvaluator
import com.bsoft.bhandbook.domain.model.InkSample
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import org.junit.Assert.*
import org.junit.Test

class InkSampleCodecTest {
    private fun sample(label: String = "25+15=", rec: String? = "25+16=") =
        InkSample(label, rec, Pen(1).text("25+15=").strokes, createdAt = 1_700_000_000_123L)

    @Test fun roundTrip_preservesLabelStrokesAndPoints() {
        val s = sample()
        val back = InkSampleCodec.decode(InkSampleCodec.encode(s))!!
        assertEquals(s.label, back.label); assertEquals(s.recognizedAs, back.recognizedAs); assertEquals(s.createdAt, back.createdAt)
        assertEquals(s.strokes.size, back.strokes.size)
        for (i in s.strokes.indices) {
            assertEquals(s.strokes[i].points.size, back.strokes[i].points.size)
            for (j in s.strokes[i].points.indices) {
                val a = s.strokes[i].points[j]; val b = back.strokes[i].points[j]
                assertEquals(a.x, b.x, 1e-3f); assertEquals(a.y, b.y, 1e-3f); assertEquals(a.t, b.t); assertEquals(a.pressure, b.pressure, 1e-3f)
            }
            assertEquals(s.strokes[i].width, back.strokes[i].width, 1e-3f)
        }
    }
    @Test fun nullRecognisedAs_roundTrips() { assertNull(InkSampleCodec.decode(InkSampleCodec.encode(sample(rec = null)))!!.recognizedAs) }
    @Test fun specialCharactersInLabel_areEscaped() {
        val label = "√(9)×\"2\"\\ ÷ \n\t x²"
        assertEquals(label, InkSampleCodec.decode(InkSampleCodec.encode(sample(label = label)))!!.label)
    }
    @Test fun output_isPlainJson_thatOtherToolsCanRead() {
        val json = InkSampleCodec.encode(sample())
        assertTrue(json.trimStart().startsWith("{")); assertTrue(json.contains("\"label\"")); assertTrue(json.contains("\"strokes\""))
    }
    @Test fun emptyStrokes_roundTrip() { assertEquals(0, InkSampleCodec.decode(InkSampleCodec.encode(InkSample("1", null, emptyList(), 5L)))!!.strokes.size) }
    @Test fun malformedOrWrongShape_returnsNull_notCrash() {
        listOf("", "not json", "{", "[]", "{\"label\": 5}", "{\"label\":\"x\"}", "{\"label\":\"x\",\"strokes\":[{\"points\":[[1,2]]}]}", "{\"version\":99,\"label\":\"x\",\"strokes\":[]}")
            .forEach { assertNull("input: $it", InkSampleCodec.decode(it)) }
    }
    @Test fun unicodeEscapes_inInput_areUnderstood() {
        val json = """{"version":1,"label":"\u221a9","recognizedAs":null,"createdAt":1,"strokes":[]}"""
        assertEquals("√9", InkSampleCodec.decode(json)!!.label)
    }
    @Test fun numbers_inExponentForm_areAccepted() {
        val json = """{"version":1,"label":"1","recognizedAs":null,"createdAt":1,"strokes":[{"width":3e0,"points":[[1.5E2,-2.0e1,10,0.5],[1,2,11,1]]}]}"""
        val p = InkSampleCodec.decode(json)!!.strokes.single().points
        assertEquals(150f, p[0].x, 0f); assertEquals(-20f, p[0].y, 0f)
    }
    @Test fun veryDeepJson_isRejected_notStackOverflow() {
        assertNull(InkSampleCodec.decode("[".repeat(100_000)))
        assertNull(InkSampleCodec.decode("{\"a\":".repeat(5_000)))
    }
}

class SampleEvaluatorTest {
    private fun sample(label: String, seed: Long) = InkSample(label, null, Pen(seed, sloppiness = 0.5f).text(label.replace("×", "*")).strokes, 0L)

    @Test fun evaluator_reportsAccuracy_andListsMisreads() {
        val good = listOf(sample("25+15=", 1), sample("9-4", 2), sample("7+8", 3))
        val bad = InkSample("99+99", null, Pen(4).text("12+34").strokes, 0L)      // label deliberately wrong
        val report = SampleEvaluator.evaluate(good + bad)
        assertEquals(4, report.total); assertEquals(3, report.correct)
        assertEquals(0.75, report.accuracy, 1e-9)
        assertEquals(1, report.misses.size); assertEquals("99+99", report.misses.single().expected)
        assertEquals("12+34", report.misses.single().actual)
    }
    @Test fun evaluator_countsLowConfidenceAsNeedingReview_separately() {
        val r = SampleEvaluator.evaluate(listOf(sample("25+15=", 1)))
        assertEquals(1, r.total); assertTrue(r.reliableCorrect <= r.correct)
    }
    @Test fun evaluator_handlesEmptyInput() { val r = SampleEvaluator.evaluate(emptyList()); assertEquals(0, r.total); assertEquals(0.0, r.accuracy, 0.0) }
    @Test fun labels_areComparedInNormalisedForm() {
        // users type "x" for ×, spaces, and "/" for ÷ differently from the recogniser's canonical text
        assertEquals(SampleEvaluator.normalise("6 x 4 ="), SampleEvaluator.normalise("6×4="))
        assertEquals(SampleEvaluator.normalise("8÷2"), SampleEvaluator.normalise("8 / 2"))
        assertNotEquals(SampleEvaluator.normalise("2x+1"), SampleEvaluator.normalise("2×+1"))
    }
}
