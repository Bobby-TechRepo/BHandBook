package com.bsoft.bhandbook.feature.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.core.icons.Glyph
import com.bsoft.bhandbook.core.icons.Glyphs
import com.bsoft.bhandbook.domain.editor.CanvasView
import com.bsoft.bhandbook.domain.editor.ExpressionLayout
import com.bsoft.bhandbook.domain.model.ExpressionObject
import com.bsoft.bhandbook.domain.model.ProcessingStatus
import com.bsoft.bhandbook.domain.model.ThemeMode
import com.bsoft.bhandbook.feature.settings.ModesSheet
import com.bsoft.bhandbook.feature.settings.TipsList
import com.bsoft.bhandbook.feature.settings.TipsSheet
import com.bsoft.bhandbook.feature.settings.SettingsSheet
import com.bsoft.bhandbook.feature.settings.modeLabel
import com.bsoft.bhandbook.feature.workspace.CanvasListSheet
import com.bsoft.bhandbook.ui.icons.GlyphIcon

/** Stateless: everything comes from [state], every interaction goes out through [actions]. */
@Composable
fun CanvasScreen(state: CanvasUiState, actions: CanvasActions) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // one-shot messages (e.g. the result of an import)
    androidx.compose.runtime.LaunchedEffect(state.notice) {
        state.notice?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); actions.onNoticeShown() }
    }
    // a backup file that is ready: write it (off the main thread), then hand it to the share sheet
    androidx.compose.runtime.LaunchedEffect(state.pendingFile) {
        state.pendingFile?.let { f ->
            try {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CanvasExporter.writeBackup(context, f.name, f.text) }
                CanvasExporter.sendBackup(context, file, state.canvas?.name ?: "Canvas")
            } catch (e: Exception) {
                android.util.Log.e("CanvasScreen", "backup failed", e); android.widget.Toast.makeText(context, "Could not create the backup file", android.widget.Toast.LENGTH_SHORT).show()
            }
            actions.onFileHandled()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("canvas_screen")) {
        val wide = maxWidth >= 840.dp
        val density = LocalDensity.current
        val viewportW = with(density) { (if (wide) maxWidth - SIDE_PANEL_WIDTH else maxWidth).toPx() }
        val viewportH = with(density) { maxHeight.toPx() }
        val selected = state.selectedId?.let { state.expressions[it] }
        // a search hit was opened: bring its expression into view once its canvas is showing
        androidx.compose.runtime.LaunchedEffect(state.focusRequest, state.loading) {
            val id = state.focusRequest
            if (id != null && !state.loading) {
                runCatching {
                    val l = ExpressionLayout.all(state.strokes, state.expressions, state.insights.systems, state.graphs, state.insights.graphable, state.graphOffsets, state.graphScales, state.settings.graph.defaultSize)[id]
                    if (l != null) actions.onTransformChanged(state.transform.fitTo(l.hitBounds, viewportW, viewportH))
                }
                actions.onFocusHandled()
            }
        }

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CanvasSurface(state, actions)
                if (state.strokes.isEmpty() && state.sheet == Sheet.NONE && !state.loading) EmptyCanvasGuide(state, actions)
                Column(
                    Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                    horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TopBar(state, actions, Modifier)
                    ModeChip(state, actions)
                    if (state.strokes.isNotEmpty()) Pill(Modifier.testTag("fit_all")) {
                        ContextAction(Glyphs.ZOOM_IN, "Show all", "btn_fit") {
                            // frames everything written (ink, answers, graphs); a failure to lay out must never take the canvas down
                            runCatching {
                                val layouts = ExpressionLayout.all(state.strokes, state.expressions, state.insights.systems, state.graphs, state.insights.graphable, state.graphOffsets, state.graphScales, state.settings.graph.defaultSize)
                                CanvasView.fitAll(state.strokes, layouts, viewportW, viewportH)
                            }.getOrNull()?.let { actions.onTransformChanged(it) }
                        }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.multi.active) MultiBar(state.multi.ids.size, actions)
                    if (selected == null && !state.multi.active) state.graphSuggestion?.let { key ->
                        val label = if (state.insights.systems[key] != null) "Graph these equations" else "Graph " + state.expressions[key]?.recognized.orEmpty().take(24)
                        Pill(Modifier.testTag("graph_suggestion")) { ContextAction(Glyphs.GRAPH, label, "ctx_suggest_graph") { actions.onToggleGraph(key) } }
                    }
                    if (selected != null) {
                        val system = state.insights.systems[selected.id]
                        ContextBar(
                            selected, actions,
                            graphable = selected.id in state.insights.graphable,
                            graphShown = (system?.anchorId ?: selected.id) in state.graphs,
                            systemHasSteps = system?.steps?.isNotEmpty() == true,
                        ) {
                            val l = ExpressionLayout.all(state.strokes, state.expressions)[selected.id]
                            if (l != null) actions.onTransformChanged(state.transform.fitTo(l.hitBounds, viewportW, viewportH))
                        }
                    }
                    if (state.tool == Tool.PEN) PenWidthRow(state, actions)
                    ToolBar(state, actions)
                }
            }
            if (wide) SidePanel(selected, Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight())
        }

        state.correctionFor?.let { id -> state.expressions[id]?.let { CorrectionDialog(it, state.alternatives[id].orEmpty(), actions) } }
        when (state.sheet) {
            Sheet.CANVASES -> CanvasListSheet(state, actions)
            Sheet.SETTINGS -> SettingsSheet(state, actions)
            Sheet.MODES -> ModesSheet(state, actions)
            Sheet.TIPS -> TipsSheet(actions)
            Sheet.NONE -> {}
        }
    }
}

