package com.bsoft.bhandbook.domain.usecase

import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.ProcessingStatus

/** Folds a processing outcome into the stored expression. Pure; user-controlled flags (solution visibility) are preserved. */
fun ProcessingOutcome.applyTo(e: ExpressionObject, now: Long): ExpressionObject {
    val base = e.copy(recognized = recognized, confidence = confidence, modifiedAt = now, error = null, result = null, steps = emptyList(), resultInline = false, displayText = null)
    return when (this) {
        is ProcessingOutcome.NeedsReview -> base.copy(status = ProcessingStatus.NEEDS_REVIEW, error = warnings.joinToString().ifEmpty { null })
        is ProcessingOutcome.Invalid -> base.copy(status = ProcessingStatus.ERROR, error = message)
        is ProcessingOutcome.Failed -> base.copy(status = ProcessingStatus.ERROR, error = message, displayText = displayText)
        is ProcessingOutcome.RecognizedOnly -> base.copy(status = ProcessingStatus.RECOGNIZED, displayText = displayText)
        is ProcessingOutcome.Evaluated -> base.copy(status = ProcessingStatus.EVALUATED, displayText = displayText,
            result = if (decimal != null) "$result ≈ $decimal" else result, resultInline = explicit, steps = steps)
        is ProcessingOutcome.Solved -> base.copy(status = ProcessingStatus.EVALUATED, displayText = displayText, result = finalExpression, steps = steps)
    }
}
