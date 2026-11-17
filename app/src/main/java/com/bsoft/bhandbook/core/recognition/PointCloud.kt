package com.bsoft.bhandbook.core.recognition

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Stroke-order, stroke-count and direction independent matching ($P point-cloud recogniser, Vatavu et al. 2012). */
internal class Cloud(val x: FloatArray, val y: FloatArray)

internal object PointCloud {
    const val N = 32
    private const val EPS = 0.5

    /** [strokes] are polylines as (x,y) pairs. Resamples to exactly [N] points, centres and scales uniformly. */
    fun build(rawStrokes: List<List<Pair<Float, Float>>>): Cloud {
        val strokes = rawStrokes.map { smooth(it) }          // identical for input ink and templates, so corners are treated consistently
        val lens = strokes.map { pathLength(it) }
        val total = lens.sum()
        val quotas = quotas(lens, total)
        val xs = ArrayList<Float>(N); val ys = ArrayList<Float>(N)
        for ((i, s) in strokes.withIndex()) for (p in resample(s, quotas[i])) { xs += p.first; ys += p.second }
        // normalise: centroid -> origin, uniform scale by the larger bbox side (keeps aspect ratio information)
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val scale = max(maxX - minX, maxY - minY).let { if (it < 1e-6f) 1f else it }
        val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
        return Cloud(FloatArray(xs.size) { (xs[it] - cx) / scale }, FloatArray(ys.size) { (ys[it] - cy) / scale })
    }

    private const val SMOOTH_POINTS = 40
    private const val SMOOTH_PASSES = 2

    /**
     * Pen jitter is roughly constant in pixels, so it swamps the small features of small glyphs (the base bar of a '2' vs a '7').
     * Resample to even arc-length spacing, then low-pass filter ([1 2 1]/4, endpoints fixed) to remove it before matching.
     */
    internal fun smooth(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pts.size < 4 || pathLength(pts) < 1e-6f) return pts
        var cur = resample(pts, SMOOTH_POINTS)
        repeat(SMOOTH_PASSES) {
            val next = ArrayList<Pair<Float, Float>>(cur.size)
            next += cur.first()
            for (i in 1 until cur.size - 1) next += ((cur[i - 1].first + 2 * cur[i].first + cur[i + 1].first) / 4f) to ((cur[i - 1].second + 2 * cur[i].second + cur[i + 1].second) / 4f)
            next += cur.last()
            cur = next
        }
        return cur
    }

    /** Average point distance between two clouds (about 0 = identical, > 0.2 = clearly different). */
    fun distance(a: Cloud, b: Cloud): Float {
        val n = a.x.size
        val step = floor(n.toDouble().pow(1 - EPS)).toInt().coerceAtLeast(1)
        var best = Float.MAX_VALUE
        var i = 0
        while (i < n) { best = minOf(best, cloudDistance(a, b, i), cloudDistance(b, a, i)); i += step }
        return best / (n * 0.5f)
    }

    private fun cloudDistance(a: Cloud, b: Cloud, start: Int): Float {
        val n = a.x.size
        val matched = BooleanArray(n)
        var sum = 0f
        var i = start
        do {
            var idx = -1; var min = Float.MAX_VALUE
            for (j in 0 until n) if (!matched[j]) {
                val d = hypot(a.x[i] - b.x[j], a.y[i] - b.y[j])
                if (d < min) { min = d; idx = j }
            }
            matched[idx] = true
            val weight = 1f - ((i - start + n) % n).toFloat() / n
            sum += weight * min
            i = (i + 1) % n
        } while (i != start)
        return sum
    }

    fun pathLength(p: List<Pair<Float, Float>>): Float {
        var l = 0f
        for (i in 1 until p.size) l += hypot(p[i].first - p[i - 1].first, p[i].second - p[i - 1].second)
        return l
    }

    /** Largest-remainder allocation of [N] points across strokes proportional to length (>= 2 each). */
    private fun quotas(lens: List<Float>, total: Float): IntArray {
        val k = lens.size
        if (total < 1e-6f) return IntArray(k) { if (it == 0) N - 2 * (k - 1) else 2 }.also { if (it[0] < 2) it[0] = 2 }
        val spare = (N - 2 * k).coerceAtLeast(0)
        val raw = lens.map { spare * it / total }
        val q = IntArray(k) { 2 + floor(raw[it]).toInt() }
        var left = N - q.sum()
        val order = raw.indices.sortedByDescending { raw[it] - floor(raw[it]) }
        var i = 0
        while (left > 0) { q[order[i % k]]++; left--; i++ }
        return q
    }

    private fun resample(pts: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>> {
        if (pts.isEmpty()) return List(n) { 0f to 0f }
        val total = pathLength(pts)
        if (total < 1e-6f) return List(n) { pts[0] }
        val interval = total / (n - 1)
        val out = ArrayList<Pair<Float, Float>>(n)
        out += pts[0]
        var d = 0f
        var prev = pts[0]
        var i = 1
        val work = pts.toMutableList()
        while (i < work.size) {
            val cur = work[i]
            val seg = hypot(cur.first - prev.first, cur.second - prev.second)
            if (seg > 0f && d + seg >= interval) {
                val t = (interval - d) / seg
                val q = (prev.first + t * (cur.first - prev.first)) to (prev.second + t * (cur.second - prev.second))
                out += q; work.add(i, q); prev = q; d = 0f
            } else { d += seg; prev = cur }
            i++
        }
        while (out.size < n) out += pts.last()
        return out.take(n)
    }
}
