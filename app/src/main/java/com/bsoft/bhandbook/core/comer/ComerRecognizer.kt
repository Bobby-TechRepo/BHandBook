package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.math.MathExpressionParser
import com.bsoft.bhandbook.core.math.ParseResult
import com.bsoft.bhandbook.core.recognition.EqualsDetector
import com.bsoft.bhandbook.core.recognition.RecognitionCancelled
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.rotated
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** One encoded input. Reusable for every decoder step of that recognition; MUST be closed (releases native tensors). */
interface ComerEncoding : ComerDecoderBackend, AutoCloseable {
    override fun close()
}

/** The model runtime (ONNX Runtime on Android). Everything above this interface is plain Kotlin. */
interface ComerModelBackend {
    val vocab: ComerVocab
    fun encode(input: ComerInput): ComerEncoding
}

data class ComerRecognizerConfig(
    val beamWidth: Int = 3,
    val maxSteps: Int = 50,
    val mode: ComerMode = ComerMode.AUTO,
    /** Geometric-mean token probability needed to calculate without asking. A heuristic threshold, to be tuned on real samples. */
    val minMeanTokenProbability: Double = 0.6,
    /**
     * Decoder cost grows with output length (the exported graph has no key/value cache), and on wide (long) inputs greedy decoding was
     * as accurate as beam search in measurements (108 vs 109 of 168 stress cases) while being ~3x cheaper. Inputs at least this wide
     * (pixels of the model canvas) are decoded with [longInputBeamWidth].
     */
    val longInputWidth: Int = 768,
    val longInputBeamWidth: Int = 1,
    /**
     * Safety guard, not an accuracy claim: when an expression is so long that the 1024 px width cap shrinks it below this fraction of the
     * 128 px content height the model was trained on, the result is never trusted automatically. Measured with the real model:
     * 0.67 still read 12/12, 0.59 read 0/12 (a single failing point, so the exact value is a conservative guess); 0 disables the guard.
     */
    val minContentScale: Double = 0.65,
    /**
     * Writing direction. Ink written at more than [uprightToleranceDegrees] from left-to-right (sideways, top-to-bottom, upside down, diagonal) is
     * recognised in several orientations, in a measured order, stopping at the first calculable, plausible and confident reading (see [recognize]).
     */
    val orientationEnabled: Boolean = true,
    val uprightToleranceDegrees: Double = 20.0,
    val acceptConfidence: Double = 0.9,
    val preprocess: ComerPreprocessConfig = ComerPreprocessConfig(),
)

data class ComerTimings(val preprocessMs: Long, val encoderMs: Long, val decoderMs: Long, val totalMs: Long)

/** One ranked decoder candidate after repair and validation. [latex] is the repaired text; the raw decoder output is [tokenIds]. */
data class ComerCandidateReport(val rank: Int, val tokenIds: List<Int>, val latex: String, val logProb: Double, val meanTokenProbability: Double, val usable: Boolean, val note: String?)

sealed interface ComerRecognition {
    val candidates: List<ComerCandidateReport>

    data class Recognized(
        val latex: String, val linear: String, override val candidates: List<ComerCandidateReport>, val selectedRank: Int,
        val confidence: Double, val timings: ComerTimings,
        /** Changes made to the raw decoder output after validating it against the ink; empty when the model's reading was used as is. */
        val adjustments: List<String> = emptyList(),
        /** Reasons the result should be confirmed by the user before it is calculated (see [ComerRecognizerConfig.minContentScale]). */
        val warnings: List<String> = emptyList(),
        /** How far the ink was turned (degrees, clockwise on screen) before it was read; 0 for ordinary left-to-right writing. */
        val rotationDegrees: Double = 0.0,
        /** True when every symbol is one a calculator user plausibly writes (digits, operators, x/y/z, π, functions), not stray letters. */
        val plausible: Boolean = true,
    ) : ComerRecognition

    /** Nothing was calculated: the text is incomplete, unsupported or unparsable. [displayText] is a readable rendering to correct. */
    data class NeedsCorrection(
        val latex: String, val displayText: String, val reason: String, override val candidates: List<ComerCandidateReport>,
        val confidence: Double, val timings: ComerTimings,
        val rotationDegrees: Double = 0.0,
    ) : ComerRecognition

    data object NotMeaningful : ComerRecognition { override val candidates: List<ComerCandidateReport> = emptyList() }

    companion object {
        /** Scores come from decoder log-probabilities; they rank candidates but are not calibrated probabilities. */
        const val CONFIDENCE_IS_HEURISTIC = true
    }
}

class ComerSelection(
    val rank: Int?, val latex: String, val tokens: List<String>, val conversion: LatexConversion?, val reason: String?, val reports: List<ComerCandidateReport>,
    val adjustments: List<String> = emptyList(),
)

