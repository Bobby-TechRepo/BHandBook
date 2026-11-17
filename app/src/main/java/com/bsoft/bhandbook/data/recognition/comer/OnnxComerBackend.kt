package com.bsoft.bhandbook.data.recognition.comer

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.bsoft.bhandbook.core.comer.ComerEncoding
import com.bsoft.bhandbook.core.comer.ComerInput
import com.bsoft.bhandbook.core.comer.ComerModelBackend
import com.bsoft.bhandbook.core.comer.ComerVocab
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

class ComerInitException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** ONNX Runtime session settings. The defaults are ORT's own; the alternatives exist so a slow device can be investigated (see ComerOnDeviceTest). */
data class ComerSessionTuning(
    val intraOpThreads: Int = OnnxComerBackend.defaultThreads(),
    val interOpThreads: Int = 1,
    val memoryPatternOptimization: Boolean = true,
    val cpuArena: Boolean = true,
    val optimizationLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT,
) {
    override fun toString() = "threads=$intraOpThreads/$interOpThreads memPattern=$memoryPatternOptimization arena=$cpuArena opt=$optimizationLevel"
}

/** What the loaded graphs actually declare (inspected at load time, never assumed). */
data class ComerModelInfo(
    val encoderInputs: Map<String, String>, val encoderOutputs: Map<String, String>,
    val decoderInputs: Map<String, String>, val decoderOutputs: Map<String, String>,
    val vocabSize: Int, val intraOpThreads: Int, val initMs: Long,
)

/**
 * CoMER encoder + decoder on ONNX Runtime Android (CPU). The model files come from the app's own assets (`models/comer/`):
 * nothing is downloaded. Sessions are created once and reused; every tensor created per recognition is closed deterministically.
 * Not thread-safe by itself: [com.bsoft.bhandbook.core.comer.ComerMathRecognitionEngine] serialises access.
 */
