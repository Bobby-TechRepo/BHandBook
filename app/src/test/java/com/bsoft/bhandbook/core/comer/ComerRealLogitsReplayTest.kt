package com.bsoft.bhandbook.core.comer

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Decoder queries recorded from the REAL CoMER decoder while the reference engine decoded four Kotlin-preprocessed inputs
 * (beam 1/2/3, auto/number mode). Replaying them checks the Kotlin beam search against real numerics, not just scripted ones.
 */
class ComerRealLogitsReplayTest {
    private class Replay(entries: List<Pair<List<Int>, FloatArray>>) : ComerDecoderBackend {
        private val map = entries.associate { it.first to it.second }
        val unknown = ArrayList<List<Int>>()
        override fun nextLogits(ids: IntArray): FloatArray = map[ids.toList()] ?: run { unknown += ids.toList(); FloatArray(113) }
    }

    private fun replayFor(root: Map<String, Any?>, case: String): Replay {
        val entries = ComerTestData.list(ComerTestData.map(root["cases"])[case]).map { e ->
            val m = ComerTestData.map(e)
            val raw = Base64.getDecoder().decode(m["logits"] as String)
            val f = FloatArray(raw.size / 4); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f)
            ComerTestData.ints(m["ids"]) to f
        }
        return Replay(entries)
    }
    private fun root() = ComerTestData.map(com.bsoft.bhandbook.core.codec.MiniJson.parse(String(ComerTestData.gunzip("real_replay.json.gz"), Charsets.UTF_8)))

    @Test fun kotlinDecoderReproducesTheReference_onRealModelLogits() {
        val vocab = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", vocab != null); vocab!!
        val root = root(); val expected = ComerTestData.list(root["expected"]).map { ComerTestData.map(it) }
        assertEquals(24, expected.size)
        val replays = HashMap<String, Replay>()
        for (e in expected) {
            val case = e["case"] as String; val replay = replays.getOrPut(case) { replayFor(root, case) }
            val beam = (e["beam"] as Double).toInt(); val mode = if (e["mode"] == "number") ComerMode.NUMBER else ComerMode.AUTO
            val out = ComerDecoding.decode(replay, vocab, ComerDecodeConfig(beamWidth = beam), mode)
            val label = "$case beam=$beam mode=${e["mode"]}"
            assertEquals("$label latex", e["latex"], out.latex)
            assertEquals("$label ids", ComerTestData.ints(e["tokenIds"]), out.tokenIds)
        }
        for ((case, r) in replays) assertTrue("$case: Kotlin asked for prefixes the reference never queried: ${r.unknown.take(3)}", r.unknown.isEmpty())
    }

    @Test fun realOutputsAreRecognisableMathematics() {
        val root = root(); val expected = ComerTestData.list(root["expected"]).map { ComerTestData.map(it) }
        val byCase = expected.filter { it["beam"] == 3.0 && it["mode"] == "auto" }.associate { it["case"] as String to it["latex"] as String }
        assertEquals("2 5 + 1 5", byCase["add_25_15_w1@1.0"]); assertEquals("\\frac { 3 } { 4 }", byCase["frac_3_4_w2@1.0"])
        assertEquals("2 x + 5 = 1 5", byCase["eq_2x_w3@1.0"])
        assertTrue(byCase["add_eq_w1@1.0"]!!.startsWith("2 5 + 1 5 ="))
    }
}
