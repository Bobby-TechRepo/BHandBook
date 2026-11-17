package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.core.math.MathStatement

sealed interface SolveOutcome {
    /** [finalExpression] e.g. "55" or "x = 5"; [solutions] are plain, re-parseable values (empty for arithmetic). */
    data class Solved(val steps: List<SolutionStep>, val finalExpression: String, val solutions: List<String> = emptyList()) : SolveOutcome
    data class NoSolution(val steps: List<SolutionStep>, val reason: String) : SolveOutcome
    /** True for every value of the variable (or a true numeric statement). */
    data class Identity(val steps: List<SolutionStep>) : SolveOutcome
    data class Unsupported(val reason: String) : SolveOutcome
    data class Invalid(val reason: String) : SolveOutcome
}

interface MathSolutionEngine {
    /** [calc]: the angle unit trigonometric functions are read in, and how final answers are written (fractions or decimals, decimal places). */
    fun solve(statement: MathStatement, calc: CalcSettings = CalcSettings()): SolveOutcome
    /** Solves several equations together (simultaneous equations). */
    fun solveSystem(equations: List<MathStatement.Equation>, calc: CalcSettings = CalcSettings()): SolveOutcome
}
