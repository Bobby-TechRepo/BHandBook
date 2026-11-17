package com.bsoft.bhandbook.core.graphics

/** Platform-independent 2D vector/point. Kept free of Compose/Android types so core logic is JVM-testable. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec2(x * k, y * k)
    fun distanceTo(o: Vec2): Float = kotlin.math.hypot(x - o.x, y - o.y)

    companion object { val Zero = Vec2(0f, 0f) }
}

/** Axis-aligned rectangle in whatever space the caller uses (world or screen). */
data class WorldRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = right < left || bottom < top

    fun intersects(o: WorldRect): Boolean =
        !isEmpty && !o.isEmpty && left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom

    fun inflate(dx: Float, dy: Float) = WorldRect(left - dx, top - dy, right + dx, bottom + dy)
    fun union(o: WorldRect): WorldRect = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> WorldRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    }
    fun translated(dx: Float, dy: Float) = WorldRect(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        val Empty = WorldRect(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    }
}
