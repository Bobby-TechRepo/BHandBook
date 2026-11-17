package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where a graph's plotting area is inside its panel, and how graph coordinates map to the panel's world coordinates and back. The renderer draws with
 * exactly this, and the gestures (pan, zoom, trace) turn touches into graph coordinates with it, so they can never disagree.
 */
class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float, val view: GraphViewport, val equalScale: Boolean) {
    private val pw = (right - left).toDouble(); private val ph = (bottom - top).toDouble()
    private val xSpan = view.xMax - view.xMin; private val ySpan = view.yMax - view.yMin
    private val xMid = (view.xMin + view.xMax) / 2; private val yMid = (view.yMin + view.yMax) / 2
    /** Implicit curves (circles ...) use the same scale on both axes. */
    private val k = if (equalScale) min(pw / xSpan, ph / ySpan) else 0.0

    fun screenX(x: Double): Float = if (equalScale) (left + pw / 2 + (x - xMid) * k).toFloat() else (left + (x - view.xMin) / xSpan * pw).toFloat()
    fun screenY(y: Double): Float = if (equalScale) (top + ph / 2 - (y - yMid) * k).toFloat() else (bottom - (y - view.yMin) / ySpan * ph).toFloat()
    fun graphX(px: Float): Double = if (equalScale) xMid + (px - (left + pw / 2)) / k else view.xMin + (px - left) / pw * xSpan
    fun graphY(py: Float): Double = if (equalScale) yMid - (py - (top + ph / 2)) / k else view.yMin + (bottom - py) / ph * ySpan
    /** Graph units per world unit, horizontally and vertically. */
    val xPerWorld: Double get() = if (equalScale) 1.0 / k else xSpan / pw
    val yPerWorld: Double get() = if (equalScale) 1.0 / k else ySpan / ph
    fun contains(px: Float, py: Float) = px in left..right && py in top..bottom
}

object GraphPlot {
    fun area(rect: WorldRect, view: GraphViewport, equalScale: Boolean): PlotArea {
        val label = rect.width / ExpressionLayout.GRAPH_WIDTH_EM * 0.5f                // everything inside the panel scales with the panel
        return PlotArea(rect.left + label * 3.6f, rect.top + label, rect.right - label, rect.bottom - label * 2.2f, view, equalScale)
    }
}

/** A traced point: on curve number [curve] (-1: an intersection or root it snapped to; -2 and below: a vertical line). */
data class TracePoint(val x: Double, val y: Double, val curve: Int, val label: String, val snapped: Boolean)

object GraphTrace {
    /** How far (as a fraction of the view) a tap may be from a curve and still trace it. */
    private const val MAX_DISTANCE = 0.3

    /**
     * The point of the graph nearest to the tap ([gx], [gy]), snapping to an intersection / root within ([tolX], [tolY]); null when the tap is far
     * from everything or the input is not a number. Works on the drawn polylines, so it handles explicit, vertical and implicit curves alike.
     */
    fun at(data: GraphData, gx: Double, gy: Double, tolX: Double, tolY: Double): TracePoint? {
        if (!gx.isFinite() || !gy.isFinite() || !(tolX > 0.0) || !(tolY > 0.0) || !tolX.isFinite() || !tolY.isFinite()) return null
        val v = data.viewport; val xs = v.xMax - v.xMin; val ys = v.yMax - v.yMin
        if (!(xs > 0.0) || !(ys > 0.0)) return null
        data.markers.filter { abs(it.x - gx) <= tolX && abs(it.y - gy) <= tolY }.minByOrNull { hypot((it.x - gx) / xs, (it.y - gy) / ys) }?.let { return TracePoint(it.x, it.y, -1, it.label, true) }

        var best: TracePoint? = null; var bestD = MAX_DISTANCE
        data.curves.forEachIndexed { ci, c ->
            for (seg in c.segments) for (i in 0 until seg.size - 1) {
                val ax = (seg[i].x - gx) / xs; val ay = (seg[i].y - gy) / ys; val bx = (seg[i + 1].x - gx) / xs; val by = (seg[i + 1].y - gy) / ys
                val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
                val t = if (len2 > 0.0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < bestD) { bestD = d; best = TracePoint(seg[i].x + t * (seg[i + 1].x - seg[i].x), seg[i].y + t * (seg[i + 1].y - seg[i].y), ci, c.label, false) }
            }
        }
        data.verticalLines.forEachIndexed { j, line ->
            val d = abs(line.x - gx) / xs
            if (d < bestD) { bestD = d; best = TracePoint(line.x, gy.coerceIn(v.yMin, v.yMax), -2 - j, "x = ${GraphMarker.number(line.x)}", false) }
        }
        return best
    }

    fun readout(p: TracePoint): String = if (p.curve <= -2) p.label else "(${GraphMarker.number(p.x)}, ${GraphMarker.number(p.y)})"

    /** Whether two traced points are the same spot (within half a tolerance), so tapping the marker again can switch it off. */
    fun same(a: TracePoint, b: TracePoint, tolX: Double, tolY: Double) = abs(a.x - b.x) <= tolX / 2 && abs(a.y - b.y) <= tolY / 2
}