/** Chooses the best usable decoder candidate: repair -> completeness -> structure -> LaTeX conversion -> the app's real parser. */
object ComerCandidateSelector {
    /**
     * [inkEndsWithEquals]: the ink's right-most symbol is an equals sign. The model writes symbols left to right, so anything it emits AFTER
     * the last "=" cannot correspond to ink and is a decoding artefact (observed with the real model: "... = 1"); it is removed and reported.
     * No symbol is re-interpreted.
     */
    fun select(candidates: List<ComerCandidate>, vocab: ComerVocab, parser: MathExpressionParser, mode: ComerMode, inkEndsWithEquals: Boolean = false): ComerSelection {
        class Evaluated(val tokens: List<String>, val latex: String, val conversion: LatexConversion?, val usable: Boolean, val note: String?, val adjustments: List<String> = emptyList())

        val evaluated = candidates.map { c ->
            var tokens = ComerLatex.collapseRepeatedEquals(ComerLatex.repair(vocab.tokens(c.ids)))
            val adjustments = ArrayList<String>()
            val lastEquals = tokens.lastIndexOf("=")
            if (inkEndsWithEquals && lastEquals >= 0 && lastEquals < tokens.size - 1) {
                adjustments += "dropped ${tokens.size - 1 - lastEquals} symbol(s) after the final '=': the ink ends with '=' (${tokens.drop(lastEquals + 1).joinToString(" ")})"
                tokens = tokens.subList(0, lastEquals + 1)
            }
            val latex = tokens.joinToString(" ")
            val ev = when {
                tokens.isEmpty() -> Evaluated(tokens, latex, null, false, "nothing was recognised")
                mode == ComerMode.AUTO && !ComerLatex.isComplete(tokens) -> Evaluated(tokens, latex, null, false, "incomplete expression")
                !ComerLatex.isStructurallyValid(latex) -> Evaluated(tokens, latex, null, false, "malformed structure")
                else -> when (val conv = LatexToMath.convert(tokens)) {
                    is LatexConversion.Malformed -> Evaluated(tokens, latex, conv, false, conv.reason)
                    is LatexConversion.Unsupported -> Evaluated(tokens, latex, conv, true, conv.reason)
                    is LatexConversion.Success -> when (val p = parser.parse(conv.linear)) {
                        is ParseResult.Failure -> Evaluated(tokens, latex, conv, false, "cannot be parsed: ${p.error.message}")
                        is ParseResult.Success -> Evaluated(tokens, latex, conv, true, null)
                    }
                }
            }
            Evaluated(ev.tokens, ev.latex, ev.conversion, ev.usable, ev.note, adjustments)
        }
        val reports = candidates.mapIndexed { i, c ->
            ComerCandidateReport(i, c.ids, evaluated[i].latex, c.logProb, meanProbability(c), evaluated[i].usable, evaluated[i].note)
        }
        val chosen = evaluated.indexOfFirst { it.usable }
        return if (chosen >= 0) ComerSelection(chosen, evaluated[chosen].latex, evaluated[chosen].tokens, evaluated[chosen].conversion, evaluated[chosen].note, reports, evaluated[chosen].adjustments)
        else {
            val first = evaluated.firstOrNull()
            ComerSelection(null, first?.latex ?: "", first?.tokens ?: emptyList(), first?.conversion, first?.note ?: "nothing was recognised", reports)
        }
    }

    /** exp(mean log-probability per generated token, counting <eos>): a length-normalised score in 0..1. Not calibrated. */
    fun meanProbability(c: ComerCandidate): Double = exp(c.logProb / (c.ids.size + 1))
}

