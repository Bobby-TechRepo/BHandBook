package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.codec.InkSampleCodec
import com.bsoft.bhandbook.core.codec.MiniJson
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.MathStatement
import com.bsoft.bhandbook.core.math.ParseResult
import com.bsoft.bhandbook.core.recognition.RecognitionCancelled
import com.bsoft.bhandbook.domain.model.Stroke
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** A backend whose "model" is the recorded real decoder (per input) or a scripted token sequence. Counts calls and tracks closing. */
class FakeBackend(override val vocab: ComerVocab, private val logitsFor: (IntArray) -> FloatArray) : ComerModelBackend {
    var encodeCalls = 0; var closed = 0; var lastInput: ComerInput? = null
    override fun encode(input: ComerInput): ComerEncoding {
        encodeCalls++; lastInput = input
        return object : ComerEncoding {
            override fun nextLogits(ids: IntArray) = logitsFor(ids)
            override fun close() { closed++ }
        }
    }
}

class ComerRecognizerTest {
    private val parser = DefaultMathParser()
    private fun fixture(name: String): List<Stroke> = InkSampleCodec.decode(ComerTestData.text("$name.json"))!!.strokes

    private fun replayBackend(vocab: ComerVocab, case: String, file: String = "real_replay.json.gz"): FakeBackend {
        val root = ComerTestData.map(MiniJson.parse(String(ComerTestData.gunzip(file), Charsets.UTF_8)))
        val map = ComerTestData.list(ComerTestData.map(root["cases"])[case]).associate { e ->
            val m = ComerTestData.map(e); val raw = Base64.getDecoder().decode(m["logits"] as String)
            val f = FloatArray(raw.size / 4); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
            ComerTestData.ints(m["ids"]) to f
        }
        return FakeBackend(vocab) { ids -> map[ids.toList()] ?: error("decoder asked for a prefix the real model was never queried with: ${ids.toList()}") }
    }

    /** Forces the decoder to emit exactly [tokens] then <eos>. */
    private fun scripted(vocab: ComerVocab, tokens: List<String>) = FakeBackend(vocab) { ids ->
        val i = ids.size - 1
        FloatArray(vocab.size).also { row -> row[if (i < tokens.size) vocab.id(tokens[i])!! else vocab.eos] = 30f }
    }

