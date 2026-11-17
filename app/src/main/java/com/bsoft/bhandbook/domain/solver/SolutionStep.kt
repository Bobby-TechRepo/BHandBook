package com.bsoft.bhandbook.domain.solver

/** One mathematically justified step of a solution. [highlight] marks the sub-range of [resulting] that changed. */
data class SolutionStep(
    val number: Int,
    val expression: String,      // expression before the operation
    val explanation: String,     // what was done
    val resulting: String,       // expression after the operation
    val highlight: IntRange? = null,
)
