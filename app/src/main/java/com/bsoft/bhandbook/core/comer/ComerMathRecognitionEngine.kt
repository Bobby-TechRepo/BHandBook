package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.recognition.MathExpression
import com.bsoft.bhandbook.core.recognition.MathRecognitionEngine
import com.bsoft.bhandbook.core.recognition.MathStructure
import com.bsoft.bhandbook.core.recognition.RecognitionCandidate
import com.bsoft.bhandbook.core.recognition.RecognitionHint
import com.bsoft.bhandbook.core.recognition.RecognitionResult
import com.bsoft.bhandbook.core.recognition.RecognitionTimings
import com.bsoft.bhandbook.domain.model.Stroke

/**
 * Adapts [ComerRecognizer] to the app's [MathRecognitionEngine]. The rest of the app depends only on that interface,
 * never on ONNX or CoMER classes. Inference is serialised: one recognition at a time owns the sessions.
 */
class ComerMathRecognitionEngine(
    private val recognizer: ComerRecognizer,
    private val minMeanTokenProbability: Double = ComerRecognizerConfig().minMeanTokenProbability,
    /** Called after every recognition with the raw result (timings, candidates); used for logging and diagnosing slow devices. */
    private val onResult: (ComerRecognition) -> Unit = {},
) : MathRecognitionEngine {

    private val lock = Any()

    /** Runs [block] while no recognition is running (and none can start). Used to dispose native sessions safely. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): RecognitionResult = recognize(strokes, RecognitionHint.ANY, isCancelled)

    override fun recognize(strokes: List<Stroke>, hint: RecognitionHint, isCancelled: () -> Boolean): RecognitionResult {
        val mode = if (hint == RecognitionHint.NUMBER) ComerMode.NUMBER else null          // the model's own constrained "numbers only" decoding
        val r = synchronized(lock) { recognizer.recognize(strokes, mode, isCancelled) }
        onResult(r)
        fun expr(text: String) = MathExpression(MathStructure.Row(emptyList()), text, emptyList())
        fun cands(list: List<ComerCandidateReport>) = list.map { RecognitionCandidate(it.rank, it.latex, it.logProb, it.usable, it.note) }
        fun times(t: ComerTimings) = RecognitionTimings(t.preprocessMs, t.encoderMs, t.decoderMs, t.totalMs)
        return when (r) {
            ComerRecognition.NotMeaningful -> RecognitionResult(MathExpression.Empty, 0f, false, listOf("nothing to recognise"), engine = ENGINE)
            is ComerRecognition.Recognized -> {
                val low = r.confidence < minMeanTokenProbability
                val warnings = (if (low) listOf("low confidence") else emptyList()) + r.warnings
                RecognitionResult(expr(r.linear), r.confidence.toFloat(), warnings.isEmpty(), warnings, r.latex, cands(r.candidates), times(r.timings), ENGINE, ComerAlternatives.of(r.candidates, r.linear))
            }
            is ComerRecognition.NeedsCorrection ->
                RecognitionResult(expr(r.displayText), r.confidence.toFloat(), false, listOf(r.reason), r.latex, cands(r.candidates), times(r.timings), ENGINE, ComerAlternatives.of(r.candidates, r.displayText))
        }
    }

    companion object { const val ENGINE = "comer" }
}
