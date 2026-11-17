package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.comer.ComerEncoding
import com.bsoft.bhandbook.core.comer.ComerInput
import com.bsoft.bhandbook.core.comer.ComerMathRecognitionEngine
import com.bsoft.bhandbook.core.comer.ComerModelBackend
import com.bsoft.bhandbook.core.comer.ComerRecognizer
import com.bsoft.bhandbook.core.comer.ComerTestData
import com.bsoft.bhandbook.core.comer.ComerVocab
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.domain.model.Stroke
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

class FallbackRecognitionEngineTest {
    private val ink = Pen.write("25+15", 1)
    private fun result(text: String, engine: String, reliable: Boolean = true) =
        RecognitionResult(MathExpression(MathStructure.Row(emptyList()), text, emptyList()), 0.9f, reliable, engine = engine)
    private fun engine(f: (List<Stroke>) -> RecognitionResult) = object : MathRecognitionEngine {
        var calls = 0
        override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult { calls++; return f(strokes) }
    }

    @Test fun usesThePrimaryEngineWhenItIsAvailable() {
        val p = engine { result("primary", "comer") }; val f = engine { result("fallback", "geometric") }
        val r = FallbackRecognitionEngine({ p }, f).recognize(ink)
        assertEquals("primary", r.expression.linear); assertEquals(0, f.calls)
    }
    @Test fun fallsBackWhenThePrimaryIsUnavailable_andSaysSo() {
        val f = engine { result("fallback", "geometric") }
        val r = FallbackRecognitionEngine({ null }, f).recognize(ink)
        assertEquals("fallback", r.expression.linear); assertTrue(r.warnings.any { it.contains("fallback", ignoreCase = true) || it.contains("built-in", ignoreCase = true) })
    }
    @Test fun fallsBackWhenThePrimaryFailsAtRuntime_forExampleOutOfMemory() {
        val p = engine { throw IllegalStateException("onnx exploded") }; val f = engine { result("fallback", "geometric") }
        val notes = ArrayList<String>()
        val r = FallbackRecognitionEngine({ p }, f) { notes += it }.recognize(ink)
        assertEquals("fallback", r.expression.linear); assertEquals(1, notes.size); assertTrue(notes[0].contains("onnx exploded"))
    }
    @Test fun cancellationIsNeverSwallowed_orTurnedIntoAFallback() {
        val p = engine { throw RecognitionCancelled() }; val f = engine { result("fallback", "geometric") }
        try { FallbackRecognitionEngine({ p }, f).recognize(ink); fail("expected cancellation") } catch (_: RecognitionCancelled) {}
        assertEquals(0, f.calls)
    }
    @Test fun aSecondFailureInTheFallbackIsNotHidden() {
        val p = engine { throw IllegalStateException("a") }; val f = engine { throw IllegalArgumentException("b") }
        try { FallbackRecognitionEngine({ p }, f).recognize(ink); fail() } catch (e: IllegalArgumentException) { assertEquals("b", e.message) }
    }

}

class LinkageFailureTest {
    private val ink = Pen.write("25", 1)
    private fun failing(t: Throwable) = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult = throw t }
    private val fallback = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = RecognitionResult(MathExpression(MathStructure.Row(emptyList()), "ok", emptyList()), 0.9f, true) }

    /** A release build that shrinks or renames ONNX Runtime classes fails with these (they are Errors, not Exceptions): the app must keep working. */
    @Test fun missingClassesOrMethodsOrNativeLibraries_fallBackInsteadOfCrashing() {
        for (t in listOf<Throwable>(NoSuchMethodError("OnnxTensor.createTensor"), NoClassDefFoundError("ai/onnxruntime/OrtEnvironment"), UnsatisfiedLinkError("libonnxruntime4j_jni.so"), IncompatibleClassChangeError("x"))) {
            val seen = ArrayList<String>()
            val r = FallbackRecognitionEngine({ failing(t) }, fallback, { seen += it }).recognize(ink)
            assertEquals("ok", r.expression.linear); assertTrue(r.warnings.any { it.contains("built-in", ignoreCase = true) }); assertTrue("reported: $seen", seen.isNotEmpty())
        }
    }
    @Test fun genuineProgrammingErrorsInTheFallbackItself_areStillVisible() {
        try { FallbackRecognitionEngine({ null }, failing(IllegalStateException("boom"))).recognize(ink); fail("expected the fallback's own failure to surface") } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
    }
    @Test fun outOfMemoryStillFallsBack_andCancellationStillPropagates() {
        assertEquals("ok", FallbackRecognitionEngine({ failing(OutOfMemoryError()) }, fallback).recognize(ink).expression.linear)
        try { FallbackRecognitionEngine({ failing(RecognitionCancelled()) }, fallback).recognize(ink); fail("must propagate") } catch (_: RecognitionCancelled) {}
    }
}

