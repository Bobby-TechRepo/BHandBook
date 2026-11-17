package com.bsoft.bhandbook.domain.model

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.solver.SolutionStep

enum class ProcessingStatus { IDLE, PENDING, RECOGNIZING, RECOGNIZED, NEEDS_REVIEW, EVALUATED, ERROR }

/**
 * A mathematical expression on the canvas. Strokes live in [CanvasState.strokes] and reference this object
 * by [Stroke.expressionId]; the handwriting is never discarded after recognition.
 */
data class ExpressionObject(
    val id: String,
    val recognized: String? = null,          // linear notation, e.g. "25+15×2="
    val confidence: Float = 0f,
    val result: String? = null,
    val steps: List<SolutionStep> = emptyList(),
    val solutionVisible: Boolean = true,
    val status: ProcessingStatus = ProcessingStatus.IDLE,
    val error: String? = null,
    val modifiedAt: Long = 0L,
    val strokeSignature: Int = 0,            // hash of the strokes the recognition was computed from
    val displayText: String? = null,         // typeset form of the recognised expression, e.g. "25 + 15"
    val resultInline: Boolean = false,       // true when the user wrote '=': result is drawn right after the handwriting
)

/** Canvas document state. Strokes are kept in draw order. */
data class CanvasState(
    val strokes: List<Stroke> = emptyList(),
    val expressions: Map<String, ExpressionObject> = emptyMap(),
) {
    fun strokesOf(expressionId: String): List<Stroke> = strokes.filter { it.expressionId == expressionId }
    fun boundsOf(expressionId: String): WorldRect =
        strokesOf(expressionId).fold(WorldRect.Empty) { acc, s -> acc.union(s.bounds) }
}
