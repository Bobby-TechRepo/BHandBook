package com.bsoft.bhandbook.data.recognition.comer

import android.content.Context
import android.util.Log
import com.bsoft.bhandbook.core.comer.ComerMathRecognitionEngine
import com.bsoft.bhandbook.core.comer.ComerPoint
import com.bsoft.bhandbook.core.comer.ComerPreprocessor
import com.bsoft.bhandbook.core.comer.ComerStroke
import com.bsoft.bhandbook.core.comer.ComerRecognition
import com.bsoft.bhandbook.core.comer.ComerRecognizer
import com.bsoft.bhandbook.core.comer.ComerRecognizerConfig
import com.bsoft.bhandbook.core.math.MathExpressionParser
import com.bsoft.bhandbook.core.recognition.MathRecognitionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single CoMER runtime for the process: created lazily (and by [warmUp] in the background), reused for every recognition,
 * released on [shutdown] without ever closing sessions under a running inference.
 */
@Singleton
class ComerRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: MathExpressionParser,
) {
    sealed interface Status {
        data object NotLoaded : Status
        data class Ready(val info: ComerModelInfo) : Status
        data class Failed(val message: String) : Status
    }

    private val lock = Any()
    @Volatile var status: Status = Status.NotLoaded
        private set
    private var backend: OnnxComerBackend? = null
    private var engine: ComerMathRecognitionEngine? = null
    var config: ComerRecognizerConfig = ComerRecognizerConfig()

    /** The ready engine, or null if the models are missing/incompatible (the app then uses the built-in recogniser). Loads on first use. */
    fun engine(): MathRecognitionEngine? {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            if (status is Status.Failed) return null                 // do not retry on every stroke; call retry() explicitly
            return try {
                val b = OnnxComerBackend.create(context.assets)
                warmUpInference(b)
                backend = b
                ComerMathRecognitionEngine(ComerRecognizer(b, parser, config), config.minMeanTokenProbability, ::logResult).also { engine = it; status = Status.Ready(b.info) }
            } catch (e: ComerInitException) {
                Log.w(TAG, "CoMER unavailable: ${e.message}", e); status = Status.Failed(e.message ?: "initialisation failed"); null
            } catch (e: Throwable) {
                if (e is OutOfMemoryError || e is Exception || e is LinkageError) { Log.w(TAG, "CoMER failed to start", e); status = Status.Failed(e.message ?: e.javaClass.simpleName); null } else throw e
            }
        }
    }

    /**
     * Runs one throw-away encode + decode step so any lazy first-run work inside ONNX Runtime happens here, in the background at start-up,
     * instead of during the user's first recognition. On one tested phone the first encoder call took 60 s against 0.5 s afterwards.
     */
    private fun warmUpInference(b: OnnxComerBackend) {
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            fun line(x0: Double, y0: Double, x1: Double, y1: Double) = ComerStroke((0..20).map { ComerPoint(x0 + (x1 - x0) * it / 20, y0 + (y1 - y0) * it / 20) }, 3.0)
            val input = ComerPreprocessor.preprocess(listOf(line(0.0, 40.0, 40.0, 40.0), line(20.0, 20.0, 20.0, 60.0), line(70.0, 0.0, 70.0, 80.0), line(100.0, 40.0, 220.0, 40.0), line(100.0, 60.0, 220.0, 60.0)))
            b.encode(input).use { it.nextLogits(intArrayOf(b.vocab.sos)) }
            Log.i(TAG, "warm-up inference took ${android.os.SystemClock.elapsedRealtime() - start} ms")
        } catch (e: Exception) { Log.w(TAG, "warm-up inference failed (recognition will still work): ${e.message}") }
    }

    /** One logcat line per recognition so slow targets are obvious (tag ComerRuntime). */
    private fun logResult(r: ComerRecognition) {
        val t = when (r) { is ComerRecognition.Recognized -> r.timings; is ComerRecognition.NeedsCorrection -> r.timings; ComerRecognition.NotMeaningful -> return }
        val text = when (r) { is ComerRecognition.Recognized -> r.latex; is ComerRecognition.NeedsCorrection -> r.latex; else -> "" }
        Log.i(TAG, "recognised \"$text\" in ${t.totalMs} ms (preprocess ${t.preprocessMs}, encoder ${t.encoderMs}, decoder ${t.decoderMs})")
        if (t.totalMs > SLOW_MS) Log.w(TAG, "recognition is slow (${t.totalMs} ms). An emulator without hardware acceleration, or with a CPU image that does not match the host, is the usual cause.")
    }

    /** Pre-loads the models (call from a background thread) so the first recognition has no start-up delay. */
    fun warmUp() { engine() }

    fun retry() { synchronized(lock) { if (status is Status.Failed) status = Status.NotLoaded } }

    /** Releases the native sessions (e.g. under memory pressure). They are recreated lazily on the next recognition. */
    fun shutdown() {
        val e: ComerMathRecognitionEngine?; val b: OnnxComerBackend?
        synchronized(lock) { e = engine; b = backend; engine = null; backend = null; if (status is Status.Ready) status = Status.NotLoaded }
        // wait for a running inference to finish before closing the sessions it uses
        if (e != null) e.exclusive { b?.close() } else b?.close()
    }

    private companion object { const val TAG = "ComerRuntime"; const val SLOW_MS = 3_000L }
}
