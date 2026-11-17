package com.bsoft.bhandbook.core.graphics

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Immutable world<->screen transform for the infinite canvas.
 *
 *   screen = world * scale + offset
 *
 * World coordinates are device independent and never bounded; only screen coordinates depend on the viewport.
 * Pan is unbounded by design (Floats comfortably address ~1e7 world units at full precision).
 */
data class CanvasTransform(val scale: Float = 1f, val offset: Vec2 = Vec2.Zero) {

    fun screenToWorld(p: Vec2) = Vec2((p.x - offset.x) / scale, (p.y - offset.y) / scale)
    fun worldToScreen(p: Vec2) = Vec2(p.x * scale + offset.x, p.y * scale + offset.y)

    fun panBy(dx: Float, dy: Float) = copy(offset = Vec2(offset.x + dx, offset.y + dy))

    /** Zoom by [factor] so the world point currently under [focal] stays under [focal]. Scale is clamped. */
    fun zoomAround(focal: Vec2, factor: Float): CanvasTransform {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val worldFocal = screenToWorld(focal)
        return CanvasTransform(
            scale = newScale,
            offset = Vec2(focal.x - worldFocal.x * newScale, focal.y - worldFocal.y * newScale),
        )
    }

    /** Combined pan+pinch step: content follows the fingers (centroid moves by [pan] while zooming about it). */
    fun applyGesture(centroid: Vec2, pan: Vec2, zoom: Float): CanvasTransform =
        zoomAround(centroid, zoom).panBy(pan.x, pan.y)

    fun doubleTapZoom(focal: Vec2): CanvasTransform =
        if (scale < DOUBLE_TAP_ZOOM * 0.99f) zoomAround(focal, DOUBLE_TAP_ZOOM / scale)
        else zoomAround(focal, 1f / scale)

    /** Transform that shows [rect] centred in a [width] x [height] viewport with a margin ("zoom into this expression"). */
    fun fitTo(rect: WorldRect, width: Float, height: Float, margin: Float = 0.15f): CanvasTransform {
        val usableW = width * (1f - 2f * margin); val usableH = height * (1f - 2f * margin)
        val sx = if (rect.width > 0f) usableW / rect.width else MAX_SCALE
        val sy = if (rect.height > 0f) usableH / rect.height else MAX_SCALE
        val s = minOf(sx, sy).coerceIn(MIN_SCALE, MAX_SCALE)
        return CanvasTransform(s, Vec2(width / 2f - rect.centerX * s, height / 2f - rect.centerY * s))
    }

    fun visibleWorldRect(width: Float, height: Float): WorldRect {
        val tl = screenToWorld(Vec2.Zero)
        val br = screenToWorld(Vec2(width, height))
        return WorldRect(tl.x, tl.y, br.x, br.y)
    }

    /**
     * World-space grid spacing (a power-of-two multiple of [baseSpacing]) chosen so the on-screen
     * spacing always lies in [baseSpacing/2, baseSpacing*2). Gives a stable, scale-aware grid.
     */
    fun gridSpacingWorld(baseSpacing: Float): Float {
        val k = floor(ln((baseSpacing / scale) / baseSpacing) / ln(2f) + 0.5f)
        // choose k so that baseSpacing*2^k*scale is in [0.75, 1.5) * baseSpacing... then widen band to [0.5,2)
        return baseSpacing * 2f.pow(k)
    }

    companion object {
        /**
         * How far out you can zoom: 0.2% (a ~500x overview). A guard, not a numerical limit: below about 2% normal handwriting is under 2 px tall, and every
         * visible stroke is redrawn each frame, so the limit also bounds the worst case. It was 0.02 and felt too tight on a canvas with many expressions.
         */
        const val MIN_SCALE = 0.002f
        const val MAX_SCALE = 40f
        const val DOUBLE_TAP_ZOOM = 2f
    }
}
