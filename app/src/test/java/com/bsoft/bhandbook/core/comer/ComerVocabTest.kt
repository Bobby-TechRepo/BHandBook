package com.bsoft.bhandbook.core.comer

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ComerVocabTest {
    private val small = """{"word2idx":{"<pad>":0,"<sos>":1,"<eos>":2,"a":3,"\\frac":4},"idx2word":{"0":"<pad>","1":"<sos>","2":"<eos>","3":"a","4":"\\frac"},"special_tokens":{"pad":0,"sos":1,"eos":2},"vocab_size":5}"""

    @Test fun parsesAWellFormedVocabulary() {
        val v = ComerVocab.parse(small)
        assertEquals(5, v.size); assertEquals(0, v.pad); assertEquals(1, v.sos); assertEquals(2, v.eos)
        assertEquals("\\frac", v.word(4)); assertEquals(4, v.id("\\frac")); assertNull(v.id("zzz")); assertNull(v.word(99))
    }
    @Test fun decodeSkipsSpecialsAndUnknownIds() { assertEquals(listOf("a", "\\frac"), ComerVocab.parse(small).tokens(listOf(1, 3, 99, 4, 2, 0))) }
    @Test fun rejectsInconsistentVocabularies() {
        val bad = mapOf(
            "size mismatch" to small.replace("\"vocab_size\":5", "\"vocab_size\":6"),
            "gap in ids" to small.replace("\"4\":\"\\\\frac\"", "\"7\":\"\\\\frac\"").replace("\"\\\\frac\":4", "\"\\\\frac\":7"),
            "maps disagree" to small.replace("\"3\":\"a\"", "\"3\":\"b\""),
            "special out of range" to small.replace("\"eos\":2", "\"eos\":9"),
            "special token mismatch" to small.replace("\"sos\":1", "\"sos\":3"),
            "not json" to "nope", "missing field" to """{"word2idx":{}}""",
        )
        for ((why, json) in bad) try { ComerVocab.parse(json); fail("accepted: $why") } catch (_: ComerVocab.Invalid) {}
    }
    @Test fun realVocabulary_is113Tokens_withTheDocumentedSpecials() {
        val v = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", v != null); v!!
        assertEquals(113, v.size); assertEquals(0, v.pad); assertEquals(1, v.sos); assertEquals(2, v.eos)
        assertEquals("\\frac", v.word(53)); assertEquals("\\times", v.word(78)); assertEquals("{", v.word(110)); assertEquals("}", v.word(112))
    }
    @Test fun realVocabulary_matchesTheDecoderOutputSize() {
        val v = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", v != null)
        assertEquals("decoder logits dimension reported by the ONNX graph", 113, v!!.size)
    }
    @Test fun numberModeSet_derivedFromTokenStrings_equalsTheReferencesHardcodedIds() {
        val v = ComerTestData.vocab(); assumeTrue("vocab.json not fetched", v != null)
        val reference = setOf(0, 1, 2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 22, 50, 53, 69, 78, 82, 83, 110, 112)
        assertEquals(reference, ComerModes.numberModeTokens(v!!))
    }
    @Test fun numberModeSet_neverHardcodesIds_soAReorderedVocabularyStillWorks() {
        val shuffled = """{"word2idx":{"<pad>":0,"<sos>":1,"<eos>":2,"7":3,"x":4,"{":5,"}":6},"idx2word":{"0":"<pad>","1":"<sos>","2":"<eos>","3":"7","4":"x","5":"{","6":"}"},"special_tokens":{"pad":0,"sos":1,"eos":2},"vocab_size":7}"""
        assertEquals(setOf(0, 1, 2, 3, 5, 6), ComerModes.numberModeTokens(ComerVocab.parse(shuffled)))
    }
}