class OnnxComerBackend private constructor(
    private val env: OrtEnvironment,
    private val encoderSession: OrtSession,
    private val decoderSession: OrtSession,
    override val vocab: ComerVocab,
    val info: ComerModelInfo,
) : ComerModelBackend, AutoCloseable {

    @Volatile private var closed = false

    override fun encode(input: ComerInput): ComerEncoding {
        check(!closed) { "the recognition engine has been shut down" }
        val pixels = OnnxTensor.createTensor(env, FloatBuffer.wrap(input.tensor), longArrayOf(1, 1, input.height.toLong(), input.width.toLong()))
        var mask: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            mask = OnnxTensor.createTensor(env, ByteBuffer.wrap(input.mask), longArrayOf(1, input.height.toLong(), input.width.toLong()), OnnxJavaType.BOOL)
            result = encoderSession.run(mapOf(IN_PIXELS to pixels, IN_PIXEL_MASK to mask))
            val features = result.get(OUT_FEATURES).get() as OnnxTensor
            val featureMask = result.get(OUT_MASK).get() as OnnxTensor
            return Encoding(result, features, featureMask)
        } catch (t: Throwable) {
            result?.close()
            throw t
        } finally {
            mask?.close()           // inputs are copied by the runtime; release them right away
            pixels.close()
        }
    }

    /** Holds the encoder outputs for one recognition; the decoder is run once per generated token against them. */
    private inner class Encoding(
        private val result: OrtSession.Result, private val features: OnnxTensor, private val featureMask: OnnxTensor,
    ) : ComerEncoding {
        private var done = false

        override fun nextLogits(ids: IntArray): FloatArray {
            check(!done && !closed) { "encoding already closed" }
            val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
            try {
                decoderSession.run(mapOf(IN_FEATURES to features, IN_ENCODER_MASK to featureMask, IN_IDS to idTensor)).use { out ->
                    val logits = out.get(OUT_LOGITS).get() as OnnxTensor
                    val shape = (logits.info as TensorInfo).shape                    // [1, sequence, vocabulary]
                    val v = vocab.size
                    check(shape.size == 3 && shape[2] == v.toLong()) { "decoder returned logits of shape ${shape.toList()}, expected last dimension $v" }
                    val row = FloatArray(v)
                    val buf = logits.floatBuffer
                    buf.position(((shape[1] - 1) * v).toInt())                        // logits of the LAST position only
                    buf.get(row)
                    return row
                }
            } finally {
                idTensor.close()
            }
        }

        override fun close() { if (!done) { done = true; result.close() } }       // also releases the encoder output tensors
    }

    override fun close() {
        if (closed) return
        closed = true
        decoderSession.close()
        encoderSession.close()
    }

    companion object {
        private const val TAG = "ComerOnnx"
        private const val DIR = "models/comer"
        const val ENCODER_FILE = "encoder_int8.onnx"
        const val DECODER_FILE = "decoder_int8.onnx"
        const val VOCAB_FILE = "vocab.json"

        // names declared by the released graphs (verified from the ONNX metadata; re-verified at load time below)
        private const val IN_PIXELS = "pixel_values"
        private const val IN_PIXEL_MASK = "pixel_mask"
        private const val OUT_FEATURES = "encoder_features"
        private const val OUT_MASK = "encoder_mask"
        private const val IN_FEATURES = "encoder_features"
        private const val IN_ENCODER_MASK = "encoder_mask"
        private const val IN_IDS = "input_ids"
        private const val OUT_LOGITS = "logits"

        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** Loads the vocabulary and both sessions from the app's assets and verifies them against each other. Run off the main thread. */
        fun create(assets: AssetManager, tuning: ComerSessionTuning = ComerSessionTuning()): OnnxComerBackend {
            val intraOpThreads = tuning.intraOpThreads
            val start = SystemClock.elapsedRealtime()
            val vocab = try { ComerVocab.parse(String(readAsset(assets, VOCAB_FILE), Charsets.UTF_8)) }
            catch (e: ComerVocab.Invalid) { throw ComerInitException("the vocabulary file is invalid: ${e.message}", e) }

            val env = OrtEnvironment.getEnvironment()
            val encoder = openSession(env, assets, ENCODER_FILE, tuning)
            val decoder = try { openSession(env, assets, DECODER_FILE, tuning) } catch (t: Throwable) { encoder.close(); throw t }
            try {
                verify(encoder, decoder, vocab)
            } catch (t: Throwable) {
                decoder.close(); encoder.close()
                throw if (t is ComerInitException) t else ComerInitException("the model files are not compatible: ${t.message}", t)
            }
            val info = ComerModelInfo(describe(encoder.inputInfo), describe(encoder.outputInfo), describe(decoder.inputInfo), describe(decoder.outputInfo),
                vocab.size, intraOpThreads, SystemClock.elapsedRealtime() - start)
            Log.i(TAG, "CoMER ready in ${info.initMs} ms, ${info.intraOpThreads} threads, vocabulary ${vocab.size}; encoder ${info.encoderInputs} -> ${info.encoderOutputs}; decoder ${info.decoderInputs} -> ${info.decoderOutputs}")
            return OnnxComerBackend(env, encoder, decoder, vocab, info)
        }

        private fun readAsset(assets: AssetManager, name: String): ByteArray =
            try { assets.open("$DIR/$name").use { it.readBytes() } }
            catch (e: IOException) { throw ComerInitException("model file '$DIR/$name' is not packaged in the app. Run scripts/fetch_comer_models.py and rebuild.", e) }

        private fun openSession(env: OrtEnvironment, assets: AssetManager, file: String, tuning: ComerSessionTuning): OrtSession {
            val bytes = readAsset(assets, file)                                      // only referenced while the session is created
            try {
                OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads(tuning.intraOpThreads)
                    opts.setInterOpNumThreads(tuning.interOpThreads)
                    opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    opts.setOptimizationLevel(tuning.optimizationLevel)
                    opts.setMemoryPatternOptimization(tuning.memoryPatternOptimization)
                    opts.setCPUArenaAllocator(tuning.cpuArena)
                    return env.createSession(bytes, opts)
                }
            } catch (e: OrtException) {
                throw ComerInitException("ONNX Runtime could not load '$file': ${e.message}", e)
            }
        }

        private fun tensorType(s: OrtSession, input: Boolean, name: String): OnnxJavaType? =
            ((if (input) s.inputInfo else s.outputInfo)[name]?.info as? TensorInfo)?.type

        private fun verify(encoder: OrtSession, decoder: OrtSession, vocab: ComerVocab) {
            fun need(ok: Boolean, what: String) { if (!ok) throw ComerInitException("unexpected model interface: $what") }
            need(encoder.inputNames == setOf(IN_PIXELS, IN_PIXEL_MASK), "encoder inputs ${encoder.inputNames}")
            need(encoder.outputNames.containsAll(listOf(OUT_FEATURES, OUT_MASK)), "encoder outputs ${encoder.outputNames}")
            need(decoder.inputNames == setOf(IN_FEATURES, IN_ENCODER_MASK, IN_IDS), "decoder inputs ${decoder.inputNames}")
            need(decoder.outputNames.contains(OUT_LOGITS), "decoder outputs ${decoder.outputNames}")
            need(tensorType(encoder, true, IN_PIXELS) == OnnxJavaType.FLOAT, "pixel_values must be float32")
            need(tensorType(encoder, true, IN_PIXEL_MASK) == OnnxJavaType.BOOL, "pixel_mask must be bool")
            need(tensorType(decoder, true, IN_IDS) == OnnxJavaType.INT64, "input_ids must be int64")
            val logitsShape = ((decoder.outputInfo[OUT_LOGITS]?.info) as? TensorInfo)?.shape
            need(logitsShape != null && logitsShape.size == 3, "logits must be rank 3")
            val declared = logitsShape!![2]
            need(declared < 0 || declared == vocab.size.toLong(), "decoder produces $declared logits but vocab.json has ${vocab.size} tokens (files from different releases?)")
        }

        private fun describe(m: Map<String, ai.onnxruntime.NodeInfo>): Map<String, String> =
            m.mapValues { (_, n) -> (n.info as? TensorInfo)?.let { "${it.type} ${it.shape.toList()}" } ?: n.info.toString() }
    }
}
