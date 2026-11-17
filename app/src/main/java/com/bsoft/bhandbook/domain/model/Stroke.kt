package com.bsoft.bhandbook.domain.model

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import kotlin.math.hypot

data class StrokePoint(val x: Float, val y: Float, val t: Long = 0L, val pressure: Float = 1f)

/** A pen stroke in WORLD coordinates. Immutable; edits produce new instances. */
data class Stroke(
    val id: String,
    val points: List<StrokePoint>,
    val width: Float,
    val color: Long,
    val expressionId: String? = null,
) {
    val bounds: WorldRect by lazy(LazyThreadSafetyMode.NONE) {
        if (points.isEmpty()) WorldRect.Empty else {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            for (p in points) { if (p.x < l) l = p.x; if (p.x > r) r = p.x; if (p.y < t) t = p.y; if (p.y > b) b = p.y }
            WorldRect(l, t, r, b)
        }
    }

    fun translated(dx: Float, dy: Float) = copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
    fun withExpression(id: String?) = copy(expressionId = id)
}

object StrokeEraser {
    /** True if any point or segment of [stroke] lies within [radius] of [center]. */
    fun touches(stroke: Stroke, center: Vec2, radius: Float): Boolean {
        if (stroke.bounds.isEmpty || !stroke.bounds.inflate(radius, radius).let { it.left <= center.x && center.x <= it.right && it.top <= center.y && center.y <= it.bottom }) return false
        val pts = stroke.points
        if (pts.size == 1) return hypot(pts[0].x - center.x, pts[0].y - center.y) <= radius
        for (i in 0 until pts.size - 1) if (segDist(pts[i], pts[i + 1], center) <= radius) return true
        return false
    }

    /**
     * Removes the portion of [stroke] inside the eraser circle. Returns the surviving pieces
     * (0, 1 or many strokes). Untouched strokes are returned as-is (same instance).
     * New pieces get derived ids "<id>~<n>" and keep width/colour/expression association.
     */
    fun erase(stroke: Stroke, center: Vec2, radius: Float): List<Stroke> {
        if (!touches(stroke, center, radius)) return listOf(stroke)
        // Densify so a fast-drawn long segment can be partially erased.
        val dense = densify(stroke.points, maxGap = maxOf(radius / 2f, 0.5f))
        val pieces = mutableListOf<MutableList<StrokePoint>>()
        var cur = mutableListOf<StrokePoint>()
        for (p in dense) {
            if (hypot(p.x - center.x, p.y - center.y) <= radius) {
                if (cur.isNotEmpty()) { pieces += cur; cur = mutableListOf() }
            } else cur += p
        }
        if (cur.isNotEmpty()) pieces += cur
        val survivors = pieces.filter { it.size >= 2 }
        if (survivors.isEmpty()) return emptyList()
        return survivors.mapIndexed { i, pts -> stroke.copy(id = "${stroke.id}~${i + 1}", points = pts) }
    }

    private fun densify(pts: List<StrokePoint>, maxGap: Float): List<StrokePoint> {
        if (pts.size < 2) return pts
        val out = ArrayList<StrokePoint>(pts.size)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            out += a
            val d = hypot(b.x - a.x, b.y - a.y)
            val n = (d / maxGap).toInt()
            for (k in 1..n) {
                val f = k.toFloat() / (n + 1)
                out += StrokePoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, (a.t + (b.t - a.t) * f).toLong(), a.pressure + (b.pressure - a.pressure) * f)
            }
        }
        out += pts.last()
        return out
    }

    private fun segDist(a: StrokePoint, b: StrokePoint, c: Vec2): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((c.x - a.x) * dx + (c.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(a.x + t * dx - c.x, a.y + t * dy - c.y)
    }
}
