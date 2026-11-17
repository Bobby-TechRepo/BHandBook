package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2

/** Moving the view around the infinite canvas. Pure. */
object CanvasView {
    /** Fitting a single small thing must not blow it up to fill the screen. */
    const val MAX_FIT_SCALE = 2f
    private const val MARGIN = 0.04f

    /**
     * The view that shows everything written on the canvas (ink, answers, graph panels) centred in a [width] x [height] viewport; the default view
     * for an empty canvas. Never zooms in past [MAX_FIT_SCALE], and never out past the transform's own limit (a vast canvas then simply overflows).
     */
    fun fitAll(strokes: List<com.bsoft.bhandbook.domain.model.Stroke>, layouts: Map<String, ExpressionLayout>, width: Float, height: Float): CanvasTransform {
        val clean = strokes.filter { s -> s.points.all { it.x.isFinite() && it.y.isFinite() } }
        val area = CanvasExport.area(clean, layouts.filterValues { l -> l.hitBounds.let { b -> b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite() } })
            ?: return CanvasTransform()
        if (!(width > 1f && height > 1f)) return CanvasTransform()
        val fitted = CanvasTransform().fitTo(area, width, height, MARGIN)
        if (fitted.scale <= MAX_FIT_SCALE) return fitted
        val s = MAX_FIT_SCALE
        return CanvasTransform(s, Vec2(width / 2f - area.centerX * s, height / 2f - area.centerY * s))
    }
}
