package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import kotlin.math.hypot

/**
 * The box around the selected expression(s): [hit] includes their answers and steps, [ink] only the handwriting. The resize handle is at the bottom-right
 * of [hit]; resizing scales the ink about the top-left of [ink]. Pure.
 */
class SelectionBox(val hit: WorldRect, val ink: WorldRect) {
    val handle: Vec2 get() = Vec2(hit.right, hit.bottom)
    val anchor: Vec2 get() = Vec2(ink.left, ink.top)

    fun hitsHandle(p: Vec2, radius: Float): Boolean = p.x.isFinite() && p.y.isFinite() && hypot(p.x - handle.x, p.y - handle.y) <= radius

    /** The scale factor when the handle has been dragged [grown] world units to the right of where it was (bounded; 1 for nonsense). */
    fun factorFor(grown: Float): Float {
        val d = handle.x - anchor.x
        if (!grown.isFinite() || d <= 1e-3f) return 1f
        return ((d + grown) / d).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    companion object {
        const val MIN_FACTOR = 0.1f
        const val MAX_FACTOR = 8f

        fun of(layouts: Map<String, ExpressionLayout>, ids: Set<String>): SelectionBox? {
            val chosen = ids.mapNotNull { layouts[it] }
            if (chosen.isEmpty()) return null
            return SelectionBox(chosen.map { it.hitBounds }.reduce { a, b -> a.union(b) }, chosen.map { it.ink }.reduce { a, b -> a.union(b) })
        }
    }
}
