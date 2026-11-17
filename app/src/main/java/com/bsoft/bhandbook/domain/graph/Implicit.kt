package com.bsoft.bhandbook.domain.graph

import kotlin.math.abs

/** Axis-aligned rectangle of world (math) coordinates. */
internal class Box(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    fun union(o: Box) = Box(minOf(xMin, o.xMin), maxOf(xMax, o.xMax), minOf(yMin, o.yMin), maxOf(yMax, o.yMax))
}

/**
 * Plots F(x, y) = 0 for any expression F: marching squares over a grid, with the pieces stitched into continuous polylines (closed loops for
 * circles, separate branches for hyperbolas). Cells where F is undefined are skipped, so square roots, logarithms and division by zero simply leave gaps.
 */
internal object ImplicitContour {

    /** Zero set of [f] in the given box as polylines. [n] is the number of grid cells along each side. */
    fun contour(f: (Double, Double) -> Double, xMin: Double, xMax: Double, yMin: Double, yMax: Double, n: Int): List<List<GraphPoint>> {
        val dx = (xMax - xMin) / n; val dy = (yMax - yMin) / n
        val w = n + 1
        val v = DoubleArray(w * w)                                                  // v[j * w + i] = f(x_i, y_j)
        for (j in 0..n) for (i in 0..n) v[j * w + i] = f(xMin + i * dx, yMin + j * dy)
        fun pos(k: Int) = v[k] > 0.0

        // edge ids: horizontal edge h(i, j) joins vertices (i, j)-(i+1, j); vertical edge e(i, j) joins (i, j)-(i, j+1)
        fun h(i: Int, j: Int): Int = j * w + i
        val vOffset = w * w
        fun e(i: Int, j: Int): Int = vOffset + j * w + i

        val segA = ArrayList<Int>(); val segB = ArrayList<Int>()
        fun seg(a: Int, b: Int) { segA += a; segB += b }
        for (j in 0 until n) for (i in 0 until n) {
            val k00 = j * w + i; val k10 = k00 + 1; val k01 = k00 + w; val k11 = k01 + 1
            val a = v[k00]; val b = v[k10]; val c = v[k11]; val d = v[k01]
            if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN() || a.isInfinite() || b.isInfinite() || c.isInfinite() || d.isInfinite()) continue
            val idx = (if (pos(k00)) 1 else 0) or (if (pos(k10)) 2 else 0) or (if (pos(k11)) 4 else 0) or (if (pos(k01)) 8 else 0)
            val bottom = h(i, j); val top = h(i, j + 1); val left = e(i, j); val right = e(i + 1, j)
            when (idx) {
                1, 14 -> seg(left, bottom)
                2, 13 -> seg(bottom, right)
                3, 12 -> seg(left, right)
                4, 11 -> seg(right, top)
                6, 9 -> seg(bottom, top)
                7, 8 -> seg(left, top)
                5 -> if ((a + b + c + d) / 4 > 0) { seg(bottom, right); seg(left, top) } else { seg(left, bottom); seg(right, top) }
                10 -> if ((a + b + c + d) / 4 > 0) { seg(left, bottom); seg(right, top) } else { seg(bottom, right); seg(left, top) }
            }
        }

        fun point(edge: Int): GraphPoint {
            return if (edge < vOffset) {
                val j = edge / w; val i = edge % w
                val f0 = v[j * w + i]; val f1 = v[j * w + i + 1]; val t = f0 / (f0 - f1)
                GraphPoint(xMin + (i + t) * dx, yMin + j * dy)
            } else {
                val r = edge - vOffset; val j = r / w; val i = r % w
                val f0 = v[j * w + i]; val f1 = v[(j + 1) * w + i]; val t = f0 / (f0 - f1)
                GraphPoint(xMin + i * dx, yMin + (j + t) * dy)
            }
        }

        // stitch the pieces: every edge is shared by at most two pieces, so the pieces form simple chains and loops
        val byEdge = HashMap<Int, MutableList<Int>>()
        for (s in segA.indices) { byEdge.getOrPut(segA[s]) { mutableListOf() } += s; byEdge.getOrPut(segB[s]) { mutableListOf() } += s }
        val used = BooleanArray(segA.size)
        val out = ArrayList<List<GraphPoint>>()
        for (start in segA.indices) {
            if (used[start]) continue
            used[start] = true
            val chain = java.util.ArrayDeque<Int>(); chain.addLast(segA[start]); chain.addLast(segB[start])
            fun extend(atEnd: Boolean) {
                while (true) {
                    val edge = if (atEnd) chain.last else chain.first
                    val next = byEdge[edge]?.firstOrNull { !used[it] } ?: return
                    used[next] = true
                    val other = if (segA[next] == edge) segB[next] else segA[next]
                    if (atEnd) chain.addLast(other) else chain.addFirst(other)
                }
            }
            extend(true); extend(false)
            val pts = chain.map(::point)
            if (pts.size >= 2) out += pts
        }
        return out
    }

    /**
     * Where the curve is, so that it can be framed: scans square windows of growing size around the origin until the curve appears, and also
     * looks at +-10 (so that unbounded curves such as hyperbolas are shown with their branches). Null if nothing is found up to 100 000.
     */
    fun bounds(f: (Double, Double) -> Double): Box? {
        var radius = 0.001
        while (radius <= 100_000.0) {
            val found = crossingBox(f, radius)
            if (found != null) {
                if (radius < 10.0) crossingBox(f, 10.0)?.let { return found.union(it) }
                return found
            }
            radius *= 4.0
        }
        return null
    }

    private const val SCAN = 36

    /** Union of the grid cells of the window [-r, r]^2 that the curve passes through, or null. */
    private fun crossingBox(f: (Double, Double) -> Double, r: Double): Box? {
        val n = SCAN; val d = 2 * r / n; val w = n + 1
        val v = DoubleArray(w * w) { f(-r + (it % w) * d, -r + (it / w) * d) }
        var box: Box? = null
        for (j in 0 until n) for (i in 0 until n) {
            val c = doubleArrayOf(v[j * w + i], v[j * w + i + 1], v[(j + 1) * w + i], v[(j + 1) * w + i + 1])
            if (c.any { it.isNaN() || it.isInfinite() }) continue
            if (c.any { it > 0.0 } && c.any { it <= 0.0 }) {
                val cell = Box(-r + i * d, -r + (i + 1) * d, -r + j * d, -r + (j + 1) * d)
                box = box?.union(cell) ?: cell
            }
        }
        return box
    }
}
