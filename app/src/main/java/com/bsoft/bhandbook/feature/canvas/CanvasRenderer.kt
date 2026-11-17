package com.bsoft.bhandbook.feature.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import com.bsoft.bhandbook.domain.editor.SystemSolution
import com.bsoft.bhandbook.domain.editor.TextLine
import com.bsoft.bhandbook.domain.graph.GraphData
import com.bsoft.bhandbook.domain.graph.GraphGuides
import com.bsoft.bhandbook.domain.model.CanvasBackground
import com.bsoft.bhandbook.domain.model.ProcessingStatus
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.model.StrokePoint
import kotlin.math.floor

/** Colours the renderer needs, resolved once per frame from the theme and canvas background. */
data class RenderPalette(val background: Color, val grid: Color, val ink: Color, val result: Color, val steps: Color, val warning: Color, val error: Color, val selection: Color)

/** [appDark]: the app is in its dark theme, so every canvas is drawn dark (a canvas can also be dark by itself). */
fun paletteFor(bg: CanvasBackground, primary: Color, secondary: Color, errorColor: Color, tertiary: Color, appDark: Boolean = false): RenderPalette {
    val dark = bg == CanvasBackground.DARK || appDark
    return RenderPalette(
        background = if (dark) Color(0xFF121212) else Color.White,
        grid = if (dark) Color(0x33FFFFFF) else Color(0x22000000),
        ink = if (dark) Color(0xFFF2F2F2) else Color(0xFF111111),
        result = secondary, steps = if (dark) Color(0xFFB8BCC4) else Color(0xFF555B66), warning = tertiary, error = errorColor, selection = primary,
    )
}

/** Smooth paths are expensive to rebuild, so they are cached per stroke instance (strokes are immutable). */
class StrokePathCache {
    private val map = HashMap<String, Pair<Stroke, Path>>()
    fun pathFor(s: Stroke): Path {
        map[s.id]?.let { if (it.first === s) return it.second }
        return smoothPath(s.points).also { map[s.id] = s to it }
    }
    fun retainOnly(ids: Set<String>) { if (map.size > ids.size * 2 + 64) map.keys.retainAll(ids) }
}

/** Quadratic curve through segment midpoints: smooth without overshoot, O(n). */
fun smoothPath(pts: List<StrokePoint>): Path {
    val p = Path()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) { p.lineTo(pts[0].x, pts[0].y); return p }
    for (i in 1 until pts.size - 1) {
        p.quadraticTo(pts[i].x, pts[i].y, (pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
    }
    p.lineTo(pts.last().x, pts.last().y)
    return p
}

/** Width multiplier from the device's reported pressure; devices without pressure report a constant 1 and are left unchanged. */
fun pressureFactor(s: Stroke): Float {
    val ps = s.points
    if (ps.isEmpty()) return 1f
    val mean = ps.sumOf { it.pressure.toDouble() }.toFloat() / ps.size
    return if (ps.all { it.pressure == 1f }) 1f else (0.7f + 0.6f * mean).coerceIn(0.6f, 1.4f)
}

/** Caches measured text. [sizePx] is in WORLD pixels (converted to sp here so layout and drawing agree under any density/font scale). */
class TextCache(private val measurer: TextMeasurer, private val density: Density, private val capacity: Int = 400) {
    private data class Key(val text: String, val size: Int, val color: Long, val bold: Boolean, val handwritten: Boolean)
    private val map = object : LinkedHashMap<Key, TextLayoutResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TextLayoutResult>?) = size > capacity
    }
    /** [handwritten]: the Handlee font (answers and steps) instead of the plain sans-serif; it has one weight, so [bold] is ignored then. */
    fun layout(text: String, sizePx: Float, color: Color, bold: Boolean, handwritten: Boolean = false): TextLayoutResult =
        map.getOrPut(Key(text, (sizePx * 2).toInt(), color.value.toLong(), bold, handwritten)) {
            val fontSize = with(density) { sizePx.toSp() }
            measurer.measure(AnnotatedString(text), TextStyle(color = color, fontSize = fontSize, fontFamily = if (handwritten) com.bsoft.bhandbook.ui.theme.Handlee else FontFamily.SansSerif,
                fontWeight = if (bold && !handwritten) FontWeight.SemiBold else FontWeight.Normal))
        }
}

fun DrawScope.drawCanvasBackground(bg: CanvasBackground, t: CanvasTransform, p: RenderPalette) {
    drawRect(p.background)
    if (bg == CanvasBackground.GRID) drawGrid(t, p)      // WHITE and DARK are plain workspaces
}

