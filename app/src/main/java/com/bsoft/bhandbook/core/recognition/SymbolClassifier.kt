package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.Stroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A classified glyph. Labels: 0-9 + X ( ) / = ÷ √ plus the provisional BAR (− or fraction bar), DOT (. or ·) and ? (unknown). */
internal data class Sym(val label: String, val bounds: WorldRect, val conf: Float, val ids: List<String>) {
    val cx get() = bounds.centerX
    val cy get() = bounds.centerY
    fun toSymbol(finalLabel: String = label) = MathSymbol(finalLabel, bounds, conf, ids)
}

internal object Geometry {
    fun pts(s: Stroke) = s.points.map { it.x to it.y }
    fun isDot(b: WorldRect, ref: Float) = b.width < 0.28f * ref && b.height < 0.28f * ref
    fun isFlat(b: WorldRect, ref: Float) = !isDot(b, ref) && b.width > 0 && b.height <= 0.28f * b.width
    fun gapX(a: WorldRect, b: WorldRect) = max(a.left, b.left) - min(a.right, b.right)    // <0 => overlap
    fun gapY(a: WorldRect, b: WorldRect) = max(a.top, b.top) - min(a.bottom, b.bottom)

    /** Reference glyph height: median height of clearly non-flat strokes (falls back to the largest dimension). */
    fun refSize(strokes: List<Stroke>): Float {
        val tall = strokes.map { it.bounds }.filter { it.height >= 0.4f * max(it.width, it.height) && it.height > 0f }.map { it.height }.sorted()
        if (tall.isNotEmpty()) return tall[tall.size / 2]
        return strokes.maxOfOrNull { max(it.bounds.width, it.bounds.height) } ?: 1f
    }

    /**
     * `minDistance(a, b) <= tolerance`, but rejects far-apart strokes from their bounding boxes first and stops at the first close pair:
     * segmentation asks this for every pair of strokes, so the exhaustive point-by-point comparison dominated recognition time on long expressions.
     */
    fun closerThan(a: Stroke, b: Stroke, tolerance: Float): Boolean {
        val ba = a.bounds; val bb = b.bounds
        if (ba.isEmpty || bb.isEmpty) return false
        if (gapX(ba, bb) > tolerance || gapY(ba, bb) > tolerance) return false
        val pa = sample(a); val pb = sample(b); val tol2 = tolerance * tolerance
        for (p in pa) for (q in pb) { val dx = p.first - q.first; val dy = p.second - q.second; if (dx * dx + dy * dy <= tol2) return true }
        return false
    }

    /** Minimum distance between two polylines, sampled. */
    fun minDistance(a: Stroke, b: Stroke): Float {
        val pa = sample(a); val pb = sample(b)
        var best = Float.MAX_VALUE
        for (p in pa) for (q in pb) { val d = dist(p.first - q.first, p.second - q.second); if (d < best) best = d }
        return best
    }
    private fun sample(s: Stroke): List<Pair<Float, Float>> {
        val p = pts(s)
        if (p.size <= 48) return densify(p)
        val step = p.size / 48f
        return densify(List(48) { p[(it * step).toInt().coerceAtMost(p.size - 1)] })
    }
    private fun densify(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (p.size < 2) return p
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until p.size - 1) {
            out += p[i]
            val d = dist(p[i + 1].first - p[i].first, p[i + 1].second - p[i].second)
            val n = (d / 4f).toInt().coerceAtMost(12)
            for (k in 1..n) { val f = k.toFloat() / (n + 1); out += (p[i].first + (p[i + 1].first - p[i].first) * f) to (p[i].second + (p[i + 1].second - p[i].second) * f) }
        }
        out += p.last(); return out
    }

    fun union(strokes: List<Stroke>): WorldRect = strokes.fold(WorldRect.Empty) { acc, s -> acc.union(s.bounds) }
}