    private fun recognizer(b: ComerModelBackend, beam: Int = 3) = ComerRecognizer(b, parser, ComerRecognizerConfig(beamWidth = beam))
    private fun vocabOrSkip(): ComerVocab { val v = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", v != null); return v!! }

    // ---------------------------------------------------------------- real recorded model behaviour
    @Test fun addition_isRecognisedConvertedAndParsed() {
        val v = vocabOrSkip(); val b = replayBackend(v, "add_25_15_w1@1.0")
        val r = recognizer(b).recognize(fixture("add_25_15_w1")) as ComerRecognition.Recognized
        assertEquals("2 5 + 1 5", r.latex); assertEquals("25+15", r.linear)
        assertTrue(parser.parse(r.linear) is ParseResult.Success)
        assertEquals(1, b.encodeCalls); assertEquals("encoder input has the model's height", 256, b.lastInput!!.height)
    }
    @Test fun fraction() {
        val v = vocabOrSkip(); val r = recognizer(replayBackend(v, "frac_3_4_w2@1.0")).recognize(fixture("frac_3_4_w2")) as ComerRecognition.Recognized
        assertEquals("\\frac { 3 } { 4 }", r.latex); assertEquals("((3)/(4))", r.linear)
    }
    @Test fun equation() {
        val v = vocabOrSkip(); val r = recognizer(replayBackend(v, "eq_2x_w3@1.0")).recognize(fixture("eq_2x_w3")) as ComerRecognition.Recognized
        assertEquals("2x+5=15", r.linear); assertTrue((parser.parse(r.linear) as ParseResult.Success).statement is MathStatement.Equation)
    }
    @Test fun doubledEqualsFromATwoStrokeEqualsSign_isCollapsedToOne() {
        val v = vocabOrSkip(); val r = recognizer(replayBackend(v, "add_eq_w1@1.0")).recognize(fixture("add_eq_w1")) as ComerRecognition.Recognized
        assertEquals("25+15=", r.linear); assertTrue((parser.parse(r.linear) as ParseResult.Success).requestedExplicitly)
        assertTrue("the original decoder output is preserved", r.candidates.first().tokenIds.isNotEmpty())
    }
    @Test fun candidatesAreRankedWithScoresTimingsAndAreNotCalledProbabilities() {
        val v = vocabOrSkip(); val r = recognizer(replayBackend(v, "add_25_15_w1@1.0")).recognize(fixture("add_25_15_w1")) as ComerRecognition.Recognized
        assertTrue(r.candidates.isNotEmpty()); assertEquals(0, r.selectedRank)
        assertTrue(r.candidates.all { it.logProb <= 0.0 }); assertTrue(r.confidence in 0.0..1.0)
        assertTrue(r.timings.totalMs >= r.timings.encoderMs && r.timings.totalMs >= 0)
        assertEquals("confidence is documented as a heuristic", true, ComerRecognition.CONFIDENCE_IS_HEURISTIC)
    }

    @Test fun realCorrectResults_haveHighHeuristicConfidence_soTheDefaultGateDoesNotNagOnGoodInk() {
        val v = vocabOrSkip()
        for ((case, fx) in listOf("add_25_15_w1@1.0" to "add_25_15_w1", "frac_3_4_w2@1.0" to "frac_3_4_w2", "eq_2x_w3@1.0" to "eq_2x_w3", "add_eq_w1@1.0" to "add_eq_w1")) {
            val r = recognizer(replayBackend(v, case)).recognize(fixture(fx)) as ComerRecognition.Recognized
            println("CONFIDENCE $case = ${"%.3f".format(r.confidence)}  (min default gate ${ComerRecognizerConfig().minMeanTokenProbability})")
            assertTrue("$case confidence ${r.confidence}", r.confidence >= ComerRecognizerConfig().minMeanTokenProbability)
        }
    }

    // ---------------------------------------------------------------- gating and error paths
    @Test fun meaninglessInk_neverTouchesTheModel() {
        val v = vocabOrSkip(); val b = scripted(v, listOf("1"))
        assertTrue(recognizer(b).recognize(fixture("junk_dot")) is ComerRecognition.NotMeaningful)
        assertTrue(recognizer(b).recognize(emptyList()) is ComerRecognition.NotMeaningful)
        assertEquals(0, b.encodeCalls)
    }
    @Test fun unsupportedNotation_isShownButNotPassedToTheCalculator() {
        val v = vocabOrSkip(); val r = recognizer(scripted(v, listOf("\\sum", "a"))).recognize(fixture("add_25_15_w1"))
        assertTrue("$r", r is ComerRecognition.NeedsCorrection); r as ComerRecognition.NeedsCorrection
        assertTrue(r.reason.contains("\\sum")); assertTrue(r.displayText.contains("∑")); assertEquals("\\sum a", r.latex)
    }
    @Test fun incompleteExpression_isNotCalculated() {
        val v = vocabOrSkip(); val r = recognizer(scripted(v, listOf("2", "+", "\\frac", "{", "1", "}"))).recognize(fixture("add_25_15_w1"))
        assertTrue("$r", r is ComerRecognition.NeedsCorrection)
    }
    @Test fun unparsableMath_isNotCalculated() {
        val v = vocabOrSkip(); val r = recognizer(scripted(v, listOf("2", "+", "\\times", "3"))).recognize(fixture("add_25_15_w1"))
        assertTrue("$r", r is ComerRecognition.NeedsCorrection); assertTrue((r as ComerRecognition.NeedsCorrection).reason.isNotBlank())
    }
    @Test fun modelProducingNothing_isReported() {
        val v = vocabOrSkip(); assertTrue(recognizer(scripted(v, emptyList())).recognize(fixture("add_25_15_w1")) is ComerRecognition.NeedsCorrection)
    }
    @Test fun encodingIsAlwaysClosed_evenWhenDecodingFails() {
        val v = vocabOrSkip()
        val b = FakeBackend(v) { error("decoder exploded") }
        try { recognizer(b).recognize(fixture("add_25_15_w1")); fail("expected exception") } catch (_: IllegalStateException) {}
        assertEquals(1, b.closed)
        val ok = scripted(v, listOf("1")); recognizer(ok, beam = 1).recognize(fixture("add_25_15_w1")); assertEquals(ok.closed, ok.encodeCalls)
    }
    @Test fun cancellation_propagates_andResourcesAreReleased() {
        val v = vocabOrSkip(); val b = scripted(v, listOf("1", "2", "3"))
        var n = 0
        try { recognizer(b).recognize(fixture("add_25_15_w1")) { ++n > 3 }; fail("expected cancellation") } catch (_: RecognitionCancelled) {}
        assertEquals(1, b.closed)
    }
    @Test fun cancelledBeforeStart_doesNoWork() {
        val v = vocabOrSkip(); val b = scripted(v, listOf("1"))
        try { recognizer(b).recognize(fixture("add_25_15_w1")) { true }; fail() } catch (_: RecognitionCancelled) {}
        assertEquals(0, b.encodeCalls)
    }
    @Test fun resultIsIndependentOfWhereOnTheCanvasTheExpressionWasWritten() {
        val v = vocabOrSkip(); val seen = ArrayList<FloatArray>()
        val b = FakeBackend(v) { FloatArray(v.size).also { it[v.eos] = 9f } }
        val a = fixture("add_25_15_w1"); val moved = a.map { it.translated(4000f, 2500f) }
        val ra = ComerRecognizer(b, parser, ComerRecognizerConfig()); ra.recognize(a); val first = b.lastInput!!.tensor.copyOf()
        ra.recognize(moved); seen += first; seen += b.lastInput!!.tensor
        var maxDiff = 0f; for (i in first.indices) maxDiff = maxOf(maxDiff, Math.abs(seen[0][i] - seen[1][i]))
        assertTrue("model input must not depend on canvas position (diff $maxDiff)", maxDiff < 0.15f)
    }

    // ---------------------------------------------------------------- candidate selection (pure)
    private fun ids(v: ComerVocab, vararg t: String) = t.map { v.id(it)!! }
    @Test fun selector_prefersTheHighestRankedValidCandidate_skippingUnparsableOnes() {
        val v = vocabOrSkip()
        val cands = listOf(ComerCandidate(ids(v, "2", "+", "\\times", "3"), -0.5), ComerCandidate(ids(v, "2", "+", "3"), -0.9), ComerCandidate(ids(v, "2", "-", "3"), -1.2))
        val s = ComerCandidateSelector.select(cands, v, parser, ComerMode.AUTO)
        assertEquals(1, s.rank); assertEquals("2+3", (s.conversion as LatexConversion.Success).linear)
        assertFalse("the skipped candidate is reported as invalid", s.reports[0].usable)
    }
    @Test fun selector_doesNotSwapAnUnsupportedTopCandidateForACalculableOne() {
        val v = vocabOrSkip()
        val s = ComerCandidateSelector.select(listOf(ComerCandidate(ids(v, "1", "\\pm", "2"), -0.4), ComerCandidate(ids(v, "1", "+", "2"), -0.6)), v, parser, ComerMode.AUTO)
        assertEquals(0, s.rank); assertTrue(s.conversion is LatexConversion.Unsupported)
    }
    @Test fun selector_withNoUsableCandidate_returnsNoRank() {
        val v = vocabOrSkip()
        val s = ComerCandidateSelector.select(listOf(ComerCandidate(ids(v, "\\frac", "{", "1", "}"), -0.4)), v, parser, ComerMode.AUTO)
        assertNull(s.rank); assertTrue(s.reason!!.isNotBlank())
    }
    @Test fun selector_repairsBracesBeforeJudgingACandidate() {
        val v = vocabOrSkip()
        val s = ComerCandidateSelector.select(listOf(ComerCandidate(ids(v, "\\sqrt", "{", "9"), -0.4)), v, parser, ComerMode.AUTO)
        assertEquals("√(9)", (s.conversion as LatexConversion.Success).linear)
        assertEquals("the unrepaired decoder output stays available", ids(v, "\\sqrt", "{", "9"), s.reports[0].tokenIds)
    }


    // ---------------------------------------------------------------- stress findings: long expressions and a spurious tail after '='
    private fun longReplay(v: ComerVocab, case: String) = replayBackend(v, case, "real_replay_long.json.gz")

    @Test fun inkThatEndsWithAnEqualsSign_dropsTheModelsSpuriousTrailingSymbol() {
        val v = vocabOrSkip(); val b = longReplay(v, "len20_long_sum_w1")
        val r = recognizer(b).recognize(fixture("len20_long_sum_w1")) as ComerRecognition.Recognized
        assertEquals("the real model emitted '... = 1'", "12+34+56+78+90+12+34=", r.linear)
        assertTrue("the adjustment is reported, not hidden: ${r.adjustments}", r.adjustments.any { it.contains("after") })
        assertTrue((parser.parse(r.linear) as ParseResult.Success).requestedExplicitly)
    }
    @Test fun theMixedLongExpression_isRecognisedUnchanged() {
        val v = vocabOrSkip(); val r = recognizer(longReplay(v, "len13_mixed_w1")).recognize(fixture("len13_mixed_w1")) as ComerRecognition.Recognized
        assertEquals("12×34-56+78", r.linear); assertTrue(r.adjustments.isEmpty())
    }
    @Test fun selector_dropsEverythingAfterTheFinalEquals_onlyWhenTheInkEndsWithOne() {
        val v = vocabOrSkip(); val c = listOf(ComerCandidate(ids(v, "2", "+", "3", "=", "5"), -0.3))
        val with = ComerCandidateSelector.select(c, v, parser, ComerMode.AUTO, inkEndsWithEquals = true)
        assertEquals("2+3=", (with.conversion as LatexConversion.Success).linear); assertEquals("2 + 3 =", with.latex); assertTrue(with.adjustments.isNotEmpty())
        val without = ComerCandidateSelector.select(c, v, parser, ComerMode.AUTO, inkEndsWithEquals = false)
        assertEquals("2+3=5", (without.conversion as LatexConversion.Success).linear); assertTrue(without.adjustments.isEmpty())
    }
    @Test fun selector_leavesTextWithoutAnEqualsSignAlone_evenIfTheInkEndsWithOne() {
        val v = vocabOrSkip()
        val s = ComerCandidateSelector.select(listOf(ComerCandidate(ids(v, "2", "+", "3"), -0.3)), v, parser, ComerMode.AUTO, inkEndsWithEquals = true)
        assertEquals("2+3", (s.conversion as LatexConversion.Success).linear); assertTrue(s.adjustments.isEmpty())
    }
    @Test fun selector_onlyTrimsAfterTheLastEquals_soEquationsKeepTheirRightHandSide() {
        val v = vocabOrSkip()
        val s = ComerCandidateSelector.select(listOf(ComerCandidate(ids(v, "x", "=", "2", "=", "7"), -0.3)), v, parser, ComerMode.AUTO, inkEndsWithEquals = true)
        assertEquals("x=2=", s.latex.replace(" ", ""))
    }

    @Test fun wideInputs_useGreedyDecoding_becauseDecoderCostGrowsWithOutputLength() {
        val v = vocabOrSkip()
        fun calls(strokes: List<Stroke>, cfg: ComerRecognizerConfig): Int {
            var n = 0
            val b = FakeBackend(v) { ids -> n++; FloatArray(v.size).also { row -> row[if (ids.size - 1 < 3) v.id(listOf("2", "+", "3")[ids.size - 1])!! else v.eos] = 30f } }
            ComerRecognizer(b, parser, cfg).recognize(strokes); return n
        }
        val narrow = calls(fixture("add_25_15_w1"), ComerRecognizerConfig())
        val wide = calls(fixture("len13_mixed_w1"), ComerRecognizerConfig())
        assertEquals("greedy: one decoder call per generated token plus <eos>", 4, wide)
        assertTrue("beam search explores several hypotheses per step (calls=$narrow)", narrow > 4)
        assertTrue("the width threshold is configurable", calls(fixture("len13_mixed_w1"), ComerRecognizerConfig(longInputWidth = Int.MAX_VALUE)) > 4)
    }
    @Test fun adaptiveBeam_neverIncreasesTheBeamWidth() {
        val v = vocabOrSkip(); var n = 0
        val b = FakeBackend(v) { ids -> n++; FloatArray(v.size).also { row -> row[if (ids.size - 1 < 3) v.id(listOf("2", "+", "3")[ids.size - 1])!! else v.eos] = 30f } }
        ComerRecognizer(b, parser, ComerRecognizerConfig(beamWidth = 1)).recognize(fixture("add_25_15_w1")); assertEquals(4, n)
    }


    // ---------------------------------------------------------------- very long input: squeezed by the 1024 px cap
    private fun anyReading(v: ComerVocab) = scripted(v, listOf("1", "+", "2"))

    @Test fun expressionsSqueezedByTheWidthCap_areNotTrustedAutomatically() {
        val v = vocabOrSkip()
        val r = recognizer(anyReading(v)).recognize(fixture("len35_extreme_w1")) as ComerRecognition.Recognized
        assertTrue("a very long single line must carry a warning: ${r.warnings}", r.warnings.any { it.contains("long", ignoreCase = true) })
    }
    @Test fun normalExpressions_carryNoLengthWarning() {
        val v = vocabOrSkip()
        for (n in listOf("add_25_15_w1", "len13_mixed_w1", "len20_long_sum_w1")) {
            val r = recognizer(anyReading(v)).recognize(fixture(n)) as ComerRecognition.Recognized
            assertTrue("$n: ${r.warnings}", r.warnings.isEmpty())
        }
    }
    @Test fun lengthGuard_isConfigurable() {
        val v = vocabOrSkip()
        val off = ComerRecognizer(anyReading(v), parser, ComerRecognizerConfig(minContentScale = 0.0)).recognize(fixture("len35_extreme_w1")) as ComerRecognition.Recognized
        assertTrue(off.warnings.isEmpty())
    }
    @Test fun engineAdapter_treatsAWarnedResultAsUnreliable_evenWithHighConfidence() {
        val v = vocabOrSkip()
        val engine = ComerMathRecognitionEngine(ComerRecognizer(anyReading(v), parser))
        val long = engine.recognize(fixture("len35_extreme_w1")); val normal = engine.recognize(fixture("add_25_15_w1"))
        assertFalse(long.reliable); assertTrue(long.warnings.any { it.contains("long", ignoreCase = true) }); assertEquals("1+2", long.expression.linear)
        assertTrue(normal.reliable)
    }


    @Test fun engineReportsEveryRecognitionToAnObserver_soSlowDevicesCanBeDiagnosed() {
        val v = vocabOrSkip(); val seen = ArrayList<ComerRecognition>()
        val engine = ComerMathRecognitionEngine(ComerRecognizer(anyReading(v), parser), onResult = { seen += it })
        engine.recognize(fixture("add_25_15_w1")); engine.recognize(fixture("junk_dot"))
        assertEquals(2, seen.size); assertTrue(seen[0] is ComerRecognition.Recognized); assertTrue(seen[1] is ComerRecognition.NotMeaningful)
    }


    // ---------------------------------------------------------------- number hint (used for the rows of a column)
    /** The model prefers the letter "g" (30) over the digit "9" (10) for a lone glyph, then stops. */
    private fun prefersLetterOverDigit(v: ComerVocab) = FakeBackend(v) { ids ->
        FloatArray(v.size).also { row -> if (ids.size == 1) { row[v.id("g")!!] = 30f; row[v.id("9")!!] = 10f } else row[v.eos] = 30f }
    }
    @Test fun numberMode_forbidsLettersAndPicksTheDigit() {
        val v = vocabOrSkip()
        assertEquals("g", (recognizer(prefersLetterOverDigit(v)).recognize(fixture("add_25_15_w1")) as ComerRecognition.Recognized).linear)
        val r = recognizer(prefersLetterOverDigit(v)).recognize(fixture("add_25_15_w1"), mode = ComerMode.NUMBER) as ComerRecognition.Recognized
        assertEquals("9", r.linear)
    }
    @Test fun theEngineAdapter_mapsTheNumberHintToNumberMode() {
        val v = vocabOrSkip(); val engine = ComerMathRecognitionEngine(ComerRecognizer(prefersLetterOverDigit(v), parser))
        assertEquals("9", engine.recognize(fixture("add_25_15_w1"), com.bsoft.bhandbook.core.recognition.RecognitionHint.NUMBER).expression.linear)
        assertEquals("g", engine.recognize(fixture("add_25_15_w1")).expression.linear)
    }


    /** Three close competing first symbols (g, 9, 8) and nothing else plausible, then the end of the expression. */
    private fun closeCall(v: ComerVocab) = FakeBackend(v) { ids ->
        FloatArray(v.size) { -100f }.also { row -> if (ids.size == 1) { row[v.id("g")!!] = 30f; row[v.id("9")!!] = 29f; row[v.id("8")!!] = 28f } else row[v.eos] = 30f }
    }
    @Test fun theEngineAdapter_offersTheRunnerUpReadingsForOneTapCorrection() {
        val v = vocabOrSkip(); val r = ComerMathRecognitionEngine(ComerRecognizer(closeCall(v), parser)).recognize(fixture("add_25_15_w1"))
        assertEquals("g", r.expression.linear); assertEquals(listOf("9", "8"), r.alternatives)
    }
}