/** Grid lines are placed in world space but spaced so they always stay 20-80 px apart on screen. */
fun DrawScope.drawGrid(t: CanvasTransform, p: RenderPalette) {
    val spacing = t.gridSpacingWorld(40f)
    val v = t.visibleWorldRect(size.width, size.height)
    var x = floor(v.left / spacing) * spacing
    while (x <= v.right) { val sx = t.worldToScreen(Vec2(x, 0f)).x; drawLine(p.grid, Offset(sx, 0f), Offset(sx, size.height), 1f); x += spacing }
    var y = floor(v.top / spacing) * spacing
    while (y <= v.bottom) { val sy = t.worldToScreen(Vec2(0f, y)).y; drawLine(p.grid, Offset(0f, sy), Offset(size.width, sy), 1f); y += spacing }
}

fun DrawScope.drawStrokes(
    strokes: List<Stroke>, cache: StrokePathCache, t: CanvasTransform, p: RenderPalette,
    dragIds: Set<String>, dragDelta: Vec2,
    /** Expressions being resized, the point they are scaled about, and the factor so far (a live preview: the real change is made when the finger lifts). */
    resizePreview: Triple<Set<String>, Vec2, Float>? = null,
) {
    val visible = t.visibleWorldRect(size.width, size.height)
    withTransform({
        translate(t.offset.x, t.offset.y)
        scale(t.scale, t.scale, pivot = Offset.Zero)
    }) {
        for (s in strokes) {
            val dragged = s.expressionId != null && s.expressionId in dragIds
            val rp = resizePreview; val scaled = rp != null && s.expressionId != null && s.expressionId in rp.first
            val b = if (dragged) s.bounds.translated(dragDelta.x, dragDelta.y) else s.bounds
            if (b.isEmpty || (!scaled && !b.inflate(s.width, s.width).intersects(visible))) continue            // viewport culling
            val color = if (s.color == 0L) p.ink else Color(s.color.toInt())
            val w = s.width * pressureFactor(s)
            val tiny = s.bounds.width < w && s.bounds.height < w
            if (dragged || scaled) withTransform({ if (dragged) translate(dragDelta.x, dragDelta.y); if (scaled) scale(rp!!.third, rp.third, pivot = Offset(rp.second.x, rp.second.y)) }) { drawOne(s, cache, color, w, tiny) } else drawOne(s, cache, color, w, tiny)
        }
    }
}

