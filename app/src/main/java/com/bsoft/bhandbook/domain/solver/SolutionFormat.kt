package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.core.math.NumberFormatter
import com.bsoft.bhandbook.core.math.Rational
import com.bsoft.bhandbook.core.math.ResultStyle
import com.bsoft.bhandbook.core.math.Value

/**
 * Applies the result format (decimal / fixed places) to the FINAL answer of an equation or a system. The algebra above it stays exact (the steps are
 * the honest working); one extra step says that the answer is being written as a decimal. Answers that are not plain numbers (radicals, free
 * variables) are left exactly as they are.
 */
internal object SolutionFormat {
    fun apply(outcome: SolveOutcome, calc: CalcSettings): SolveOutcome {
        if (calc.style == ResultStyle.AUTO && calc.places == null) return outcome
        if (outcome !is SolveOutcome.Solved || outcome.solutions.isEmpty()) return outcome
        val inSystem = outcome.solutions.all { it.contains(" = ") }
        val parsed = outcome.solutions.map { s ->
            val value = if (inSystem) s.substringAfter(" = ") else s
            (parseNumber(value) ?: return outcome) to (if (inSystem) s.substringBefore(" = ") + " = " else "")
        }
        val formatted = parsed.map { (r, prefix) -> prefix + NumberFormatter.format(Value.Exact(r), calc).primary }
        if (formatted == outcome.solutions) return outcome
        val final = if (inSystem) formatted.joinToString(", ") else outcome.finalExpression.substringBefore(" =").let { v -> formatted.joinToString(" or ") { "$v = $it" } }
        val explanation = if (calc.places != null) "Round the answer to ${calc.places} decimal place${if (calc.places == 1) "" else "s"}" else "Write the answer as a decimal"
        val step = SolutionStep(outcome.steps.size + 1, outcome.finalExpression, explanation, final)
        return outcome.copy(steps = outcome.steps + step, finalExpression = final, solutions = formatted)
    }

    /** "10/3", "-2.5", "7" -> exact value; null for anything else (radicals, expressions in other variables). */
    private fun parseNumber(text: String): Rational? {
        val t = text.trim()
        return try {
            if ('/' in t) { val (n, d) = t.split('/', limit = 2); Rational.of(java.math.BigInteger(n.trim()), java.math.BigInteger(d.trim())) } else Rational.parse(t)
        } catch (e: Exception) { null }
    }
}
