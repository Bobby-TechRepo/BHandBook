package com.bsoft.bhandbook.feature.canvas

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import com.bsoft.bhandbook.domain.model.StrokePoint

const val CANVAS_TAG = "infinite_canvas"

/**
 * The infinite canvas. Two stacked layers: the static layer (background, committed ink, results) and a dynamic layer
 * (the stroke being drawn, eraser cursor) so that every pointer move only invalidates the cheap layer.
 */
@Composable
fun CanvasSurface(state: CanvasUiState, actions: CanvasActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val appDark = colors.background.luminance() < 0.5f             // dark theme: the canvas is dark too, whatever its own background setting
    val palette = remember(state.background, colors, appDark) { paletteFor(state.background, colors.primary, colors.secondary, colors.error, colors.tertiary, appDark) }
    val pathCache = remember { StrokePathCache() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textCache = remember(measurer, density) { TextCache(measurer, density) }

    // Layout of every expression's text, recomputed only when ink or results change.
    val layouts: Map<String, ExpressionLayout> = remember(state.strokes, state.expressions, state.insights, state.graphs, state.graphOffsets, state.graphScales, state.settings.graph.defaultSize) {
        try { ExpressionLayout.all(state.strokes, state.expressions, state.insights.systems, state.graphs, state.insights.graphable, state.graphOffsets, state.graphScales, state.settings.graph.defaultSize) }
        catch (e: Exception) { android.util.Log.e("CanvasSurface", "layout failed; showing the ink without results", e); emptyMap() }       // never crash the drawing surface
    }
    remember(state.strokes) { pathCache.retainOnly(state.strokes.mapTo(HashSet()) { it.id }) }

    val transform by rememberUpdatedState(state.transform)
    val tool by rememberUpdatedState(state.tool)
    val mode by rememberUpdatedState(state.inputMode)
    val selected by rememberUpdatedState(state.selectedId)
    val layoutsNow by rememberUpdatedState(layouts)
    val actionsNow by rememberUpdatedState(actions)

    val active = remember { mutableStateListOf<StrokePoint>() }      // in-progress ink in world coordinates
    var eraserAt by remember { mutableStateOf<Offset?>(null) }
    var drag by remember { mutableStateOf<Pair<Set<String>, Vec2>?>(null) }      // the expression(s) being dragged and how far
    var graphDrag by remember { mutableStateOf<Pair<String, Vec2>?>(null) }
    var graphResize by remember { mutableStateOf<Pair<String, Float>?>(null) }      // key to live size factor while the corner grip is dragged
    var lastTap by remember { mutableStateOf<Pair<Long, Offset>?>(null) }
    var lastGraphTap by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var graphViewPreview by remember { mutableStateOf<Pair<String, com.bsoft.bhandbook.domain.graph.GraphViewport>?>(null) }      // live while two fingers pan / zoom a graph
    val multiNow by rememberUpdatedState(state.multi)
    // the box around the selection, with its resize handle (select tool only)
    val selectionIds = if (state.multi.active) state.multi.ids else setOfNotNull(state.selectedId)
    val selectionBox = remember(layouts, selectionIds, state.tool) { if (state.tool == Tool.SELECT) com.bsoft.bhandbook.domain.editor.SelectionBox.of(layouts, selectionIds) else null }
    val boxNow by rememberUpdatedState(selectionBox)
    val idsNow by rememberUpdatedState(selectionIds)
    var resize by remember { mutableStateOf<Triple<Set<String>, Vec2, Float>?>(null) }      // ids, the point scaled about, and the factor so far, while the handle is dragged
    val graphDataNow by rememberUpdatedState(state.graphData)
    val graphViewsNow by rememberUpdatedState(state.graphViews)

    val summary = remember(state.expressions) {
        val done = state.expressions.values.mapNotNull { e -> e.result?.let { "${e.displayText ?: e.recognized}: $it" } }
        "Infinite math canvas. ${state.expressions.size} expressions." + if (done.isNotEmpty()) " " + done.take(5).joinToString(". ") else ""
    }

    Box(modifier.fillMaxSize().semantics { contentDescription = summary }.testTag(CANVAS_TAG)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCanvasBackground(state.background, state.transform, palette)
            drawStrokes(state.strokes, pathCache, state.transform, palette, drag?.first.orEmpty(), drag?.second ?: Vec2.Zero, resize)
            drawExpressionOverlays(layouts, state.expressions.mapValues { it.value.status }, state.selectedId, state.transform, palette, textCache, drag?.first.orEmpty(), drag?.second ?: Vec2.Zero, state.insights.systems, state.graphData, graphDrag?.first, graphDrag?.second ?: Vec2.Zero, state.settings.graph, graphResize?.first, graphResize?.second ?: 1f, graphTraces = state.graphTrace, graphViewPreview = graphViewPreview, multiSelected = state.multi.ids, resizePreview = resize)
            selectionBox?.let { drawSelectionHandle(it, state.transform, palette, resize) }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                val eraserRadiusPx = 14.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    val activeTool = if (down.type == PointerType.Eraser) Tool.ERASER else tool

                    fun toWorld(o: Offset) = transform.screenToWorld(Vec2(o.x, o.y))

                    /** Pan + pinch (+ tap / double tap) until every pointer is up. Works with one finger or two. */
                    suspend fun AwaitPointerEventScope.navigate(first: PointerInputChange, allowTap: Boolean) {
                        var moved = 0f; var maxPointers = 1; val start = first.uptimeMillis; var end = start
                        var t = transform
                        while (true) {
                            val event = awaitPointerEvent()
                            maxPointers = maxOf(maxPointers, event.changes.count { it.pressed })
                            end = event.changes.maxOf { it.uptimeMillis }
                            val zoom = event.calculateZoom(); val pan = event.calculatePan(); val c = event.calculateCentroid(useCurrent = false)
                            if (zoom != 1f || pan != Offset.Zero) {
                                moved += pan.getDistance()
                                if (c.isSpecified) t = t.applyGesture(Vec2(c.x, c.y), Vec2(pan.x, pan.y), zoom)
                                actionsNow.onTransformChanged(t)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (allowTap && maxPointers == 1 && moved < touchSlop && end - start < 350) {
                            val prev = lastTap
                            if (prev != null && start - prev.first < 300 && (prev.second - first.position).getDistance() < touchSlop * 4) {
                                actionsNow.onTransformChanged(t.doubleTapZoom(Vec2(first.position.x, first.position.y))); lastTap = null
                            } else {
                                lastTap = start to first.position
                                if (tool == Tool.SELECT) {
                                    val w = toWorld(first.position)
                                    actionsNow.onSelect(layoutsNow.entries.lastOrNull { ExpressionLayout.contains(it.value, w) }?.key)
                                }
                            }
                        }
                    }

                    // the corner grip of a graph panel enlarges / shrinks that graph (any tool except the eraser)
                    val cornerWorld = toWorld(down.position)
                    val cornerKey = if (activeTool == Tool.ERASER) null
                        else layoutsNow.entries.lastOrNull { (_, l) -> l.graphResizeGrip?.let { g -> cornerWorld.x in g.left..g.right && cornerWorld.y in g.top..g.bottom } == true }?.key
                    // touching a graph panel anywhere moves that graph (any tool except the eraser): the panel is solid, so nothing is drawn through it
                    val gripKey = if (activeTool == Tool.ERASER) null else ExpressionLayout.graphAt(layoutsNow, toWorld(down.position))
                    if (cornerKey != null) {
                        val panelW = layoutsNow.getValue(cornerKey).graphRect!!.width
                        var grown = 0f
                        graphResize = cornerKey to 1f; down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            val d = ch.positionChange()
                            if (d != Offset.Zero) { grown += d.x / transform.scale; graphResize = cornerKey to ((panelW + grown) / panelW).coerceIn(0.1f, 20f); ch.consume() }
                            if (!ch.pressed) break
                        }
                        val factor = graphResize?.second ?: 1f
                        graphResize = null
                        if (factor != 1f) actionsNow.onResizeGraph(cornerKey, factor)
                    } else if (gripKey != null) {
                        var delta = Vec2.Zero; var movedPx = 0f; var twoFingers = false
                        graphDrag = gripKey to delta; down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) { twoFingers = true; break }                  // a second finger: pan / zoom the graph instead of moving the panel
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            val d = ch.positionChange()
                            if (d != Offset.Zero) { movedPx += d.getDistance(); delta = Vec2(delta.x + d.x / transform.scale, delta.y + d.y / transform.scale); graphDrag = gripKey to delta; ch.consume() }
                            if (!ch.pressed) break
                        }
                        graphDrag = null
                        val panelRect = layoutsNow[gripKey]?.graphRect; val panelData = graphDataNow[gripKey]
                        if (twoFingers) {
                            // two fingers on a panel move and zoom the GRAPH (everywhere else they move and zoom the canvas); the curves are only re-sampled when the fingers lift
                            if (panelRect != null && panelData != null) {
                                var vp = graphViewsNow[gripKey] ?: panelData.viewport
                                graphViewPreview = gripKey to vp
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val zoom = event.calculateZoom(); val pan = event.calculatePan(); val c = event.calculateCentroid(useCurrent = true)
                                    if ((zoom != 1f || pan != Offset.Zero) && c.isSpecified) {
                                        val before = com.bsoft.bhandbook.domain.graph.GraphPlot.area(panelRect, vp, panelData.equalScale)
                                        val at = toWorld(c)
                                        vp = vp.zoomedAbout(zoom.toDouble(), before.graphX(at.x), before.graphY(at.y))                      // the graph point under the fingers stays under them
                                        val after = com.bsoft.bhandbook.domain.graph.GraphPlot.area(panelRect, vp, panelData.equalScale)
                                        vp = vp.panned(-(pan.x / transform.scale) * after.xPerWorld, (pan.y / transform.scale) * after.yPerWorld)
                                        graphViewPreview = gripKey to vp
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                    if (event.changes.none { it.pressed }) break
                                }
                                graphViewPreview = null
                                if (vp.isValid) actionsNow.onGraphView(gripKey, vp)
                            }
                        } else if (delta != Vec2.Zero && movedPx >= touchSlop) actionsNow.onMoveGraph(gripKey, delta.x, delta.y)
                        else {
                            // a tap on a graph: twice resets the view to automatic framing, once traces the nearest point of the curves
                            val prev = lastGraphTap
                            if (prev != null && prev.second == gripKey && down.uptimeMillis - prev.first < 300) { actionsNow.onGraphView(gripKey, null); lastGraphTap = null }
                            else {
                                lastGraphTap = down.uptimeMillis to gripKey
                                if (panelRect != null && panelData != null) {
                                    val area = com.bsoft.bhandbook.domain.graph.GraphPlot.area(panelRect, graphViewsNow[gripKey] ?: panelData.viewport, panelData.equalScale)
                                    val w = toWorld(down.position)
                                    if (area.contains(w.x, w.y)) {
                                        val finger = 16.dp.toPx() / transform.scale                         // how far a fingertip may be from the curve, in world units
                                        actionsNow.onTraceGraph(gripKey, area.graphX(w.x), area.graphY(w.y), finger * area.xPerWorld, finger * area.yPerWorld)
                                    }
                                }
                            }
                            if (tool == Tool.SELECT) actionsNow.onSelect(gripKey)                              // a tap on a graph also selects its expression
                        }
                    } else when {
                        // ---- finger in navigation mode: pan / zoom
                        !isStylus && activeTool == Tool.PEN && mode == InputMode.NAVIGATE -> navigate(down, allowTap = true)

                        // ---- pen: stylus always draws, finger draws in DRAW mode; a second finger turns the gesture into pinch
                        activeTool == Tool.PEN -> {
                            active.clear()
                            var last: Offset? = null
                            var blocked = false                                              // set when the pen reaches a graph panel: the stroke ends at its edge
                            fun add(pos: Offset, time: Long, pressure: Float) {
                                if (blocked) return
                                val l = last
                                if (l != null && (pos - l).getDistance() < 0.8f) return      // ignore sub-pixel jitter
                                last = pos
                                val w = toWorld(pos)
                                if (ExpressionLayout.graphAt(layoutsNow, w) != null) { blocked = true; return }
                                active += StrokePoint(w.x, w.y, time, if (pressure > 0f && pressure <= 1f) pressure else 1f)
                            }
                            add(down.position, down.uptimeMillis, down.pressure); down.consume()
                            var pinched = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (!isStylus && event.changes.count { it.pressed } >= 2) { pinched = true; active.clear(); navigate(down, allowTap = false); break }
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                ch.historical.forEach { h -> add(h.position, h.uptimeMillis, ch.pressure) }
                                add(ch.position, ch.uptimeMillis, ch.pressure); ch.consume()
                                if (!ch.pressed) break
                            }
                            if (!pinched && active.isNotEmpty()) actionsNow.onStrokeFinished(active.toList())
                            active.clear()
                        }

                        // ---- eraser: removes ink under the cursor; one gesture = one undo step
                        activeTool == Tool.ERASER -> {
                            actionsNow.onEraseStart()
                            eraserAt = down.position
                            actionsNow.onEraseMove(toWorld(down.position), eraserRadiusPx); down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                if (!isStylus && event.changes.count { it.pressed } >= 2) { eraserAt = null; actionsNow.onEraseEnd(); navigate(down, allowTap = false); return@awaitEachGesture }
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                eraserAt = ch.position
                                actionsNow.onEraseMove(toWorld(ch.position), eraserRadiusPx); ch.consume()
                                if (!ch.pressed) break
                            }
                            eraserAt = null; actionsNow.onEraseEnd()
                        }

                        // ---- select: drag the selected expression to move it, otherwise pan / tap to select
                        else -> {
                            val w0 = toWorld(down.position)
                            // the round handle at the corner of the selection resizes it (the ink, and with it the answer and steps drawn from it)
                            val box = boxNow
                            if (box != null && box.hitsHandle(w0, 22.dp.toPx() / transform.scale)) {
                                val ids = idsNow; var grown = 0f
                                resize = Triple(ids, box.anchor, 1f); down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val d = ch.positionChange()
                                    if (d != Offset.Zero) { grown += d.x / transform.scale; resize = Triple(ids, box.anchor, box.factorFor(grown)); ch.consume() }
                                    if (!ch.pressed) break
                                }
                                val factor = resize?.third ?: 1f
                                resize = null
                                if (kotlin.math.abs(factor - 1f) > 0.01f) actionsNow.onResizeExpressions(ids, factor, box.anchor.x, box.anchor.y)
                                return@awaitEachGesture
                            }
                            val sel = selected
                            // dragging a selected expression moves it; in the multi-select mode, dragging any selected expression moves them all
                            val group: Set<String>? = if (multiNow.active) multiNow.ids.takeIf { ids -> ids.any { id -> layoutsNow[id]?.let { ExpressionLayout.contains(it, w0) } == true } }
                                else sel?.takeIf { layoutsNow[it]?.let { l -> ExpressionLayout.contains(l, w0) } == true }?.let { setOf(it) }
                            if (group != null) {
                                var delta = Vec2.Zero
                                drag = group to delta; down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val d = ch.positionChange()
                                    if (d != Offset.Zero) { delta = Vec2(delta.x + d.x / transform.scale, delta.y + d.y / transform.scale); drag = group to delta; ch.consume() }
                                    if (!ch.pressed) break
                                }
                                drag = null
                                if (delta != Vec2.Zero) actionsNow.onMoveExpressions(group, delta.x, delta.y)
                            } else navigate(down, allowTap = true)
                        }
                    }
                }
            },
        ) {
            val t = state.transform
            val p = palette
            if (active.isNotEmpty()) {
                val pts = active.toList()
                val width = state.penWidthPx / t.scale
                withTransform({ translate(t.offset.x, t.offset.y); scale(t.scale, t.scale, pivot = Offset.Zero) }) {
                    if (pts.size < 2) drawCircle(p.ink, width * 0.6f, Offset(pts[0].x, pts[0].y))
                    else drawPath(smoothPath(pts), p.ink, style = DrawStroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            eraserAt?.let { drawCircle(p.selection.copy(alpha = 0.35f), 14.dp.toPx(), it, style = DrawStroke(2f)) }
        }
    }
}