/** Preprocess -> encode -> decode -> select -> convert -> validate. Serialisation across threads is the caller's job (see the engine). */
class ComerRecognizer(
    private val backend: ComerModelBackend,
    private val parser: MathExpressionParser,
    private val config: ComerRecognizerConfig = ComerRecognizerConfig(),
) {
    /**
     * Ordinary ink is read once. Ink whose writing direction (estimated from the order the symbols were drawn) is far from left-to-right is read in
     * up to four orientations, in this order: the estimated direction, the estimate minus 90 degrees (a stacked fraction is written top-to-bottom but
     * reads left-to-right), as written, and the estimate plus 90 degrees. The first reading that is calculable, plausible and confident is used;
     * otherwise the best-ranked one. Measured with the real model on 90 rotated inputs: upright reading 10/90 (and 7 silently wrong calculable
     * answers), this search 92/100 on a set including fractions, at 1.09 model runs on average.
     */
    fun recognize(strokes: List<Stroke>, mode: ComerMode? = null, isCancelled: () -> Boolean = { false }): ComerRecognition {
        if (!ComerStrokeAdapter.isMeaningful(strokes, config.preprocess)) return ComerRecognition.NotMeaningful
        if (isCancelled()) throw RecognitionCancelled()
        val estimate = if (config.orientationEnabled) OrientationEstimator.estimate(strokes) else null
        if (estimate == null || abs(estimate.angle) <= Math.toRadians(config.uprightToleranceDegrees)) return recognizeAt(strokes, 0.0, isCancelled, mode ?: config.mode)

        val tried = ArrayList<ComerRecognition>()
        for (rotation in candidateRotations(estimate.angle)) {
            val r = recognizeAt(strokes, rotation, isCancelled, mode ?: config.mode)
            tried += r
            if (accepts(r)) return r
        }
        val best = tried.indices.maxWithOrNull(compareBy<Int>({ rank(tried[it]) }, { (confidenceOf(tried[it]) / 0.01).toInt() }).thenByDescending { it })!!   // confidences within 0.01 are equal; ties go to the earlier stage
        return tried[best]
    }

    /** Rotations (radians, applied to the ink) in the order they are tried; near-duplicates (within 3 degrees) are dropped. */
    private fun candidateRotations(angle: Double): List<Double> {
        val raw = listOf(-angle, -angle + PI / 2, 0.0, -angle - PI / 2).map { atan2(sin(it), cos(it)) }
        val out = ArrayList<Double>()
        for (r in raw) if (out.none { abs(atan2(sin(it - r), cos(it - r))) < Math.toRadians(3.0) }) out += r
        return out
    }

    private fun accepts(r: ComerRecognition) = r is ComerRecognition.Recognized && r.plausible && r.warnings.isEmpty() && r.confidence >= config.acceptConfidence

    private fun rank(r: ComerRecognition): Int = when {
        r is ComerRecognition.Recognized && r.plausible && r.warnings.isEmpty() -> 4
        r is ComerRecognition.Recognized && r.plausible -> 3
        r is ComerRecognition.Recognized -> 2
        r is ComerRecognition.NeedsCorrection && r.displayText.isNotBlank() -> 1
        else -> 0
    }

    private fun confidenceOf(r: ComerRecognition) = when (r) { is ComerRecognition.Recognized -> r.confidence; is ComerRecognition.NeedsCorrection -> r.confidence; else -> 0.0 }

    /** One full read of the ink turned by [rotation] radians (a rotated COPY; the user's strokes are never changed). */
    private fun recognizeAt(original: List<Stroke>, rotation: Double, isCancelled: () -> Boolean, mode: ComerMode): ComerRecognition {
        val start = System.nanoTime()
        val strokes = if (rotation == 0.0) original else {
            val pts = original.flatMap { it.points }
            original.rotated(rotation, pts.map { it.x.toDouble() }.average(), pts.map { it.y.toDouble() }.average())
        }
        if (isCancelled()) throw RecognitionCancelled()

        val input = ComerPreprocessor.preprocess(ComerStrokeAdapter.fromDomain(strokes, config.preprocess))
        val afterPreprocess = System.nanoTime()
        if (isCancelled()) throw RecognitionCancelled()

        val beam = if (input.width >= config.longInputWidth) minOf(config.beamWidth, config.longInputBeamWidth) else config.beamWidth
        var encoderEnd = 0L
        val outcome = backend.encode(input).use { encoding ->
            encoderEnd = System.nanoTime()
            ComerDecoding.decode(encoding, backend.vocab, ComerDecodeConfig(beam, config.maxSteps), mode, isCancelled)
        }
        val end = System.nanoTime()
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        val timings = ComerTimings(ms(start, afterPreprocess), ms(afterPreprocess, encoderEnd), ms(encoderEnd, end), ms(start, end))
        val degrees = Math.toDegrees(rotation)

        val sel = ComerCandidateSelector.select(outcome.candidates, backend.vocab, parser, mode, inkEndsWithEquals = EqualsDetector.endsWithEquals(strokes))
        val chosenConfidence = sel.rank?.let { sel.reports[it].meanTokenProbability } ?: sel.reports.firstOrNull()?.meanTokenProbability ?: 0.0
        val conversion = sel.conversion
        return when {
            sel.rank != null && conversion is LatexConversion.Success ->
                ComerRecognition.Recognized(sel.latex, conversion.linear, sel.reports, sel.rank, chosenConfidence, timings, sel.adjustments, lengthWarnings(input), degrees, isPlausible(sel.tokens))
            sel.rank != null && conversion is LatexConversion.Unsupported ->
                ComerRecognition.NeedsCorrection(sel.latex, conversion.bestEffort, conversion.reason, sel.reports, chosenConfidence, timings, degrees)
            else ->
                ComerRecognition.NeedsCorrection(sel.latex, LatexToMath.readable(sel.tokens), sel.reason ?: "could not be recognised", sel.reports, chosenConfidence, timings, degrees)
        }
    }

    private fun isPlausible(tokens: List<String>) = tokens.isNotEmpty() && tokens.all { it in PLAUSIBLE }

    private fun lengthWarnings(input: ComerInput): List<String> {
        val scale = input.contentHeight / ComerPreprocessor.TARGET_H
        return if (config.minContentScale > 0.0 && scale < config.minContentScale)
            listOf("This expression is very long and was shrunk to fit; recognition may be inaccurate. Check it, or write it on shorter lines.") else emptyList()
    }

    private companion object {
        /** Symbols a calculator user plausibly writes. Other letters (f, n, Y ...) are what the model tends to invent when it reads sideways ink. */
        val PLAUSIBLE = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "-", "=", "(", ")", ".", "/", "\\times", "\\div", "\\cdot", "\\frac", "\\sqrt",
            "^", "{", "}", "[", "]", "|", "x", "y", "z", "\\pi", "\\sin", "\\cos", "\\tan", "\\log")
    }
}
