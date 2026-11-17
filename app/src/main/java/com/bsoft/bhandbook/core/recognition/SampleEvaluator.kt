package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.domain.model.InkSample

data class Miss(val expected: String, val actual: String, val confidence: Float, val reliable: Boolean)

data class EvaluationReport(val total: Int, val correct: Int, val reliableCorrect: Int, val reliableWrong: Int, val misses: List<Miss>) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    /** Wrong answers the app would have shown without asking: the number that matters most for trust. */
    val silentErrorRate: Double get() = if (total == 0) 0.0 else reliableWrong.toDouble() / total
    fun summary() = buildString {
        append("samples=$total exact=$correct (${"%.1f".format(accuracy * 100)}%)  reliable&correct=$reliableCorrect  reliable&WRONG=$reliableWrong (${"%.1f".format(silentErrorRate * 100)}%)\n")
        misses.forEach { append("  expected '${it.expected}' got '${it.actual}' conf=${"%.2f".format(it.confidence)} reliable=${it.reliable}\n") }
    }
}

/** Scores the recogniser against labelled real handwriting. */
object SampleEvaluator {
    /** Makes user-typed labels comparable with recogniser output: no spaces, one minus, ÷ and / alike, and × only between operands. */
    fun normalise(raw: String): String {
        val t = raw.replace(" ", "").replace('−', '-').replace('÷', '/').replace('·', '×').replace('*', '×')
        val sb = StringBuilder()
        for ((i, c) in t.withIndex()) {
            if (c == 'x' || c == 'X') {
                val prev = t.getOrNull(i - 1); val next = t.getOrNull(i + 1)
                val between = prev != null && (prev.isDigit() || prev == ')') && next != null && (next.isDigit() || next in "(.√")
                sb.append(if (between) '×' else 'x')
            } else sb.append(c)
        }
        return sb.toString()
    }

    fun evaluate(samples: List<InkSample>, engine: MathRecognitionEngine = HandwritingMathRecognizer()): EvaluationReport {
        var correct = 0; var relOk = 0; var relBad = 0
        val misses = ArrayList<Miss>()
        for (s in samples) {
            val r = engine.recognize(s.strokes)
            val ok = normalise(r.expression.linear) == normalise(s.label)
            if (ok) { correct++; if (r.reliable) relOk++ } else { if (r.reliable) relBad++; misses += Miss(s.label, r.expression.linear, r.confidence, r.reliable) }
        }
        return EvaluationReport(samples.size, correct, relOk, relBad, misses)
    }
}
