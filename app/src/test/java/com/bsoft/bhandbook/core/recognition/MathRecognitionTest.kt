package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.Stroke
import org.junit.Assert.*
import org.junit.Test

class MathRecognitionTest {
    private val engine: MathRecognitionEngine = HandwritingMathRecognizer()
    private fun rec(strokes: List<Stroke>) = engine.recognize(strokes)

    /** Recognises the same text over several seeds (different noise/shear/rotation) and returns every distinct output. */
    private fun outputs(write: (Long) -> List<Stroke>, seeds: LongRange = 1L..6L) = seeds.map { rec(write(it)).expression.linear }

    private fun assertRecognised(expected: String, write: (Long) -> List<Stroke>, minimumHits: Int = 6) {
        val outs = outputs(write)
        val hits = outs.count { it == expected }
        assertTrue("expected '$expected' in at least $minimumHits/6 variants but got $outs", hits >= minimumHits)
    }

    // ---- single glyphs
    @Test fun digits_individually() {
        for (d in "0123456789") assertRecognised("$d", { Pen.write("$d", it) }, minimumHits = 5)
    }
    @Test fun multiDigitNumbers() { assertRecognised("2024", { Pen.write("2024", it) }, 5); assertRecognised("380", { Pen.write("380", it) }, 5) }

    // ---- basic arithmetic
    @Test fun addition() { assertRecognised("25+15", { Pen.write("25+15", it) }, 5) }
    @Test fun subtraction_minusIsNotEquals() { assertRecognised("9-4", { Pen.write("9-4", it) }, 5) }
    @Test fun multiplicationCross() { assertRecognised("6×4", { Pen.write("6*4", it) }, 5) }
    @Test fun divisionSign() { assertRecognised("8÷2", { Pen.write("8D2", it) }, 5) }
    @Test fun slashDivision() { assertRecognised("8/2", { Pen.write("8/2", it) }, 4) }
    @Test fun parentheses() { assertRecognised("(2+3)×4", { Pen.write("(2+3)*4", it) }, 5) }
    @Test fun decimalPoint() { assertRecognised("3.5+1.25", { Pen.write("3.5+1.25", it) }, 5) }
    @Test fun negativeNumber_leadingMinus() { assertRecognised("-5+8", { Pen.write("-5+8", it) }, 5) }

    // ---- equals sign
    @Test fun trailingEquals_isRecognisedAsEquals() { assertRecognised("25+15=", { Pen.write("25+15=", it) }, 5) }
    @Test fun equalsInEquation() { assertRecognised("2x+5=15", { Pen.write("2x+5=15", it) }, 4) }
    @Test fun singleHorizontalStroke_isMinus_notEquals() {
        val r = rec(Pen.write("7-3", 2)); assertFalse(r.expression.linear.contains("="))
    }
    @Test fun variableX_vs_multiplicationCross() {
        assertRecognised("3x+1", { Pen.write("3x+1", it) }, 4)        // x before an operator: variable
        assertRecognised("3×4", { Pen.write("3*4", it) }, 5)          // cross between operands: multiplication
    }

    // ---- two-dimensional structure
    @Test fun fraction() { assertRecognised("(1)/(2)", { Pen(it).frac("1", "2").strokes }, 5) }
    @Test fun fractionInsideExpression() { assertRecognised("3+(12)/(4)", { Pen(it).text("3+").frac("12", "4").strokes }, 4) }
    @Test fun exponent() { assertRecognised("2^(3)", { Pen(it).text("2").sup("3").strokes }, 5) }
    @Test fun exponentOnVariable_thenMore() { assertRecognised("x^(2)+1", { Pen(it).text("x").sup("2").text("+1").strokes }, 4) }
    @Test fun squareRoot() { assertRecognised("√(16)", { Pen(it).sqrt("16").strokes }, 5) }
    @Test fun squareRootInExpression() { assertRecognised("√(9)+2", { Pen(it).sqrt("9").text("+2").strokes }, 4) }

    // ---- robustness of small writing (jitter is ~constant in pixels, so it hurts small glyphs most)
    private fun hits(expected: String, n: Int = 100, sloppiness: Float = 2f, scale: Float, text: String = expected): Int =
        (1L..n).count { rec(Pen(it, sloppiness = sloppiness).text(text, scale).strokes).expression.linear == expected }

    @Test fun smallTwo_isNotConfusedWithSeven_underJitter() {
        val full = hits("2", scale = 1f); val mid = hits("2", scale = 0.85f); val small = hits("2", scale = 0.6f)
        assertTrue("2 at 1.0x: $full/100", full >= 90); assertTrue("2 at 0.85x: $mid/100", mid >= 80); assertTrue("2 at 0.6x: $small/100", small >= 50)
    }
    @Test fun smallDigits_stayReadable_underJitter() {
        for (d in "1357") assertTrue("digit $d at 0.85x: ${hits(d.toString(), scale = 0.85f)}/100", hits(d.toString(), scale = 0.85f) >= 75)
    }

