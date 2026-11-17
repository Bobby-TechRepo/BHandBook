package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.recognition.RecognitionCancelled
import kotlin.math.exp
import kotlin.math.ln

/** Runs the decoder for one prefix and returns the logits of its LAST position (size = vocabulary size). */
interface ComerDecoderBackend { fun nextLogits(ids: IntArray): FloatArray }

data class ComerDecodeConfig(val beamWidth: Int = 3, val maxSteps: Int = 50, val repeatLimit: Int = 3)
data class ComerCandidate(val ids: List<Int>, val logProb: Double)

/**
 * [latex] is the selected, repaired candidate ("" when none is usable); [tokenIds] its ids. [candidates] are the ranked beam results
 * (a single entry for greedy). [selectedRank] indexes [candidates], or null when nothing was selected.
 * Scores are summed log-probabilities from the decoder: useful for ranking, NOT calibrated probabilities.
 */
data class ComerDecodeOutcome(val latex: String, val tokenIds: List<Int>, val candidates: List<ComerCandidate>, val selectedRank: Int?)

/** Port of ink-on's greedyDecode / beamDecode / candidate selection (inference.ts), checked against it on 405 scripted scenarios. */
object ComerDecoding {
    private class Beam(val logProb: Double, val ids: List<Int>, val finished: Boolean)

    /** JS-compatible comparator: sign of (b - a), NaN (e.g. -inf - -inf) counts as equal. */
    private fun descending(a: Double, b: Double): Int { val d = b - a; return if (d > 0) 1 else if (d < 0) -1 else 0 }

    fun decode(
        backend: ComerDecoderBackend, vocab: ComerVocab, config: ComerDecodeConfig, mode: ComerMode,
        isCancelled: () -> Boolean = { false },
    ): ComerDecodeOutcome {
        val allowed = if (mode == ComerMode.NUMBER) ComerModes.numberModeTokens(vocab) else null
        return if (config.beamWidth <= 1) greedyOutcome(backend, vocab, config, allowed, isCancelled)
        else beamOutcome(backend, vocab, config, mode, allowed, isCancelled)
    }

    private fun logSoftmax(logits: FloatArray): DoubleArray {
        var max = Double.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v.toDouble()
        var sum = 0.0
        for (v in logits) sum += exp(v.toDouble() - max)
        val lse = ln(sum)
        return DoubleArray(logits.size) { logits[it].toDouble() - max - lse }
    }

    private fun mask(lp: DoubleArray, allowed: Set<Int>?) {
        if (allowed == null) return
        for (i in lp.indices) if (i !in allowed) lp[i] = Double.NEGATIVE_INFINITY
    }

    private fun check(isCancelled: () -> Boolean) { if (isCancelled()) throw RecognitionCancelled() }

    private fun finish(vocab: ComerVocab, ids: List<Int>): String = ComerLatex.repair(vocab.tokens(ids)).joinToString(" ")

    // ------------------------------------------------------------------------------------------ greedy
    private fun greedyOutcome(backend: ComerDecoderBackend, vocab: ComerVocab, cfg: ComerDecodeConfig, allowed: Set<Int>?, isCancelled: () -> Boolean): ComerDecodeOutcome {
        val ids = arrayListOf(vocab.sos)
        var repeat = 0; var last = -1; var score = 0.0
        for (step in 0 until cfg.maxSteps) {
            check(isCancelled)
            val lp = logSoftmax(backend.nextLogits(ids.toIntArray()))
            mask(lp, allowed)
            var maxVal = Double.NEGATIVE_INFINITY; var maxIdx = 0
            for (i in lp.indices) if (lp[i] > maxVal) { maxVal = lp[i]; maxIdx = i }
            if (maxIdx == vocab.eos) { score += maxVal; break }
            if (maxIdx == last) { repeat++; if (repeat >= cfg.repeatLimit) break } else repeat = 0
            last = maxIdx; score += maxVal; ids += maxIdx
        }
        val out = ids.drop(1)
        val latex = finish(vocab, out)
        return ComerDecodeOutcome(latex, out, listOf(ComerCandidate(out, score)), 0)
    }

    // ------------------------------------------------------------------------------------------ beam
    private fun topK(arr: DoubleArray, k: Int): List<Int> =
        arr.indices.sortedWith { a, b -> descending(arr[a], arr[b]) }.take(k)       // stable, like Array.prototype.sort

    private fun beamOutcome(backend: ComerDecoderBackend, vocab: ComerVocab, cfg: ComerDecodeConfig, mode: ComerMode, allowed: Set<Int>?, isCancelled: () -> Boolean): ComerDecodeOutcome {
        val width = cfg.beamWidth
        var beams = listOf(Beam(0.0, listOf(vocab.sos), false))
        fun norm(b: Beam) = b.logProb / maxOf(b.ids.size, 1)

        for (step in 0 until cfg.maxSteps) {
            val candidates = ArrayList<Beam>()
            for (beam in beams) {
                if (beam.finished) { candidates += beam; continue }
                check(isCancelled)
                val lp = logSoftmax(backend.nextLogits(beam.ids.toIntArray()))
                mask(lp, allowed)
                for (idx in topK(lp, width * 2)) {
                    val newLogProb = beam.logProb + lp[idx]
                    if (idx == vocab.eos) { candidates += Beam(newLogProb, beam.ids, true); continue }
                    var r = 0
                    for (j in beam.ids.size - 1 downTo 1) { if (beam.ids[j] == idx) r++ else break }
                    candidates += if (r >= cfg.repeatLimit) Beam(newLogProb, beam.ids, true) else Beam(newLogProb, beam.ids + idx, false)
                }
            }
            beams = candidates.sortedWith { a, b -> descending(norm(a), norm(b)) }.take(width)
            if (beams.all { it.finished }) break
        }

        val completed = beams.filter { it.finished }
        val ranked = (if (completed.isNotEmpty()) completed else beams).sortedWith { a, b -> descending(norm(a), norm(b)) }.take(width)
        val cands = ranked.map { ComerCandidate(it.ids.drop(1), it.logProb) }

        // Candidate selection: repair -> (auto mode: completeness) -> structural validity; first success wins.
        var latex = ""; var ids = cands.firstOrNull()?.ids ?: emptyList(); var rank: Int? = null
        for ((i, c) in cands.withIndex()) {
            val repaired = ComerLatex.repair(vocab.tokens(c.ids))
            val text = repaired.joinToString(" ")
            if (mode == ComerMode.AUTO && !ComerLatex.isComplete(repaired)) continue
            if (ComerLatex.isStructurallyValid(text)) { latex = text; ids = c.ids; rank = i; break }
        }
        if (latex.isEmpty() && cands.isNotEmpty()) {          // last resort, exactly as the reference does
            val text = finish(vocab, cands[0].ids)
            latex = if (ComerLatex.isStructurallyValid(text)) text else ""
            ids = cands[0].ids
            rank = if (latex.isNotEmpty()) 0 else null
        }
        return ComerDecodeOutcome(latex, ids, cands, rank)
    }
}
