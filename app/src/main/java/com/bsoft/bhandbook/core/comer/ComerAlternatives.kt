package com.bsoft.bhandbook.core.comer

/**
 * The model's runner-up readings, as text the user can read and the calculator can use, for one-tap correction ("Did you mean ...").
 * A reading is offered only if it is usable, converts to a calculable expression, and differs from the chosen one (ignoring the "=" that merely
 * asked for the answer); duplicates after conversion appear once.
 */
object ComerAlternatives {
    fun of(reports: List<ComerCandidateReport>, chosenLinear: String, max: Int = 3): List<String> {
        val seen = hashSetOf(normal(chosenLinear))
        val out = ArrayList<String>()
        for (r in reports.sortedBy { it.rank }) {
            if (!r.usable) continue
            val tokens = r.latex.trim().split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val converted = LatexToMath.convert(tokens) as? LatexConversion.Success ?: continue
            val text = converted.linear
            if (text.isBlank() || !seen.add(normal(text))) continue
            out += text
            if (out.size >= max) break
        }
        return out
    }

    private fun normal(s: String) = s.trim().trimEnd('=').trim()
}
