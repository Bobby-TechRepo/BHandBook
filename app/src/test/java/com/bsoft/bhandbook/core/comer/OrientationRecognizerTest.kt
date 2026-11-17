package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.core.recognition.RecognitionCancelled
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.rotated
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** A backend whose n-th recognition reads [readings][n] (the last one repeats); records every input it was given. */
class SequenceBackend(override val vocab: ComerVocab, private val readings: List<List<String>>) : ComerModelBackend {
    val inputs = ArrayList<ComerInput>(); var closed = 0
    override fun encode(input: ComerInput): ComerEncoding {
        val tokens = readings[minOf(inputs.size, readings.size - 1)]; inputs += input
        return object : ComerEncoding {
            override fun nextLogits(ids: IntArray) = FloatArray(vocab.size).also { it[if (ids.size - 1 < tokens.size) vocab.id(tokens[ids.size - 1])!! else vocab.eos] = 30f }
            override fun close() { closed++ }
        }
    }
}

class OrientationRecognizerTest {
    private val parser = DefaultMathParser()
    private fun write(text: String, seed: Long = 1) = Pen(seed, size = 70f, sloppiness = 0.5f).text(text).strokes
    private fun rotate(s: List<Stroke>, deg: Double): List<Stroke> { val p = s.flatMap { it.points }; return s.rotated(deg * PI / 180, p.map { it.x.toDouble() }.average(), p.map { it.y.toDouble() }.average()) }
    private fun vocabOrSkip(): ComerVocab { val v = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", v != null); return v!! }
    private fun tokens(s: String) = s.trim().split(" ")
    private fun aspect(i: ComerInput) = i.contentWidth.toDouble() / i.contentHeight
    private fun recognizer(b: ComerModelBackend, cfg: ComerRecognizerConfig = ComerRecognizerConfig()) = ComerRecognizer(b, parser, cfg)

    @Test fun horizontalWriting_costsExactlyOneModelRun() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("2 5 + 1 5")))
        val r = recognizer(b).recognize(write("25+15=")) as ComerRecognition.Recognized
        assertEquals(1, b.inputs.size); assertEquals(0.0, r.rotationDegrees, 0.0)
    }
    @Test fun rotatedWriting_reachesTheModelAsHorizontalText_atEveryAngle() {
        val v = vocabOrSkip()
        val upright = SequenceBackend(v, listOf(tokens("2 5 + 1 5"))).also { recognizer(it).recognize(write("25+15=")) }.inputs.single()
        for (angle in listOf(90.0, -90.0, 180.0, 45.0, -45.0, 135.0, -135.0, 60.0)) {
            val b = SequenceBackend(v, listOf(tokens("2 5 + 1 5")))
            val r = recognizer(b).recognize(rotate(write("25+15="), angle)) as ComerRecognition.Recognized
            val first = b.inputs.first()
            assertEquals("angle $angle: accepted at the first stage", 1, b.inputs.size)
            assertTrue("angle $angle: content aspect ${aspect(first)} should match the horizontal ${aspect(upright)}", abs(aspect(first) / aspect(upright) - 1) < 0.2)
            assertTrue("angle $angle: reported rotation ${r.rotationDegrees}", abs(abs(r.rotationDegrees) - abs(angle)) < 15 || abs(abs(r.rotationDegrees) - (360 - abs(angle))) < 15)
        }
    }
    @Test fun theOriginalStrokesAreNeverChanged() {
        val v = vocabOrSkip(); val ink = rotate(write("25+15="), 90.0); val before = ink.map { it.points.first().x }
        recognizer(SequenceBackend(v, listOf(tokens("2 + 3")))).recognize(ink)
        assertEquals(before, ink.map { it.points.first().x })
    }

    // ---------------------------------------------------------------- staged arbitration
    @Test fun anUnsupportedFirstReading_triggersTheNextOrientation_andACalculableOneWins() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("\\sum a"), tokens("2 + 3")))
        val r = recognizer(b).recognize(rotate(write("25+15="), 90.0)) as ComerRecognition.Recognized
        assertEquals(2, b.inputs.size); assertEquals("2+3", r.linear)
    }
    @Test fun readingsWithStrayLetters_areNotPlausible_soTheSearchContinues() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("\\frac { f } { n }"), tokens("1 2 + 3")))
        val r = recognizer(b).recognize(rotate(write("25+15="), -90.0)) as ComerRecognition.Recognized
        assertEquals("12+3", r.linear); assertTrue(b.inputs.size >= 2)
    }
    @Test fun variablesXYZ_areAccepted_inTheFirstStage() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("2 x + 5 = 1 5")))
        val r = recognizer(b).recognize(rotate(write("2x+5=15"), 90.0)) as ComerRecognition.Recognized
        assertEquals(1, b.inputs.size); assertEquals("2x+5=15", r.linear)
    }
    @Test fun ifNothingIsPlausible_theBestCalculableReadingIsKept_withAtMostFourRuns() {
        val v = vocabOrSkip()
        val b = SequenceBackend(v, listOf(tokens("\\frac { f } { n }"), tokens("\\sum a"), tokens("a + b"), tokens("\\int")))
        val r = recognizer(b).recognize(rotate(write("25+15="), 90.0))
        assertEquals(4, b.inputs.size); r as ComerRecognition.Recognized
        assertEquals("equally ranked readings: the earlier stage wins", "((f)/(n))", r.linear); assertFalse(r.plausible)
        assertEquals("every encoding is closed", 4, b.closed)
    }
    @Test fun ifNothingIsCalculable_theResultIsStillReportedForCorrection_neverCalculated() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("\\sum a")))
        val r = recognizer(b).recognize(rotate(write("25+15="), 90.0))
        assertTrue("$r", r is ComerRecognition.NeedsCorrection); assertEquals(4, b.inputs.size)
    }
    @Test fun anUprightStackedFraction_isStillReadUpright() {
        val v = vocabOrSkip()
        // first stage (the estimated direction) gives nothing usable; the second (estimate - 90 degrees) reads the fraction
        val b = SequenceBackend(v, listOf(tokens("\\sum a"), tokens("\\frac { 3 } { 4 }")))
        val r = recognizer(b).recognize(Pen(1, size = 70f, sloppiness = 0.5f).frac("3", "4").strokes) as ComerRecognition.Recognized
        assertEquals("((3)/(4))", r.linear); assertEquals("the fraction is read (almost) unrotated", 0.0, abs(r.rotationDegrees), 15.0)
    }

    // ---------------------------------------------------------------- when orientation is not attempted
    @Test fun slightlySlantedWriting_isNotRotated() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("2 5 + 1 5")))
        recognizer(b).recognize(rotate(write("25+15="), 12.0)); assertEquals(1, b.inputs.size)
    }
    @Test fun thePolicyCanBeSwitchedOff() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("\\sum a")))
        recognizer(b, ComerRecognizerConfig(orientationEnabled = false)).recognize(rotate(write("25+15="), 90.0)); assertEquals(1, b.inputs.size)
    }
    @Test fun tooLittleInk_hasNoOrientationEstimate_soItIsReadAsWritten() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("2 ^ { 2 }")))
        recognizer(b).recognize(rotate(Pen(1, size = 70f, sloppiness = 0.5f).text("2").sup("2").strokes, 90.0)); assertEquals(1, b.inputs.size)
    }
    @Test fun cancellation_stillPropagates_duringTheSearch() {
        val v = vocabOrSkip(); val b = SequenceBackend(v, listOf(tokens("\\sum a")))
        var n = 0
        try { recognizer(b).recognize(rotate(write("25+15="), 90.0)) { ++n > 6 }; fail("expected cancellation") } catch (_: RecognitionCancelled) {}
        assertEquals("nothing is left open", b.inputs.size, b.closed)
    }
}