class RecognitionHintTest {
    private val ink = Pen.write("25", 1)
    private class Recording(val name: String) : MathRecognitionEngine {
        val hints = ArrayList<RecognitionHint>()
        override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = recognize(strokes, RecognitionHint.ANY, isCancelled)
        override fun recognize(strokes: List<Stroke>, hint: RecognitionHint, isCancelled: () -> Boolean): RecognitionResult {
            hints += hint; return RecognitionResult(MathExpression(MathStructure.Row(emptyList()), name, emptyList()), 0.9f, true)
        }
    }
    @Test fun anEngineThatIgnoresHints_stillWorks_viaTheDefaultMethod() {
        val e = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = RecognitionResult(MathExpression.Empty, 1f, true) }
        assertEquals(1f, e.recognize(ink, RecognitionHint.NUMBER).confidence, 0f)
    }
    @Test fun theFallbackEngine_passesTheHintToBothEngines() {
        val p = Recording("p"); val f = Recording("f")
        FallbackRecognitionEngine({ p }, f).recognize(ink, RecognitionHint.NUMBER); assertEquals(listOf(RecognitionHint.NUMBER), p.hints)
        FallbackRecognitionEngine({ null }, f).recognize(ink, RecognitionHint.NUMBER); assertEquals(listOf(RecognitionHint.NUMBER), f.hints)
    }
    @Test fun theCache_keepsReadingsForDifferentHintsApart_andReusesTheSameHint() {
        val inner = Recording("x"); val cached = CachingRecognizer(inner, RecognitionCache(8))
        cached.recognize(ink, RecognitionHint.ANY); cached.recognize(ink, RecognitionHint.NUMBER); cached.recognize(ink, RecognitionHint.NUMBER); cached.recognize(ink)
        assertEquals(listOf(RecognitionHint.ANY, RecognitionHint.NUMBER), inner.hints)
    }
}

class ExclusiveAccessTest {
    @Test fun shutdownWaitsForARunningRecognition() {
        val vocabJson = ComerTestData.vocabJson(); assumeTrue("vocab.json not fetched", vocabJson != null)
        val vocab = ComerVocab.parse(vocabJson!!)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        val backend = object : ComerModelBackend {
            override val vocab = vocab
            override fun encode(input: ComerInput): ComerEncoding {
                started.countDown(); release.await(); order += "inference finished"
                return object : ComerEncoding {
                    override fun nextLogits(ids: IntArray) = FloatArray(vocab.size).also { it[vocab.eos] = 9f }
                    override fun close() {}
                }
            }
        }
        val engine = ComerMathRecognitionEngine(ComerRecognizer(backend, DefaultMathParser()))
        val worker = Thread { engine.recognize(Pen.write("25+15", 1)) }.also { it.start() }
        started.await()
        val closer = Thread { engine.exclusive { order += "sessions closed" } }.also { it.start() }
        Thread.sleep(150)
        assertEquals("shutdown must wait while inference is running", emptyList<String>(), order.toList())
        release.countDown(); worker.join(5000); closer.join(5000)
        assertEquals(listOf("inference finished", "sessions closed"), order.toList())
    }
}