private fun DrawScope.drawOne(s: Stroke, cache: StrokePathCache, color: Color, w: Float, tiny: Boolean) {
    if (tiny) drawCircle(color, radius = w * 0.6f, center = Offset(s.points[0].x, s.points[0].y))     // taps (decimal points) render as dots
    else drawPath(cache.pathFor(s), color, style = DrawStroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Result text, solution steps, status and the selection outline, all in world space so they zoom with the ink. */
fun DrawScope.drawExpressionOverlays(
    layouts: Map<String, ExpressionLayout>, statuses: Map<String, ProcessingStatus>, selectedId: String?,
    t: CanvasTransform, p: RenderPalette, texts: TextCache, dragIds: Set<String>, dragDelta: Vec2,
    systems: Map<String, SystemSolution> = emptyMap(), graphs: Map<String, GraphData> = emptyMap(),
    graphDragId: String? = null, graphDragDelta: Vec2 = Vec2.Zero,
    graphStyle: com.bsoft.bhandbook.domain.model.GraphSettings = com.bsoft.bhandbook.domain.model.GraphSettings(),
    graphResizeId: String? = null, graphResizeFactor: Float = 1f,
    /** Drawing for a shared image / PDF: no selection outline, no status hints, no resize grips. */
    forExport: Boolean = false,
    /** The point traced on each graph, and a viewport being dragged / pinched right now (drawn by re-mapping the existing curves, so a gesture costs nothing). */
    graphTraces: Map<String, com.bsoft.bhandbook.domain.graph.TracePoint> = emptyMap(),
    graphViewPreview: Pair<String, com.bsoft.bhandbook.domain.graph.GraphViewport>? = null,
    /** Expressions selected together (the multi-select mode); drawn like the single selection. */
    multiSelected: Set<String> = emptySet(),
    resizePreview: Triple<Set<String>, Vec2, Float>? = null,
) {
    val visible: WorldRect = t.visibleWorldRect(size.width, size.height)
    withTransform({
        translate(t.offset.x, t.offset.y)
        scale(t.scale, t.scale, pivot = Offset.Zero)
    }) {
        for ((id, l) in layouts) {
            // a graph that was dragged away from its expression is outside the expression's own bounds, but may still be on screen
            if (!l.hitBounds.intersects(visible) && l.graphRect?.intersects(visible) != true) continue
            val d = if (id in dragIds) dragDelta else Vec2.Zero
            withTransform({ translate(d.x, d.y); if (resizePreview != null && id in resizePreview.first) scale(resizePreview.third, resizePreview.third, pivot = Offset(resizePreview.second.x, resizePreview.second.y)) }) {
                if (id == selectedId || id in multiSelected) {
                    val r = l.hitBounds
                    drawRoundRect(p.selection.copy(alpha = 0.10f), Offset(r.left, r.top), androidx.compose.ui.geometry.Size(r.width, r.height), androidx.compose.ui.geometry.CornerRadius(l.fontSize * 0.3f))
                    drawRoundRect(p.selection, Offset(r.left, r.top), androidx.compose.ui.geometry.Size(r.width, r.height), androidx.compose.ui.geometry.CornerRadius(l.fontSize * 0.3f), style = DrawStroke(2f / t.scale))
                }
                l.result?.let { drawLineText(texts.layout(it.text, l.fontSize, p.result, bold = true, handwritten = true), it) }
                l.stepLines.forEach { drawLineText(texts.layout(it.text, l.stepFontSize, p.steps, bold = false, handwritten = true), it) }
                l.systemSummary?.let {
                    val c = if (systems[id]?.isError == true) p.warning else p.result
                    drawLineText(texts.layout(it.text, l.fontSize, c, bold = true, handwritten = true), it)
                }
                l.systemSteps.forEach { drawLineText(texts.layout(it.text, l.stepFontSize, p.steps, bold = false, handwritten = true), it) }
                l.graphRect?.let { r ->
                    graphs[id]?.let { g ->
                        val gd = if (id == graphDragId) graphDragDelta else Vec2.Zero
                        val f = if (id == graphResizeId) graphResizeFactor else 1f              // live preview while the corner grip is dragged
                        withTransform({ translate(gd.x, gd.y); scale(f, f, pivot = Offset(r.left, r.top)) }) { drawGraphPanel(g, r, p, texts, graphStyle, l.graphResizeGrip!!.width, showGrip = !forExport, trace = if (forExport) null else graphTraces[id], view = graphViewPreview?.takeIf { it.first == id }?.second ?: g.viewport) }
                    }
                }
                if (!forExport) l.status?.let {
                    val c = if (l.statusIsHint) p.steps else if (statuses[id] == ProcessingStatus.ERROR) p.error else p.warning
                    drawLineText(texts.layout(it.text, l.statusFontSize, c, bold = false), it)
                }
            }
        }
    }
}

/** The round handle at the corner of the selection: drag it to resize. Drawn at a fixed size on screen; it follows the preview while resizing. */
fun DrawScope.drawSelectionHandle(box: com.bsoft.bhandbook.domain.editor.SelectionBox, t: CanvasTransform, p: RenderPalette, preview: Triple<Set<String>, Vec2, Float>?) {
    var hx = box.handle.x; var hy = box.handle.y
    if (preview != null) { hx = preview.second.x + (hx - preview.second.x) * preview.third; hy = preview.second.y + (hy - preview.second.y) * preview.third }
    val c = t.worldToScreen(Vec2(hx, hy)); val center = Offset(c.x, c.y); val r = 11.dp.toPx()
    drawCircle(p.background, r + 2.dp.toPx(), center); drawCircle(p.selection, r, center)
    val a = r * 0.45f
    drawLine(p.background, Offset(center.x - a, center.y + a), Offset(center.x + a, center.y - a), 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(p.background, Offset(center.x, center.y + a), Offset(center.x + a, center.y), 2.dp.toPx(), cap = StrokeCap.Round)
}

/** A graph panel in world space: grid, tick labels, axes, curves (broken at asymptotes), vertical lines, marked intersections and a legend. */
/** [gripSize] comes from the layout, so the corner grip is drawn exactly where it can be grabbed. */
private fun DrawScope.drawGraphPanel(
    data: GraphData, rect: WorldRect, p: RenderPalette, texts: TextCache, style: com.bsoft.bhandbook.domain.model.GraphSettings, gripSize: Float, showGrip: Boolean = true,
    trace: com.bsoft.bhandbook.domain.graph.TracePoint? = null, view: com.bsoft.bhandbook.domain.graph.GraphViewport = data.viewport,
) {
    val fontSize = rect.width / ExpressionLayout.GRAPH_WIDTH_EM          // everything inside the panel scales with the panel
    val label = fontSize * 0.5f
    val unit = rect.width / 160f                                          // line widths scale with the panel
    val corner = androidx.compose.ui.geometry.CornerRadius(fontSize * 0.3f)
    val panel = androidx.compose.ui.geometry.Size(rect.width, rect.height)
    drawRoundRect(p.background.copy(alpha = 0.96f), Offset(rect.left, rect.top), panel, corner)
    drawRoundRect(p.grid, Offset(rect.left, rect.top), panel, corner, style = DrawStroke(unit))

    val v = view
    // the plotting area and the data -> panel mapping are shared with the gestures (pan, zoom, trace), so that what is drawn is what is touched
    val area = com.bsoft.bhandbook.domain.graph.GraphPlot.area(rect, v, data.equalScale)
    val plotL = area.left; val plotR = area.right; val plotT = area.top; val plotB = area.bottom
    val pw = plotR - plotL; val ph = plotB - plotT
    fun sx(x: Double): Float = area.screenX(x)
    fun sy(y: Double): Float = area.screenY(y)

    for (tick in data.xTicks) {
        val x = sx(tick.value)
        if (style.showGrid) drawLine(p.grid, Offset(x, plotT), Offset(x, plotB), unit * 0.8f)
        val tl = texts.layout(tick.label, label, p.steps, bold = false)
        drawText(tl, topLeft = Offset(x - tl.size.width / 2f, plotB + label * 0.3f))
    }
    for (tick in data.yTicks) {
        val y = sy(tick.value)
        if (style.showGrid) drawLine(p.grid, Offset(plotL, y), Offset(plotR, y), unit * 0.8f)
        val tl = texts.layout(tick.label, label, p.steps, bold = false)
        drawText(tl, topLeft = Offset(plotL - tl.size.width - label * 0.3f, y - tl.size.height / 2f))
    }
    if (0.0 >= v.yMin && 0.0 <= v.yMax) drawLine(p.ink, Offset(plotL, sy(0.0)), Offset(plotR, sy(0.0)), unit * 1.4f)
    if (0.0 >= v.xMin && 0.0 <= v.xMax) drawLine(p.ink, Offset(sx(0.0), plotT), Offset(sx(0.0), plotB), unit * 1.4f)

    val lineW = unit * com.bsoft.bhandbook.domain.model.GraphSettings.BASE_LINE_UNITS * style.lineWidth        // user-adjustable; the old fixed 2.2 was too heavy
    val colors = listOf(p.selection, p.result, p.warning, p.error)
    clipRect(plotL, plotT, plotR, plotB) {
        data.curves.forEachIndexed { i, curve ->
            for (segment in curve.segments) {
                val path = Path()
                segment.forEachIndexed { k, pt -> if (k == 0) path.moveTo(sx(pt.x), sy(pt.y)) else path.lineTo(sx(pt.x), sy(pt.y)) }
                drawPath(path, colors[i % colors.size], style = DrawStroke(lineW, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        data.verticalLines.forEachIndexed { j, line ->
            val x = sx(line.x)
            drawLine(colors[(data.curves.size + j) % colors.size], Offset(x, plotT), Offset(x, plotB), lineW)
        }
    }
    if (style.showGuides) {
        // dashed lines from each intersection to the axes, with its values printed on them (on the nearest edge if an axis is off-screen)
        val axisYpx = sy(0.0.coerceIn(v.yMin, v.yMax)); val axisXpx = sx(0.0.coerceIn(v.xMin, v.xMax))
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(unit * 4f, unit * 3f))
        for (g in GraphGuides.of(data.markers, v)) {
            val px = sx(g.x); val py = sy(g.y)
            if (g.hasGuides) {
                drawLine(p.result, Offset(px, py), Offset(px, sy(g.xAxisY)), unit, pathEffect = dash)
                drawLine(p.result, Offset(px, py), Offset(sx(g.yAxisX), py), unit, pathEffect = dash)
            }
            g.xLabel?.let { s ->
                val tl = texts.layout(s, label * 0.95f, p.result, bold = true)
                val below = axisYpx + tl.size.height * 0.15f
                drawRect(p.background.copy(alpha = 0.85f), Offset(px - tl.size.width / 2f, below), androidx.compose.ui.geometry.Size(tl.size.width.toFloat(), tl.size.height.toFloat()))
                drawText(tl, topLeft = Offset(px - tl.size.width / 2f, below))
            }
            g.yLabel?.let { s ->
                val tl = texts.layout(s, label * 0.95f, p.result, bold = true)
                val left = axisXpx - tl.size.width - label * 0.2f
                drawRect(p.background.copy(alpha = 0.85f), Offset(left, py - tl.size.height / 2f), androidx.compose.ui.geometry.Size(tl.size.width.toFloat(), tl.size.height.toFloat()))
                drawText(tl, topLeft = Offset(left, py - tl.size.height / 2f))
            }
        }
    }
    for (m in data.markers) {
        val c = Offset(sx(m.x), sy(m.y))
        drawCircle(p.result, unit * 3.2f, c); drawCircle(p.background, unit * 1.5f, c)
        if (style.showMarkerLabels) {
            val tl = texts.layout(m.label, label, p.result, bold = true)
            drawText(tl, topLeft = Offset((c.x + unit * 6f).coerceAtMost(plotR - tl.size.width).coerceAtLeast(plotL), (c.y - tl.size.height - unit * 2f).coerceAtLeast(plotT)))
        }
    }
    trace?.let { tp ->
        val c = Offset(sx(tp.x), sy(tp.y))
        if (c.x in plotL..plotR && c.y in plotT..plotB) {
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(unit * 3f, unit * 3f))
            drawLine(p.steps, Offset(c.x, plotT), Offset(c.x, plotB), unit, pathEffect = dash)
            if (tp.curve > -2) drawLine(p.steps, Offset(plotL, c.y), Offset(plotR, c.y), unit, pathEffect = dash)
            drawCircle(p.background, unit * 4.8f, c); drawCircle(if (tp.curve >= 0) colors[tp.curve % colors.size] else p.result, unit * 3.4f, c)
            val tl = texts.layout(com.bsoft.bhandbook.domain.graph.GraphTrace.readout(tp), label * 1.05f, p.ink, bold = true)
            val bx = (c.x + unit * 7f).coerceAtMost(plotR - tl.size.width).coerceAtLeast(plotL)
            val by = (c.y - tl.size.height - unit * 7f).let { if (it < plotT) c.y + unit * 7f else it }.coerceAtMost(plotB - tl.size.height)
            drawRect(p.background.copy(alpha = 0.92f), Offset(bx - unit * 2f, by), androidx.compose.ui.geometry.Size(tl.size.width + unit * 4f, tl.size.height.toFloat()))
            drawText(tl, topLeft = Offset(bx, by))
        }
    }
    // resize grip in the bottom-right corner (not in shared images)
    if (showGrip) run {
        val g = gripSize
        for (k in 1..2) drawLine(p.steps, Offset(rect.right - g * 0.2f - k * g * 0.22f, rect.bottom - g * 0.2f), Offset(rect.right - g * 0.2f, rect.bottom - g * 0.2f - k * g * 0.22f), unit * 1.2f)
    }
    data.note?.let {
        val tl = texts.layout(it, label * 1.1f, p.warning, bold = true)
        drawText(tl, topLeft = Offset(plotL + (pw - tl.size.width) / 2f, plotT + (ph - tl.size.height) / 2f))
    }
    if (style.showLegend) (data.curves.map { it.label } + data.verticalLines.map { it.label }).forEachIndexed { i, name ->
        val tl = texts.layout(name, label * 0.9f, colors[i % colors.size], bold = true)
        drawText(tl, topLeft = Offset(plotL + label * 0.5f, plotT + label * 0.3f + i * label * 1.25f))
    }
}

/** Draws one line of layout text: right-aligned when asked, and turned about its origin when the user wrote the ink turned. */
private fun DrawScope.drawLineText(layout: TextLayoutResult, line: TextLine) {
    val x = if (line.alignRight) line.origin.x - layout.size.width else line.origin.x
    if (line.rotation == 0.0) { drawText(layout, topLeft = Offset(x, line.origin.y)); return }
    withTransform({ rotate(line.rotation.toFloat(), pivot = Offset(line.origin.x, line.origin.y)) }) { drawText(layout, topLeft = Offset(line.origin.x, line.origin.y)) }
}