private val SIDE_PANEL_WIDTH = 340.dp

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)), content = content)
}

/** The multi-select mode: how many are selected, select everything, delete them, or leave the mode. Dragging any selected expression moves them all. */
@Composable
private fun MultiBar(count: Int, actions: CanvasActions) {
    Pill(Modifier.testTag("multi_bar")) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", Modifier.padding(horizontal = 12.dp).testTag("multi_count"), style = MaterialTheme.typography.labelLarge)
            ContextAction(Glyphs.SELECT, "All", "multi_all") { actions.onMultiSelectAll() }
            if (count > 0) ContextAction(Glyphs.TRASH, "Delete", "multi_delete", color = MaterialTheme.colorScheme.error) { actions.onDeleteMulti() }
            ContextAction(Glyphs.CHEVRON, "Done", "multi_done") { actions.onMultiStop() }
        }
    }
}

/** Always-visible reminder of the current modes (angle unit, result format); tapping it opens the Modes panel. */
@Composable
private fun ModeChip(state: CanvasUiState, actions: CanvasActions) {
    val label = modeLabel(state.settings.calc)
    Pill(Modifier.clickable { actions.onSheet(Sheet.MODES) }.semantics { contentDescription = "Modes: $label. Tap to change." }.testTag("mode_chip")) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TopBar(state: CanvasUiState, actions: CanvasActions, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var shareMenu by remember { mutableStateOf(false) }
    Pill(modifier) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ actions.onSheet(Sheet.CANVASES) }, Modifier.weight(1f, fill = false).semantics { contentDescription = "Open canvases" }.testTag("btn_canvases")) {
                GlyphIcon(Glyphs.FOLDER_OPEN, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(state.canvas?.name ?: "Infinite Canvas", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                GlyphIcon(Glyphs.CHEVRON, size = 16.dp, rotation = 180f)
            }
            BarButton(Glyphs.PLUS, "New canvas", "btn_new") { actions.onNewCanvas() }
            BarButton(Glyphs.SAVE, "Save", "btn_save") { actions.onSave() }
            Box {
                BarButton(Glyphs.SHARE, "Share", "btn_share") { shareMenu = true }
                DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Image (PNG, transparent)") },
                        onClick = { shareMenu = false; CanvasExporter.share(context, state, measurer, density, ExportFormat.PNG) },
                        modifier = Modifier.testTag("share_png"),
                    )
                    DropdownMenuItem(
                        text = { Text("PDF") },
                        onClick = { shareMenu = false; CanvasExporter.share(context, state, measurer, density, ExportFormat.PDF) },
                        modifier = Modifier.testTag("share_pdf"),
                    )
                }
            }
            BarButton(Glyphs.THEME, "Change theme", "btn_theme") {
                actions.onThemeMode(when (state.settings.themeMode) { ThemeMode.SYSTEM -> ThemeMode.LIGHT; ThemeMode.LIGHT -> ThemeMode.DARK; ThemeMode.DARK -> ThemeMode.SYSTEM })
            }
            BarButton(Glyphs.SETTINGS, "Canvas settings", "btn_settings") { actions.onSheet(Sheet.SETTINGS) }
        }
    }
}

