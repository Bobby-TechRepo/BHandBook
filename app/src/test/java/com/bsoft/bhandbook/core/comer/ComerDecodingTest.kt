package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.recognition.RecognitionCancelled
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reproduces tools/comer-reference/src/ort-fake.ts exactly: the same scripted logits drive the reference engine and this port. */
class ScriptedBackend(private val variant: Int, private val vocabSize: Int = 113) : ComerDecoderBackend {
    var calls = 0
    override fun nextLogits(ids: IntArray): FloatArray {
        calls++
        var h = 17
        for (id in ids) h = h * 31 + id
        val row = FloatArray(vocabSize)
        for (i in 0 until vocabSize) {
            val x = (h * (i + 13) + i * i * 7).toLong() and 0xFFFFFFFFL
            row[i] = ((x % 997).toDouble() / 50.0 - 8.0).toFloat()
        }
        val last = ids.last()
        if (ids.size > 5 + variant % 7) row[2] = (row[2].toDouble() + 14.0).toFloat()
        when (variant % 3) {
            1 -> row[last] = (row[last].toDouble() + 6.0).toFloat()
            2 -> when {
                last == 53 -> row[110] = (row[110].toDouble() + 10.0).toFloat()
                last == 110 -> { val i = 11 + Math.abs(h) % 10; row[i] = (row[i].toDouble() + 9.0).toFloat() }
                last in 11..20 && ids.size > 4 -> row[112] = (row[112].toDouble() + 8.0).toFloat()
                last == 112 -> row[110] = (row[110].toDouble() + 5.0).toFloat()
            }
        }
        return row
    }
}

class ComerDecodingTest {
    private fun mode(s: String) = when (s) { "auto" -> ComerMode.AUTO; "number" -> ComerMode.NUMBER; else -> ComerMode.EXPRESSION }

    @Test fun matchesTheReferenceEngine_onAll405Scenarios() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null); vocab!!
        val cases = ComerTestData.list(ComerTestData.json("decode_cases.json"))
        assertEquals(405, cases.size)
        var nonEmpty = 0
        for (c in cases) {
            val m = ComerTestData.map(c)
            val variant = (m["variant"] as Double).toInt(); val beam = (m["beam"] as Double).toInt(); val md = m["mode"] as String
            val out = ComerDecoding.decode(ScriptedBackend(variant), vocab, ComerDecodeConfig(beamWidth = beam), mode(md))
            val label = "variant=$variant beam=$beam mode=$md"
            assertEquals("$label latex", m["latex"], out.latex)
            assertEquals("$label tokenIds", ComerTestData.ints(m["tokenIds"]), out.tokenIds)
            if (out.latex.isNotEmpty()) nonEmpty++
        }
        assertTrue("scenarios must be non-trivial, got $nonEmpty non-empty", nonEmpty > 300)
    }

    @Test fun greedyStopsAtEos() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null)
        val out = ComerDecoding.decode(object : ComerDecoderBackend {
            override fun nextLogits(ids: IntArray) = FloatArray(113).also { it[if (ids.size < 3) 11 + ids.size else 2] = 10f }
        }, vocab!!, ComerDecodeConfig(beamWidth = 1), ComerMode.EXPRESSION)
        assertEquals("1 2", out.latex)       // digits '1','2' then <eos>
    }
    @Test fun greedyAllowsThreeRepeatsButNotFour() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null)
        val out = ComerDecoding.decode(object : ComerDecoderBackend { override fun nextLogits(ids: IntArray) = FloatArray(113).also { it[12] = 10f } }, vocab!!, ComerDecodeConfig(beamWidth = 1), ComerMode.EXPRESSION)
        assertEquals(listOf(12, 12, 12), out.tokenIds)
    }
    @Test fun maxDecodeLengthIsEnforced_andNeverLoopsForever() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null)
        var n = 0
        val alternating = object : ComerDecoderBackend { override fun nextLogits(ids: IntArray) = FloatArray(113).also { it[if (ids.size % 2 == 0) 6 else 8] = 10f; n++ } }
        val out = ComerDecoding.decode(alternating, vocab!!, ComerDecodeConfig(beamWidth = 1, maxSteps = 7), ComerMode.EXPRESSION)
        assertEquals(7, out.tokenIds.size); assertEquals(7, n)
    }
    @Test fun numberModeNeverEmitsLettersOrFunctions() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null); vocab!!
        val allowed = ComerModes.numberModeTokens(vocab)
        for (variant in 0 until 12) {
            val out = ComerDecoding.decode(ScriptedBackend(variant), vocab, ComerDecodeConfig(beamWidth = 3), ComerMode.NUMBER)
            assertTrue("variant $variant produced ${out.tokenIds}", out.tokenIds.all { it in allowed })
        }
    }
    @Test fun beamReturnsRankedCandidates_bestFirst_withScores() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null); vocab!!
        val out = ComerDecoding.decode(ScriptedBackend(3), vocab, ComerDecodeConfig(beamWidth = 3), ComerMode.EXPRESSION)
        assertTrue(out.candidates.size in 1..3)
        val norm = out.candidates.map { it.logProb / maxOf(it.ids.size + 1, 1) }
        assertEquals(norm.sortedDescending(), norm)
        assertTrue(out.candidates.all { it.logProb <= 0.0 })
    }
    @Test fun cancellationIsHonouredBetweenDecoderCalls() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null)
        var checks = 0
        try { ComerDecoding.decode(ScriptedBackend(0), vocab!!, ComerDecodeConfig(beamWidth = 2), ComerMode.EXPRESSION) { ++checks > 2 }; fail("expected cancellation") } catch (_: RecognitionCancelled) {}
    }
    @Test fun decoderIsCalledOnlyOncePerBeamPerStep() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null)
        val b = ScriptedBackend(2)
        ComerDecoding.decode(b, vocab!!, ComerDecodeConfig(beamWidth = 3, maxSteps = 10), ComerMode.EXPRESSION)
        assertTrue("calls=${b.calls}", b.calls <= 3 * 10)
    }
}