    // ---- a slanted '1' vs the '/' operator: a slash needs something on its left to divide
    @Test fun leadingSlash_cannotBeDivision_soItIsReadAsOne() {
        assertEquals("12", rec(Pen.write("/2", 1)).expression.linear)
        assertEquals("(1)/(2)", rec(Pen(1).frac("/", "2").strokes).expression.linear)
    }
    @Test fun slashAfterAnOperator_isAlsoAOne() { assertEquals("3+12", rec(Pen.write("3+/2", 1)).expression.linear) }
    @Test fun slashBetweenOperands_staysDivision() { assertEquals("8/2", rec(Pen.write("8/2", 1)).expression.linear); assertEquals("(2+3)/4", rec(Pen.write("(2+3)/4", 1)).expression.linear) }
    @Test fun oneThenTwo_isRobust_underJitter() { assertTrue("12: ${hits("12", scale = 1f)}/100", hits("12", scale = 1f) >= 85) }

    // ---- confidence & reliability
    @Test fun cleanWriting_isReliable() {
        val r = rec(Pen.write("25+15=", 3)); assertTrue("confidence=${r.confidence}", r.reliable); assertTrue(r.confidence > 0.5f)
    }
    @Test fun scribble_isLowConfidence_andNotReliable() {
        val rnd = java.util.Random(42)
        val scribble = (0 until 3).map { s ->
            Stroke("z$s", (0 until 30).map { i -> com.bsoft.bhandbook.domain.model.StrokePoint(100f + i * 3f + rnd.nextFloat() * 25f, 300f + rnd.nextFloat() * 70f, i * 5L) }, 4f, 0)
        }
        val r = rec(scribble); assertFalse("scribble '${r.expression.linear}' was judged reliable (conf=${r.confidence})", r.reliable)
    }
    @Test fun emptyInput_givesEmptyUnreliableResult() {
        val r = rec(emptyList()); assertEquals("", r.expression.linear); assertFalse(r.reliable)
    }
    @Test fun symbolsKeepStrokeIds_soHandwritingStaysLinked() {
        val s = Pen.write("25+15", 1)
        val r = rec(s)
        assertEquals(s.map { it.id }.toSet(), r.expression.symbols.flatMap { it.strokeIds }.toSet())
    }
    @Test fun recognition_isTranslationInvariant() {
        val a = Pen(5).at(100f, 300f).text("25+15=").strokes
        val b = Pen(5).at(5000f, -2000f).text("25+15=").strokes
        assertEquals(rec(a).expression.linear, rec(b).expression.linear)
    }
    @Test fun recognition_isScaleInvariant() {
        val small = Pen(5, size = 25f).text("25+15=").strokes
        val large = Pen(5, size = 140f).text("25+15=").strokes
        assertEquals(rec(small).expression.linear, rec(large).expression.linear)
    }
    @Test fun cancellation_isHonoured() {
        var n = 0
        try { engine.recognize(Pen.write("25+15=", 1)) { ++n > 3 }; fail("expected cancellation") } catch (_: RecognitionCancelled) {}
    }
}

class EqualsDetectorTest {
    @Test fun writingEqualsLast_isDetected() { assertTrue(EqualsDetector.endsWithEquals(Pen.write("25+15=", 1))) }
    @Test fun noEquals_isNotDetected() { assertFalse(EqualsDetector.endsWithEquals(Pen.write("25+15", 1))) }
    @Test fun singleMinusAtEnd_isNotEquals() { assertFalse(EqualsDetector.endsWithEquals(Pen.write("25-", 1))) }
    @Test fun equalsInTheMiddle_isNotTrailing() { assertFalse(EqualsDetector.endsWithEquals(Pen.write("2x=5", 1).let { it })) }
}

class RecognitionCacheTest {
    @Test fun sameStrokes_hitCache_evenAfterMoving() {
        val cache = RecognitionCache(8)
        val a = Pen(1).at(100f, 300f).text("25+15").strokes
        val moved = a.map { it.translated(777f, -300f) }
        assertEquals(cache.signature(a), cache.signature(moved))
    }
    @Test fun differentStrokes_differentSignature() {
        val cache = RecognitionCache(8)
        assertNotEquals(cache.signature(Pen.write("25+15", 1)), cache.signature(Pen.write("25+16", 1)))
    }
    @Test fun lruEviction() {
        val cache = RecognitionCache(2)
        val r = HandwritingMathRecognizer().recognize(Pen.write("1", 1))
        cache.put(1, r); cache.put(2, r); cache.get(1); cache.put(3, r)
        assertNotNull(cache.get(1)); assertNull(cache.get(2)); assertNotNull(cache.get(3))
    }
    @Test fun cachedRecognizer_skipsRecomputation_onUndoToIdenticalStrokes() {
        var calls = 0
        val inner = object : MathRecognitionEngine {
            val real = HandwritingMathRecognizer()
            override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult { calls++; return real.recognize(strokes, isCancelled) }
        }
        val cached = CachingRecognizer(inner, RecognitionCache(8))
        val s = Pen.write("25+15", 1)
        cached.recognize(s); cached.recognize(s); cached.recognize(s.map { it.translated(50f, 50f) })
        assertEquals(1, calls)
    }
}
