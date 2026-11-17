package com.bsoft.bhandbook.domain.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * A rotated COPY of the strokes about ([pivotX], [pivotY]). Angles are in radians and, because the screen's y axis points down,
 * a positive angle turns the ink clockwise as the user sees it. Ids, widths, colours, expression ids, timestamps and pressure are kept,
 * and the original strokes are never modified.
 */
fun List<Stroke>.rotated(radians: Double, pivotX: Double, pivotY: Double): List<Stroke> {
    val c = cos(radians); val s = sin(radians)
    return map { st ->
        st.copy(points = st.points.map { p ->
            val dx = p.x - pivotX; val dy = p.y - pivotY
            p.copy(x = (pivotX + dx * c - dy * s).toFloat(), y = (pivotY + dx * s + dy * c).toFloat())
        })
    }
}
