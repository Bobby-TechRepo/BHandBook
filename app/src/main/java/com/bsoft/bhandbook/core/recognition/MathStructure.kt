package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect
import kotlin.coroutines.cancellation.CancellationException
import com.bsoft.bhandbook.domain.model.Stroke

/** One recognised glyph and the strokes it was made from (so handwriting stays linked to the result). */
data class MathSymbol(val label: String, val bounds: WorldRect, val confidence: Float, val strokeIds: List<String>)

/** 2-D structure of an expression. Superscripts attach to the preceding sibling. */
sealed interface MathStructure {
    data class Leaf(val symbol: MathSymbol) : MathStructure
    data class Row(val children: List<MathStructure>) : MathStructure
    data class Fraction(val numerator: Row, val denominator: Row, val bar: MathSymbol) : MathStructure
    data class Radical(val radicand: Row, val symbol: MathSymbol) : MathStructure
    data class Script(val exponent: Row) : MathStructure
}

/** [linear] is plain notation the [com.bsoft.bhandbook.core.math.MathExpressionParser] understands, e.g. "(1)/(2)", "x^(2)", "√(16)". */
data class MathExpression(val structure: MathStructure.Row, val linear: String, val symbols: List<MathSymbol>) {
    companion object { val Empty = MathExpression(MathStructure.Row(emptyList()), "", emptyList()) }
}

/** One ranked alternative reading. [score] is a decoder log-probability: good for ranking, NOT a calibrated probability. */
data class RecognitionCandidate(val rank: Int, val text: String, val score: Double, val usable: Boolean, val note: String? = null)

data class RecognitionTimings(val preprocessMs: Long, val encoderMs: Long, val decoderMs: Long, val totalMs: Long)

data class RecognitionResult(
    val expression: MathExpression,
    /** 0..1. For the geometric engine the weakest symbol; for CoMER the geometric-mean token probability (a heuristic). */
    val confidence: Float,
    /** False when the result must not be evaluated without user confirmation. */
    val reliable: Boolean,
    val warnings: List<String> = emptyList(),
    /** Engine-specific detail, when available. */
    val latex: String? = null,
    val candidates: List<RecognitionCandidate> = emptyList(),
    val timings: RecognitionTimings? = null,
    val engine: String = "geometric",
    /** Other readings the user may have meant, as calculator text (best first), for one-tap correction. */
    val alternatives: List<String> = emptyList(),
)

/**
 * Thrown by an engine when the caller asked it to stop. It extends [CancellationException] so that coroutines treat it as an ordinary,
 * silent cancellation (an ordinary RuntimeException escaped `launch` and crashed the app whenever the user drew during a recognition).
 */
class RecognitionCancelled : CancellationException("recognition cancelled") {
    override fun fillInStackTrace(): Throwable = this      // thrown on every cancelled recognition: do not pay for a stack trace
}

/** What the caller already knows about the ink. NUMBER: it is a number, optionally with a leading + - x / sign (rows of a column sum). */
enum class RecognitionHint { ANY, NUMBER }

/** Pure, synchronous and cancellable; callers run it on a background dispatcher and pass `{ !isActive }`. */
interface MathRecognitionEngine {
    fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean = { false }): RecognitionResult

    /** Engines that can use [hint] override this; the default ignores it, so existing engines keep working unchanged. */
    fun recognize(strokes: List<Stroke>, hint: RecognitionHint, isCancelled: () -> Boolean = { false }): RecognitionResult = recognize(strokes, isCancelled)
}

object RecognitionThresholds {
    const val RELIABLE_CONFIDENCE = 0.65f
}