@Composable
private fun BarButton(glyph: Glyph, description: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { contentDescription = description }.testTag(tag)) { GlyphIcon(glyph) }
}

@Composable
private fun ToolBar(state: CanvasUiState, actions: CanvasActions) {
    var confirmClear by remember { mutableStateOf(false) }
    Pill {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolToggle(Glyphs.PENCIL, "Pen", state.tool == Tool.PEN, "tool_pen") { actions.onToolSelected(Tool.PEN) }
            ToolToggle(Glyphs.ERASER, "Eraser", state.tool == Tool.ERASER, "tool_eraser") { actions.onToolSelected(Tool.ERASER) }
            ToolToggle(Glyphs.SELECT, "Select", state.tool == Tool.SELECT, "tool_select") { actions.onToolSelected(Tool.SELECT) }
            ToolToggle(Glyphs.HAND, "Finger navigates", state.inputMode == InputMode.NAVIGATE, "mode_navigate") {
                actions.onInputModeChanged(if (state.inputMode == InputMode.NAVIGATE) InputMode.DRAW else InputMode.NAVIGATE)
            }
            VerticalDivider(Modifier.height(24.dp))
            IconButton(actions.onUndo, Modifier.semantics { contentDescription = "Undo" }.testTag("btn_undo"), enabled = state.canUndo) { GlyphIcon(Glyphs.UNDO) }
            IconButton(actions.onRedo, Modifier.semantics { contentDescription = "Redo" }.testTag("btn_redo"), enabled = state.canRedo) { GlyphIcon(Glyphs.UNDO, mirrored = true) }
            IconButton({ confirmClear = true }, Modifier.semantics { contentDescription = "Clear canvas" }.testTag("btn_clear")) { GlyphIcon(Glyphs.TRASH) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear canvas?") }, text = { Text("Everything on this canvas will be removed. You can undo this.") },
        confirmButton = { TextButton({ confirmClear = false; actions.onClear() }, Modifier.testTag("confirm_clear")) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ToolToggle(glyph: Glyph, description: String, checked: Boolean, tag: String, onClick: () -> Unit) {
    IconToggleButton(checked, { onClick() }, Modifier.semantics { contentDescription = description }.testTag(tag)) {
        GlyphIcon(glyph, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PenWidthRow(state: CanvasUiState, actions: CanvasActions) {
    Pill {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, px) in listOf("Thin" to 2.5f, "Medium" to 4f, "Thick" to 7f)) {
                FilterChip(state.penWidthPx == px, { actions.onPenWidthSelected(px) }, { Text(label) }, Modifier.testTag("width_$label"))
            }
        }
    }
}

@Composable
private fun ContextBar(e: ExpressionObject, actions: CanvasActions, graphable: Boolean, graphShown: Boolean, systemHasSteps: Boolean, onZoom: () -> Unit) {
    Pill(Modifier.testTag("context_bar")) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ContextAction(Glyphs.RECALCULATE, "Recalculate", "ctx_recalculate") { actions.onRecalculate(e.id) }
            if (e.steps.isNotEmpty() || systemHasSteps) ContextAction(if (e.solutionVisible) Glyphs.EYE_OFF else Glyphs.EYE, if (e.solutionVisible) "Hide steps" else "Show steps", "ctx_toggle_steps") { actions.onToggleSolution(e.id) }
            if (graphable) ContextAction(Glyphs.GRAPH, if (graphShown) "Hide graph" else "Graph", "ctx_graph") { actions.onToggleGraph(e.id) }
            ContextAction(Glyphs.EDIT, "Edit", "ctx_edit") { actions.onEditRecognition(e.id) }
            ContextAction(Glyphs.ZOOM_IN, "Zoom", "ctx_zoom", onClick = onZoom)
            ContextAction(Glyphs.SELECT, "Select more", "ctx_multi") { actions.onMultiStart() }
            ContextAction(Glyphs.TRASH, "Delete", "ctx_delete", color = MaterialTheme.colorScheme.error) { actions.onDeleteSelected() }
        }
    }
}

@Composable
private fun ContextAction(glyph: Glyph, label: String, tag: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    TextButton(onClick, Modifier.testTag(tag)) {
        GlyphIcon(glyph, size = 18.dp, tint = color)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color)
    }
}

/** Tablet detail panel: the selected expression with its full step-by-step explanation. */
@Composable
private fun SidePanel(e: ExpressionObject?, modifier: Modifier) {
    Surface(modifier.testTag("side_panel"), tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Expression details", style = MaterialTheme.typography.titleMedium)
            if (e == null) { Text("Select an expression (Select tool) to see how it was read and solved.", style = MaterialTheme.typography.bodyMedium); return@Column }
            Text(e.displayText ?: e.recognized ?: "Not recognised yet", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            e.recognized?.let { Text("Read as: $it  (${(e.confidence * 100).toInt()}% confident)", style = MaterialTheme.typography.bodySmall) }
            e.result?.let { Text("Result: $it", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (e.status == ProcessingStatus.NEEDS_REVIEW) Text("Low confidence — nothing was calculated. Use Edit to correct the text.", color = MaterialTheme.colorScheme.tertiary)
            if (e.steps.isNotEmpty()) {
                HorizontalDivider()
                Text("Steps", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(e.steps) { s ->
                        Column(Modifier.fillMaxWidth().testTag("step_${s.number}")) {
                            Text("${s.number}. ${s.explanation}", style = MaterialTheme.typography.bodySmall)
                            Text("${s.expression}  →  ${s.resulting}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Characters that are awkward to type on a phone keyboard but that the calculator understands. */
private val SYMBOLS = listOf("×", "÷", "^", "√(", "π", "(", ")", "|", "sin(", "cos(", "tan(", "asin(", "log(", "ln(", "=")

@Composable
private fun CorrectionDialog(e: ExpressionObject, alternatives: List<String>, actions: CanvasActions) {
    val start = e.recognized ?: ""
    var field by remember(e.id) { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    var contribute by remember(e.id) { mutableStateOf(false) }
    fun insert(symbol: String) {
        val sel = field.selection
        field = TextFieldValue(field.text.replaceRange(sel.min, sel.max, symbol), TextRange(sel.min + symbol.length))
    }
    AlertDialog(
        onDismissRequest = { actions.onEditRecognition(null) },
        title = { Text("Correct the expression") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alternatives.isNotEmpty()) {
                    Text("Did you mean", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (alt in alternatives) AssistChip({ actions.onApplyCorrection(e.id, alt) }, { Text(alt) }, Modifier.testTag("alt_$alt"))      // one tap: applied
                    }
                }
                Text("Your handwriting stays as it is; this text is used for the calculation.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(field, { field = it }, Modifier.fillMaxWidth().testTag("correction_field"), singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sym in SYMBOLS) AssistChip({ insert(sym) }, { Text(sym) }, Modifier.testTag("sym_$sym"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(contribute, { contribute = it }, Modifier.testTag("correction_contribute"))
                    Text("Save this handwriting as a sample to improve recognition (kept on this device only)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton({ if (contribute) actions.onContributeSample(e.id, field.text); actions.onApplyCorrection(e.id, field.text) }, Modifier.testTag("correction_apply")) { Text("Apply") } },
        dismissButton = { TextButton({ actions.onEditRecognition(null) }) { Text("Cancel") } },
    )
}


/** On an empty canvas: the welcome tips the first time, afterwards just a quiet hint about what to do. */
@Composable
private fun BoxScope.EmptyCanvasGuide(state: CanvasUiState, actions: CanvasActions) {
    if (!state.settings.tipsSeen) {
        Surface(
            Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 360.dp).testTag("welcome_card"),
            shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Welcome", style = MaterialTheme.typography.titleLarge)
                TipsList()
                TextButton({ actions.onTipsSeen(true) }, Modifier.align(Alignment.End).testTag("welcome_done")) { Text("Got it") }
            }
        }
    } else {
        Text(
            "Write a calculation, for example 25+15=", Modifier.align(Alignment.Center).padding(32.dp).testTag("empty_hint"),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}