/** Splits strokes into multi-stroke symbols (+, ×, =, ÷, 4, 5, ...) using touching/crossing and the '=' and '÷' layouts. */
internal object Segmenter {
    fun segment(strokes: List<Stroke>, ref: Float, isCancelled: () -> Boolean): List<List<Stroke>> {
        val n = strokes.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(i: Int, j: Int) { parent[find(i)] = find(j) }
        val b = strokes.map { it.bounds }
        val dot = b.map { Geometry.isDot(it, ref) }
        val flat = b.map { Geometry.isFlat(it, ref) }

        for (i in 0 until n) for (j in i + 1 until n) {
            if (isCancelled()) throw RecognitionCancelled()
            // touching / crossing strokes belong to one symbol, except dots and long bars next to a smaller glyph
            if (!dot[i] && !dot[j]) {
                fun bigSide(r: WorldRect) = max(r.width, r.height)
                val barVsGlyph = (flat[i] && !flat[j] && b[i].width >= 1.8f * bigSide(b[j])) || (flat[j] && !flat[i] && b[j].width >= 1.8f * bigSide(b[i]))
                if (!barVsGlyph && !(flat[i] && flat[j]) && Geometry.closerThan(strokes[i], strokes[j], 0.12f * ref)) union(i, j)
            }
            // '=' : two stacked flat strokes of similar length
            if (flat[i] && flat[j]) {
                val overlap = min(b[i].right, b[j].right) - max(b[i].left, b[j].left)
                val wMin = min(b[i].width, b[j].width); val wMax = max(b[i].width, b[j].width)
                val gap = Geometry.gapY(b[i], b[j])
                if (overlap >= 0.6f * wMin && wMax <= 1.8f * wMin && gap <= 0.7f * wMax && gap <= 0.9f * ref) union(i, j)
            }
        }
        // '÷' : a flat bar with a dot above and a dot below, centred
        for (k in 0 until n) if (flat[k]) {
            val above = (0 until n).filter { dot[it] && b[it].centerY < b[k].centerY && b[k].centerY - b[it].centerY <= 0.8f * ref && b[it].centerX in (b[k].left + 0.25f * b[k].width)..(b[k].right - 0.25f * b[k].width) }
            val below = (0 until n).filter { dot[it] && b[it].centerY > b[k].centerY && b[it].centerY - b[k].centerY <= 0.8f * ref && b[it].centerX in (b[k].left + 0.25f * b[k].width)..(b[k].right - 0.25f * b[k].width) }
            if (above.size == 1 && below.size == 1) { union(k, above[0]); union(k, below[0]) }
        }
        return strokes.indices.groupBy { find(it) }.values.sortedBy { g -> g.minOf { b[it].left } }.map { g -> g.map { strokes[it] } }
    }
}

internal object SymbolClassifier {
    private const val MAX_MATCH_DISTANCE = 0.30f

    fun classify(cluster: List<Stroke>, ref: Float, isCancelled: () -> Boolean): Sym {
        val bounds = Geometry.union(cluster)
        val ids = cluster.map { it.id }
        val flat = cluster.map { Geometry.isFlat(it.bounds, ref) }
        val dots = cluster.map { Geometry.isDot(it.bounds, ref) }

        if (cluster.size == 1 && dots[0]) return Sym("DOT", bounds, 0.9f, ids)
        if (cluster.size == 3 && flat.count { it } == 1 && dots.count { it } == 2) return Sym("÷", bounds, 0.9f, ids)
        if (cluster.size == 2 && flat.all { it }) return Sym("=", bounds, 0.92f, ids)
        if (cluster.size == 1 && flat[0] && bounds.width >= 0.3f * ref) return Sym("BAR", bounds, 0.92f, ids)
        if (cluster.size == 1 && isRadical(cluster[0], ref)) return Sym("√", bounds, 0.9f, ids)
        return matchTemplates(cluster, bounds, ids, isCancelled)
    }

    fun isEqualsCluster(cluster: List<Stroke>, ref: Float) = cluster.size == 2 && cluster.all { Geometry.isFlat(it.bounds, ref) }

    private fun matchTemplates(cluster: List<Stroke>, bounds: WorldRect, ids: List<String>, isCancelled: () -> Boolean): Sym {
        val cloud = PointCloud.build(cluster.map { Geometry.pts(it) })
        val best = HashMap<String, Float>()
        for ((i, t) in GlyphLibrary.templates.withIndex()) {
            if (i % 6 == 0 && isCancelled()) throw RecognitionCancelled()
            val d = PointCloud.distance(cloud, t.cloud)
            if (d < (best[t.label] ?: Float.MAX_VALUE)) best[t.label] = d
        }
        val ranked = best.entries.sortedBy { it.value }
        val top = ranked[0]; val second = ranked.getOrNull(1)?.value ?: (top.value + 1f)
        if (top.value > MAX_MATCH_DISTANCE) return Sym("?", bounds, 0.05f, ids)
        val sim = 1f / (1f + (top.value / 0.10f).let { it * it })
        val margin = ((second - top.value) / second).coerceIn(0f, 1f)
        val conf = sim * (0.55f + 0.45f * (margin / 0.25f).coerceIn(0f, 1f))
        return Sym(top.key, bounds, conf.coerceIn(0f, 1f), ids)
    }

    /** √ is one stroke: short tick, long down-stroke to a low point, rising stroke, then a long horizontal vinculum along the top. */
    fun isRadical(s: Stroke, ref: Float): Boolean {
        val p = Geometry.pts(s)
        val b = s.bounds
        if (p.size < 6 || b.height < 0.5f * ref || b.width < 0.6f * b.height) return false
        val h = b.height; val w = b.width
        val lowIdx = p.indices.maxByOrNull { p[it].second }!!
        val total = PointCloud.pathLength(p)
        val toLow = PointCloud.pathLength(p.subList(0, lowIdx + 1))
        if (total <= 0f || toLow / total !in 0.08f..0.6f) return false
        if (p[lowIdx].second < b.bottom - 0.15f * h || p[lowIdx].first > b.left + 0.5f * w) return false
        if (p[0].second < b.top + 0.25f * h) return false
        val topIdx = (lowIdx until p.size).firstOrNull { p[it].second <= b.top + 0.18f * h } ?: return false
        val tail = p.subList(topIdx, p.size)
        if (tail.any { it.second > b.top + 0.3f * h }) return false
        return tail.last().first - tail.first().first >= 0.2f * w
    }
}

private fun dist(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
