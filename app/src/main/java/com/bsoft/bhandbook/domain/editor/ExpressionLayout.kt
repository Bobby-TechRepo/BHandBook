package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.ProcessingStatus
import com.bsoft.bhandbook.domain.model.rotated
import kotlin.math.max

/** Where the typeset result, solution steps and status of an expression are drawn, in world coordinates. */
/** [rotation]: degrees clockwise about [origin] (the top-left of the text), for answers written the way the user turned the ink. */
data class TextLine(val text: String, val origin: Vec2, val alignRight: Boolean = false, val rotation: Double = 0.0)

/** Size and vertical position of the user's typical symbol (median over the upright strokes), measured from the ink itself. */
class GlyphMetrics(val height: Float, val centerY: Float)

class ExpressionLayout(
    val ink: WorldRect,
    val fontSize: Float,
    val stepFontSize: Float,
    val statusFontSize: Float,
    val result: TextLine?,
    val stepLines: List<TextLine>,
    val status: TextLine?,
    val hitBounds: WorldRect,
    /** Answer of the simultaneous equations this expression anchors (the lowest one), its steps, and the graph panel if one is shown. */
    val systemSummary: TextLine? = null,
    val systemSteps: List<TextLine> = emptyList(),
    val graphRect: WorldRect? = null,
    /** True when [status] is a helpful hint (e.g. "can be graphed") rather than a problem, so it is not drawn as an error. */
    val statusIsHint: Boolean = false,
) {
    /** The corner grip (bottom right) that is dragged to enlarge or shrink the graph. */
    val graphResizeGrip: WorldRect? get() = graphRect?.let { WorldRect(it.right - GRAPH_GRIP_EM * fontSize, it.bottom - GRAPH_GRIP_EM * fontSize, it.right, it.bottom) }

    companion object {
        const val MIN_FONT = 18f
        const val MAX_FONT = 120f
        /** Height of a sans-serif digit as a fraction of the font size, and where its vertical centre lies below the top of the text box. */
        const val DIGIT_HEIGHT_RATIO = 0.72f
        const val DIGIT_CENTER_FROM_TOP = 0.57f
        const val STEP_FONT_SCALE = 0.85f
        const val STATUS_FONT_SCALE = 0.5f

        /** Median height and centre line of the upright strokes (flat bars and dots are ignored), or null if there are none. */
        fun glyphMetrics(strokes: List<com.bsoft.bhandbook.domain.model.Stroke>): GlyphMetrics? {
            val tall = strokes.map { it.bounds }.filter { !it.isEmpty && it.height > 0f && it.height >= 0.4f * maxOf(it.width, it.height) }
            if (tall.isEmpty()) return null
            fun median(v: List<Float>) = v.sorted()[v.size / 2]
            return GlyphMetrics(median(tall.map { it.height }), median(tall.map { it.centerY }))
        }
        private const val CHAR_WIDTH = 0.62f      // average glyph width in em, for extent estimates
        private const val LINE = 1.35f
        /** The graph panel is this many font sizes wide, and a bit shorter than wide. */
        const val MORE_THAN_ONE_VARIABLE = "Equations with more than one variable"
        const val GRAPH_HINT = "Two unknowns: tap Graph to plot it"
        const val GRAPH_GRIP_EM = 1.1f
        const val MIN_GRAPH_SCALE = 0.4f
        const val MAX_GRAPH_SCALE = 6f
        fun clampGraphScale(s: Float) = if (s.isNaN()) 1f else s.coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
        const val GRAPH_WIDTH_EM = 16f
        const val GRAPH_ASPECT = 0.75f

        /** With [glyph] the text is as large as the user's handwriting; without it the size is derived from the bounding box (clamped). */
        fun of(
            ink: WorldRect, e: ExpressionObject, glyph: GlyphMetrics? = null, column: com.bsoft.bhandbook.core.recognition.ColumnLayout? = null,
            system: SystemSolution? = null, showGraph: Boolean = false, graphable: Boolean = false, graphOffset: Vec2 = Vec2.Zero, graphScale: Float = 1f,
            /** When the ink was written turned by [rotation] degrees, [ink] and [glyph] are measured on the ink turned back upright; [worldInk] and [pivot] place the result on the canvas. */
            rotation: Double = 0.0, pivot: Vec2 = Vec2(ink.centerX, ink.centerY), worldInk: WorldRect? = null,
        ): ExpressionLayout? {
            if (ink.isEmpty) return null
            val font = glyph?.let { (it.height / DIGIT_HEIGHT_RATIO).coerceAtLeast(1f) } ?: (ink.height * 0.7f).coerceIn(MIN_FONT, MAX_FONT)
            val stepFont = font * STEP_FONT_SCALE; val statusFont = font * STATUS_FONT_SCALE
            var bounds = worldInk ?: ink
            val rad = Math.toRadians(rotation)
            fun turnPoint(p: Vec2): Vec2 {
                if (rotation == 0.0) return p
                val dx = p.x - pivot.x; val dy = p.y - pivot.y
                return Vec2((pivot.x + dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat(), (pivot.y + dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat())
            }
            fun turn(l: TextLine): TextLine = if (rotation == 0.0) l else l.copy(origin = turnPoint(l.origin), rotation = rotation)
            fun extend(l: TextLine, size: Float = font) {
                val w = l.text.length * CHAR_WIDTH * size
                val left = if (l.alignRight) l.origin.x - w else l.origin.x
                if (rotation == 0.0) { bounds = bounds.union(WorldRect(left, l.origin.y, left + w, l.origin.y + size * LINE)); return }
                val corners = listOf(Vec2(left, l.origin.y), Vec2(left + w, l.origin.y), Vec2(left, l.origin.y + size * LINE), Vec2(left + w, l.origin.y + size * LINE)).map(::turnPoint)
                bounds = bounds.union(WorldRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y }))
            }

            var cursorY = ink.bottom + font * 0.3f
            // while the steps are showing the answer is left out: the last step already is the answer (and the steps start where it would have been)
            val hideAnswer = e.solutionVisible && e.steps.isNotEmpty()
            val result = e.result?.takeUnless { hideAnswer }?.let { r ->
                val line = if (column != null) TextLine(r, Vec2(column.anchorX, column.anchorTop + font * 0.25f), alignRight = true).also { cursorY = it.origin.y + font * LINE }
                else if (e.resultInline) TextLine(r, Vec2(ink.right + font * 0.5f, if (glyph != null) glyph.centerY - DIGIT_CENTER_FROM_TOP * font else ink.centerY - font / 2f))
                else TextLine(r, Vec2(ink.left, cursorY).also { cursorY += font * LINE })
                extend(line); line
            }
            // inline results sit beside the ink, so steps start below the ink; below results already advanced the cursor
            val steps = if (e.result != null && e.solutionVisible) e.steps.map { s ->
                TextLine("= ${s.resulting}", Vec2(ink.left, cursorY)).also { cursorY += stepFont * LINE; extend(it, stepFont) }
            } else emptyList()
            // simultaneous equations: the answer goes under the lowest equation; the other members stay quiet
            var systemSummary: TextLine? = null; var systemSteps: List<TextLine> = emptyList()
            if (system != null && system.anchorId == e.id) {
                if (!(e.solutionVisible && system.steps.isNotEmpty())) systemSummary = TextLine(system.summary, Vec2(ink.left, cursorY)).also { cursorY += font * LINE; extend(it) }
                if (e.solutionVisible) systemSteps = system.steps.map { st -> TextLine(st.resulting, Vec2(ink.left, cursorY)).also { cursorY += stepFont * LINE; extend(it, stepFont) } }
            }
            var hint = false
            val status = when (e.status) {
ProcessingStatus.ERROR -> if (system != null) null else e.error?.let { msg ->
                    if (graphable && msg.startsWith(MORE_THAN_ONE_VARIABLE)) {
                        // the graph is the answer to "two unknowns": offer it, and say nothing once it is showing
                        if (showGraph) null else { hint = true; TextLine(GRAPH_HINT, Vec2(ink.left, ink.bottom + font * 0.3f)) }
                    }
                    else TextLine(msg, Vec2(ink.left, ink.bottom + font * 0.3f))
                }
                ProcessingStatus.RECOGNIZING -> TextLine("Recognizing…", Vec2(ink.left, ink.bottom + font * 0.3f))
                ProcessingStatus.NEEDS_REVIEW -> TextLine("Check: ${e.recognized ?: "?"}  (tap to correct)", Vec2(ink.left, ink.bottom + font * 0.3f))
                else -> null
            }?.also { extend(it, statusFont) }
            var graph: WorldRect? = null
            if (showGraph) {
                val w = GRAPH_WIDTH_EM * font * clampGraphScale(graphScale)
                // the graph sits beside the expression until the user drags it; it is not part of the expression's own tap area
                graph = WorldRect(bounds.right + font * 0.8f + graphOffset.x, ink.top + graphOffset.y, bounds.right + font * 0.8f + graphOffset.x + w, ink.top + graphOffset.y + w * GRAPH_ASPECT)
            }
            return ExpressionLayout(worldInk ?: ink, font, stepFont, statusFont, result?.let(::turn), steps.map(::turn), status?.let(::turn), bounds.inflate(font * 0.25f, font * 0.25f), systemSummary?.let(::turn), systemSteps.map(::turn), graph, hint)
        }

        /** Layouts for every expression that has ink; one O(strokes) pass. */
        fun all(
            strokes: List<com.bsoft.bhandbook.domain.model.Stroke>, expressions: Map<String, ExpressionObject>,
            systems: Map<String, SystemSolution> = emptyMap(), graphs: Set<String> = emptySet(), graphable: Set<String> = emptySet(),
            graphOffsets: Map<String, Vec2> = emptyMap(), graphScales: Map<String, Float> = emptyMap(), defaultGraphScale: Float = 1f,
        ): Map<String, ExpressionLayout> {
            val bounds = HashMap<String, WorldRect>()
            for (s in strokes) s.expressionId?.let { id -> bounds[id] = bounds[id]?.union(s.bounds) ?: s.bounds }
            val out = LinkedHashMap<String, ExpressionLayout>()
            val byExpression = strokes.filter { it.expressionId != null }.groupBy { it.expressionId!! }
            for ((id, e) in expressions) bounds[id]?.let { b ->
                val own = byExpression[id]
                val column = if (e.result != null) own?.let { com.bsoft.bhandbook.core.recognition.ColumnArithmetic.detect(it) } else null
                val system = systems[id]
                val showGraph = id in graphs && (system == null || system.anchorId == id)      // a system's graph belongs to its lowest equation
                val turned = if (own != null && column == null) com.bsoft.bhandbook.core.comer.WritingDirection.of(own) else 0.0
                val canGraph = id in graphable
                if (turned == 0.0 || own == null) of(b, e, own?.let(::glyphMetrics), column, system, showGraph, canGraph, graphOffsets[id] ?: Vec2.Zero, graphScales[id] ?: defaultGraphScale)?.let { out[id] = it }
                else {
                    // written turned (device rotated, app not): lay the text out for upright ink, then turn it back onto the canvas
                    val pivot = Vec2(b.centerX, b.centerY)
                    val upright = own.rotated(-Math.toRadians(turned), pivot.x.toDouble(), pivot.y.toDouble())
                    val uInk = upright.fold(WorldRect.Empty) { acc, s -> acc.union(s.bounds) }
                    of(uInk, e, glyphMetrics(upright), null, system, showGraph, canGraph, graphOffsets[id] ?: Vec2.Zero, graphScales[id] ?: defaultGraphScale, turned, pivot, b)?.let { out[id] = it }
                }
            }
            return out
        }

        /** The expression whose graph panel is under [p] (the last, i.e. topmost, one if panels overlap), or null. Touching a panel moves it; it is never drawn through. */
        fun graphAt(layouts: Map<String, ExpressionLayout>, p: Vec2): String? =
            layouts.entries.lastOrNull { (_, l) -> l.graphRect?.let { g -> p.x in g.left..g.right && p.y in g.top..g.bottom } == true }?.key

        fun contains(layout: ExpressionLayout, p: Vec2): Boolean {
            val h = layout.hitBounds; val g = layout.graphRect
            return (p.x in h.left..h.right && p.y in h.top..h.bottom) || (g != null && p.x in g.left..g.right && p.y in g.top..g.bottom)
        }
    }
}
