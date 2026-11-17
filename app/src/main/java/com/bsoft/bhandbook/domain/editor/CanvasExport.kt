package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.Stroke
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How the exported area maps onto an image or page: pixel size, scale, and the offset so that [WorldRect.left]/[WorldRect.top] land on (0, 0). */
class ExportPlan(val width: Int, val height: Int, val scale: Float, val offsetX: Float, val offsetY: Float)

/** What to export (everything on the canvas), how large to make it, and what to call the file. Pure, so it is tested without a device. */
object CanvasExport {
    const val MIN_MARGIN = 24f
    private const val MARGIN_FRACTION = 0.04f
    private const val MAX_NAME = 40

    /**
     * The world-space area that contains all the ink, every expression's text (results, steps, system answers) and every graph panel wherever it was
     * dragged, plus a margin. Null when there is nothing to export.
     */
    fun area(strokes: List<Stroke>, layouts: Map<String, ExpressionLayout>): WorldRect? {
        var r = WorldRect.Empty
        for (s in strokes) if (!s.bounds.isEmpty) r = r.union(s.bounds.inflate(s.width / 2f, s.width / 2f))
        for (l in layouts.values) { r = r.union(l.hitBounds); l.graphRect?.let { r = r.union(it) } }
        if (r.isEmpty) return null
        val margin = max(MIN_MARGIN, MARGIN_FRACTION * max(r.width, r.height))
        return r.inflate(margin, margin)
    }

    /** Pixel (or point) size for [area]: as large as [maxScale] allows, but never larger than [maxSide] on the long side, and never empty. */
    fun plan(area: WorldRect, maxSide: Int, maxScale: Float): ExportPlan {
        val w = max(area.width, 1e-3f); val h = max(area.height, 1e-3f)
        val scale = min(maxScale, min(maxSide / w, maxSide / h)).let { if (it.isFinite() && it > 0f) it else 1f }
        return ExportPlan(ceil(w * scale).toInt().coerceIn(1, maxSide), ceil(h * scale).toInt().coerceIn(1, maxSide), scale, -area.left * scale, -area.top * scale)
    }

    /** "Canvas 1" -> "Canvas_1.png": letters and digits of any language are kept, everything else becomes one underscore; never empty, never a path. */
    fun fileName(canvasName: String, extension: String): String {
        val cleaned = buildString {
            var lastUnderscore = false
            for (c in canvasName.trim()) {
                val keep = c.isLetterOrDigit() || c == '-'
                if (keep) { append(c); lastUnderscore = false } else if (!lastUnderscore) { append('_'); lastUnderscore = true }
            }
        }.trim('_').take(MAX_NAME).trim('_')
        return (cleaned.ifEmpty { "canvas" }) + "." + extension
    }


    /** A name for an imported canvas that no existing canvas has: "Homework", then "Homework (2)", "Homework (3)" ... (case does not matter). */
    fun uniqueName(wanted: String, existing: Collection<String>): String {
        val base = wanted.trim().ifEmpty { "Imported canvas" }
        val taken = existing.map { it.trim().lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }
}
