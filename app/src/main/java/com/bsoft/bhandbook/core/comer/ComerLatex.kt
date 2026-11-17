package com.bsoft.bhandbook.core.comer

/** Port of ink-on's latex-repair.ts and the structural checks in inference.ts. Behaviour is identical (verified against the reference on 724 cases). */
object ComerLatex {
    fun repair(tokens: List<String>): List<String> = fixSqrtArgs(fixFracArgs(balanceBraces(tokens.toList())))

    private fun balanceBraces(tokens: List<String>): List<String> {
        val out = ArrayList<String>(); var depth = 0
        for (t in tokens) {
            when (t) {
                "{" -> { depth++; out += t }
                "}" -> if (depth > 0) { depth--; out += t }        // an unmatched '}' is dropped
                else -> out += t
            }
        }
        while (depth > 0) { out += "}"; depth-- }
        return out
    }

    /** Copies one braced group starting at [start] (which must be "{") into [out]; returns the index after it. */
    private fun copyGroup(tokens: List<String>, start: Int, out: MutableList<String>): Int {
        var i = start; var depth = 1
        out += tokens[i]; i++
        while (i < tokens.size && depth > 0) {
            if (tokens[i] == "{") depth++ else if (tokens[i] == "}") depth--
            out += tokens[i]; i++
        }
        return i
    }

    private fun skipGroup(tokens: List<String>, start: Int): Int {
        var i = start + 1; var depth = 1
        while (i < tokens.size && depth > 0) { if (tokens[i] == "{") depth++ else if (tokens[i] == "}") depth--; i++ }
        return i
    }

    private fun fixArgs(tokens: List<String>, command: String, maxGroups: Int): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < tokens.size) {
            if (tokens[i] == command) {
                out += tokens[i]; i++
                var groups = 0
                while (i < tokens.size && groups < maxGroups) {
                    if (tokens[i] == "{") { groups++; i = copyGroup(tokens, i, out) }
                    else { out += tokens[i]; i++; groups++ }            // an unbraced single-token argument
                }
                while (i < tokens.size && tokens[i] == "{") i = skipGroup(tokens, i)   // surplus groups are dropped
            } else { out += tokens[i]; i++ }
        }
        return out
    }

    private fun fixFracArgs(tokens: List<String>) = fixArgs(tokens, "\\frac", 2)
    private fun fixSqrtArgs(tokens: List<String>) = fixArgs(tokens, "\\sqrt", 1)

    private val needsArgs = setOf("\\sum", "\\int", "\\sin", "\\cos", "\\tan", "\\log", "\\lim", "\\sqrt", "\\frac")

    fun isComplete(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        if (tokens.size == 1 && tokens[0] in needsArgs) return false
        for (i in tokens.indices) {
            if (tokens[i] == "\\frac" && countBracedGroups(tokens.subList(i + 1, tokens.size)) < 2) return false
            if (tokens[i] == "\\sqrt" && countBracedGroups(tokens.subList(i + 1, tokens.size)) < 1) return false
        }
        return true
    }

    private fun countBracedGroups(tokens: List<String>): Int {
        var groups = 0; var i = 0
        while (i < tokens.size) {
            if (tokens[i] != "{") break
            groups++; var depth = 1; i++
            while (i < tokens.size && depth > 0) { if (tokens[i] == "{") depth++ else if (tokens[i] == "}") depth--; i++ }
        }
        return groups
    }

    /** The reference's "isValidMath": balanced braces, \frac followed by two braced groups, \sqrt followed by something. */
    fun isStructurallyValid(latex: String): Boolean {
        val tokens = latex.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        var depth = 0
        for (t in tokens) { if (t == "{") depth++ else if (t == "}") depth--; if (depth < 0) return false }
        if (depth != 0) return false
        for (i in tokens.indices) {
            if (tokens[i] == "\\frac" && countBracedGroups(tokens.subList(i + 1, tokens.size)) < 2) return false
            if (tokens[i] == "\\sqrt" && i + 1 >= tokens.size) return false
        }
        return true
    }

    /**
     * NOT in the reference. The model reads a two-stroke "=" as "= =" (observed with the real model). Two directly adjacent equals
     * signs are never valid mathematics, so they are merged; equals signs separated by anything are left alone.
     */
    fun collapseRepeatedEquals(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        for (t in tokens) if (!(t == "=" && out.lastOrNull() == "=")) out += t
        return out
    }
}
