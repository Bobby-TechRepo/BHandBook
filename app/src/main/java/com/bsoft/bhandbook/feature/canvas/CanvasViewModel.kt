package com.bsoft.bhandbook.feature.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.recognition.ColumnArithmetic
import com.bsoft.bhandbook.core.recognition.EqualsDetector
import com.bsoft.bhandbook.data.SettingsStore
import com.bsoft.bhandbook.di.DefaultDispatcher
import com.bsoft.bhandbook.domain.editor.CanvasEditor
import com.bsoft.bhandbook.domain.editor.GraphPanels
import com.bsoft.bhandbook.domain.editor.Insights
import com.bsoft.bhandbook.core.codec.CanvasFile
import com.bsoft.bhandbook.core.codec.CanvasFileCodec
import com.bsoft.bhandbook.core.codec.CanvasFileResult
import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.domain.editor.CanvasExport
import com.bsoft.bhandbook.domain.model.GraphPanelState
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.repository.CanvasRepository
import com.bsoft.bhandbook.domain.repository.CanvasViewSettings
import com.bsoft.bhandbook.domain.repository.SampleRepository
import com.bsoft.bhandbook.domain.usecase.ProcessExpressionUseCase
import com.bsoft.bhandbook.domain.usecase.ProcessingOutcome
import com.bsoft.bhandbook.domain.usecase.ProcessingSettings
import com.bsoft.bhandbook.domain.usecase.applyTo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Wires the pure [CanvasEditor] to persistence and background recognition.
 * Rules: every editor call happens on the main thread; recognition/evaluation runs on [cpu];
 * a result is applied only if the ink it was computed from is still current (see CanvasEditor.applyRecognition).
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    private val canvases: CanvasRepository,
    private val settingsStore: SettingsStore,
    private val process: ProcessExpressionUseCase,
    private val insights: com.bsoft.bhandbook.domain.editor.CanvasInsights,
    private val graphPanels: com.bsoft.bhandbook.domain.repository.GraphPanelRepository,
    private val samples: SampleRepository,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : ViewModel() {

    private val editor = CanvasEditor(idGenerator = { UUID.randomUUID().toString() })
    private val _ui = MutableStateFlow(CanvasUiState(settings = settingsStore.settings.value))
    val ui: StateFlow<CanvasUiState> = _ui.asStateFlow()

    private val recognitionJobs = HashMap<String, Job>()
    private val runningSignature = HashMap<String, Int>()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { s -> _ui.update { it.copy(settings = s) } }
        }
        viewModelScope.launch { openInitialCanvas() }
    }

    // ----------------------------------------------------------------- canvas management
    private suspend fun openInitialCanvas() {
        val id = canvases.mostRecentId()
        if (id != null && loadCanvas(id)) return
        createCanvas()
    }

    private suspend fun loadCanvas(id: String): Boolean {
        val doc = canvases.load(id) ?: return false
        cancelAllWork()
        editor.load(doc.state)
        val savedPanels = graphPanels.load(id)                       // graph panels (shown, placed, sized) come back with the canvas
        runCatching { reevaluateExpressions() }.onFailure { android.util.Log.e("CanvasViewModel", "re-evaluation failed", it) }          // saved answers always match the modes in force
        runCatching { editor.splitMergedLines() }.onFailure { android.util.Log.e("CanvasViewModel", "line splitting failed", it) }          // repairs canvases saved while lines were still merged
        _ui.update {
            it.copy(loading = false, canvas = doc.info, strokes = editor.state.strokes, expressions = editor.state.expressions,
                transform = CanvasTransform(doc.view.scale, Vec2(doc.view.offsetX, doc.view.offsetY)), background = doc.view.background,
                canUndo = false, canRedo = false, selectedId = null, correctionFor = null, sheet = Sheet.NONE,
                graphs = savedPanels.visible, graphOffsets = savedPanels.offsets, graphScales = savedPanels.scales, graphViews = savedPanels.views, graphTrace = emptyMap(), multi = com.bsoft.bhandbook.domain.editor.MultiSelection(), focusRequest = null, graphSuggestion = null)
        }
        refreshInsights()
        refreshCanvasList()
        scheduleRecognition()      // anything that was pending when the app closed; cached results are instant
        return true
    }

    private suspend fun createCanvas() {
        val n = canvases.listCanvases().size + 1
        val info = canvases.create("Canvas $n", settingsStore.settings.value.defaultBackground, System.currentTimeMillis())
        loadCanvas(info.id)
    }

    private fun notice(text: String) { _ui.update { it.copy(notice = text) } }
    fun onNoticeShown() { _ui.update { it.copy(notice = null) } }
    fun onFileHandled() { _ui.update { it.copy(pendingFile = null) } }

    /** Builds a backup file of canvas [id] (off the main thread) and hands it to the screen to share. */
    fun onExportCanvas(id: String) {
        viewModelScope.launch {
            if (_ui.value.canvas?.id == id) flushNow()                                       // the file must contain what is on screen
            val doc = canvases.load(id)
            if (doc == null) { notice("That canvas could not be read."); return@launch }
            val file = CanvasFile(doc.info.name, doc.view.background, doc.view.scale, doc.view.offsetX, doc.view.offsetY, doc.state, graphPanels.load(id))
            val text = try { withContext(cpu) { CanvasFileCodec.encode(file) } } catch (e: Exception) { notice("The backup file could not be created."); return@launch }
            _ui.update { it.copy(pendingFile = PendingFile(CanvasExport.fileName(doc.info.name, "bhand"), text)) }
        }
    }

    /** Adds the canvas in a backup file as a NEW canvas (it can never overwrite one) and opens it. */
    fun onImportCanvas(text: String?) {
        viewModelScope.launch {
            if (text == null) { notice("That file could not be read, or it is too large."); return@launch }
            when (val r = withContext(cpu) { CanvasFileCodec.decode(text) }) {
                is CanvasFileResult.Failed -> notice(r.reason)
                is CanvasFileResult.Ok -> {
                    flushNow()
                    val fresh = withContext(cpu) { CanvasFileCodec.withFreshIds(r.file) { UUID.randomUUID().toString() } }
                    val now = System.currentTimeMillis()
                    val info = canvases.create(CanvasExport.uniqueName(fresh.name, canvases.listCanvases().map { it.name }), fresh.background, now)
                    canvases.save(info.id, CanvasViewSettings(fresh.background, fresh.scale, fresh.offsetX, fresh.offsetY), fresh.state, now)
                    graphPanels.save(info.id, fresh.graphs)
                    loadCanvas(info.id)
                    notice("Imported \u201c${info.name}\u201d" + if (r.skipped > 0) " (${r.skipped} damaged items were skipped)" else "")
                }
            }
        }
    }

    private suspend fun refreshCanvasList() { val l = canvases.listCanvases(); _ui.update { it.copy(canvases = l) } }

    fun onNewCanvas() { viewModelScope.launch { flushNow(); createCanvas() } }
    fun onOpenCanvas(id: String) { viewModelScope.launch { flushNow(); loadCanvas(id) } }
    fun onRenameCanvas(id: String, name: String) { viewModelScope.launch { canvases.rename(id, name); refreshCanvasList(); _ui.update { s -> s.copy(canvas = s.canvas?.takeIf { it.id == id }?.copy(name = name) ?: s.canvas) } } }
    fun onDeleteCanvas(id: String) {
        viewModelScope.launch {
            val deletingCurrent = _ui.value.canvas?.id == id
            if (deletingCurrent) { cancelAllWork(); saveJob?.cancel() }
            canvases.delete(id)
            graphPanels.delete(id)
            if (deletingCurrent) openInitialCanvas() else refreshCanvasList()
        }
    }

    // ----------------------------------------------------------------- drawing
    fun onStrokeFinished(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val pts = if (points.size == 1) points + points[0].copy(t = points[0].t + 1) else points      // a tap becomes a dot
        val scale = _ui.value.transform.scale
        editor.addStroke(Stroke(UUID.randomUUID().toString(), pts, _ui.value.penWidthPx / scale, ADAPTIVE_INK))
        if (!_ui.value.settings.tipsSeen) onTipsSeen(true)                       // the user has started: the welcome tips have done their job
        publish()
    }

    fun onEraseStart() = editor.beginErase()
    fun onEraseMove(world: Vec2, radiusPx: Float) { editor.eraseAt(world, radiusPx / _ui.value.transform.scale); publish(schedule = false) }
    fun onEraseEnd() { editor.endErase(); publish() }

    fun onUndo() { editor.undo(); publish() }
    fun onRedo() { editor.redo(); publish() }
    fun onClear() { editor.clearAll(); publish() }

    fun onTransformChanged(t: CanvasTransform) { _ui.update { it.copy(transform = t) }; scheduleSave() }
    fun onToolSelected(t: Tool) = _ui.update { it.copy(tool = t, selectedId = if (t == Tool.SELECT) it.selectedId else null, multi = if (t == Tool.SELECT) it.multi else it.multi.stop()) }
    fun onInputModeChanged(m: InputMode) = _ui.update { it.copy(inputMode = m) }
    fun onPenWidthSelected(px: Float) = _ui.update { it.copy(penWidthPx = px) }

    // ----------------------------------------------------------------- expressions
    fun onSelect(id: String?) = _ui.update { if (it.multi.active) it.copy(multi = it.multi.tap(id)) else it.copy(selectedId = id) }          // in the multi-select mode a tap adds or removes
    fun onMoveExpression(id: String, dx: Float, dy: Float) { editor.moveExpression(id, dx, dy); publish(schedule = false) }
    fun onMultiStart() = _ui.update { it.copy(multi = com.bsoft.bhandbook.domain.editor.MultiSelection().start(it.selectedId), selectedId = null) }
    fun onMultiStop() = _ui.update { it.copy(multi = it.multi.stop()) }
    fun onMultiSelectAll() = _ui.update { it.copy(multi = it.multi.selectAll(editor.state.expressions.keys)) }
    /** Resizes expressions about ([ax], [ay]) as one undo step; they keep their answers (the expression is not read again). */
    fun onResizeExpressions(ids: Set<String>, factor: Float, ax: Float, ay: Float) { editor.scaleExpressions(ids, factor, ax, ay); publish(schedule = false) }

    /** Moves the selected expressions together (one undo step). */
    fun onMoveExpressions(ids: Set<String>, dx: Float, dy: Float) { editor.moveExpressions(ids, dx, dy); publish(schedule = false) }
    /** Deletes every selected expression (one undo step) and stays in the mode with an empty selection. */
    fun onDeleteMulti() { val ids = _ui.value.multi.ids; if (ids.isEmpty()) return; editor.deleteExpressions(ids); publish() }

    // ----------------------------------------------------------------- search across canvases
    private var searchJob: Job? = null

    /** Searches every canvas (names, expression text and answers); the one on screen is searched as it is now, saved or not. */
    fun onSearch(query: String) {
        searchJob?.cancel()
        _ui.update { it.copy(search = SearchState(query, if (query.isBlank()) emptyList() else it.search.hits, searching = query.isNotBlank())) }
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DELAY_MS)
            val hits = ArrayList<com.bsoft.bhandbook.domain.editor.SearchHit>()
            val current = _ui.value.canvas?.id
            try {
                for (info in canvases.listCanvases()) {
                    if (hits.size >= MAX_SEARCH_HITS) break
                    val state = if (info.id == current) editor.state else canvases.load(info.id)?.state ?: continue
                    hits += withContext(cpu) { com.bsoft.bhandbook.domain.editor.CanvasSearch.inCanvas(info.id, info.name, state, query, MAX_HITS_PER_CANVAS) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.e("CanvasViewModel", "search failed", e) }
            _ui.update { it.copy(search = SearchState(query, hits.take(MAX_SEARCH_HITS), searching = false)) }
        }
    }

    /** Opens the canvas of a search hit (if it is another one) and asks the screen to bring the expression into view. */
    fun onOpenHit(hit: com.bsoft.bhandbook.domain.editor.SearchHit) {
        viewModelScope.launch {
            if (_ui.value.canvas?.id != hit.canvasId) { flushNow(); if (!loadCanvas(hit.canvasId)) { notice("That canvas could not be opened."); return@launch } }
            _ui.update { it.copy(sheet = Sheet.NONE, tool = if (hit.expressionId != null) Tool.SELECT else it.tool, selectedId = hit.expressionId?.takeIf { id -> id in editor.state.expressions }, multi = it.multi.stop(), focusRequest = hit.expressionId) }
        }
    }
    fun onFocusHandled() { _ui.update { it.copy(focusRequest = null) } }

    fun onDeleteSelected() { _ui.value.selectedId?.let { editor.deleteExpression(it); publish() } }
    fun onRecalculate(id: String) { editor.invalidateExpression(id); publish() }
    fun onToggleSolution(id: String) { editor.updateExpression(id) { it.copy(solutionVisible = !it.solutionVisible) }; publish(schedule = false) }
    fun onEditRecognition(id: String?) = _ui.update { it.copy(correctionFor = id) }

    /** The user's corrected text is trusted over the ink and goes through the same parse/evaluate path. */
    fun onApplyCorrection(id: String, text: String) {
        val sig = editor.signatureOf(id)
        val outcome = process.fromText(text.trim(), 1f, processingSettings())
        editor.applyRecognition(id, sig) { outcome.applyTo(it, System.currentTimeMillis()) }
        _ui.update { it.copy(correctionFor = null, alternatives = it.alternatives - id) }          // the user has decided: the old suggestions no longer apply
        publish(schedule = false)
    }

    /** Opt-in: stores this expression's ink with the user's [label] (and what the engine read) for offline accuracy measurement. */
    fun onContributeSample(id: String, label: String) {
        val strokes = editor.state.strokesOf(id)
        if (strokes.isEmpty() || label.isBlank()) return
        val engineReading = editor.state.expressions[id]?.recognized
        viewModelScope.launch { samples.save(InkSample(label.trim(), engineReading, strokes, System.currentTimeMillis())) }
    }

    // ----------------------------------------------------------------- settings
    fun onSheet(s: Sheet) = _ui.update { it.copy(sheet = s) }
    fun onBackground(b: CanvasBackground) { _ui.update { it.copy(background = b) }; scheduleSave() }
    /** Keeps the "did you mean" readings of [id] (in memory only; they are recomputed whenever the ink is read again). */
    private fun rememberAlternatives(id: String, alternatives: List<String>) {
        _ui.update { it.copy(alternatives = if (alternatives.isEmpty()) it.alternatives - id else it.alternatives + (id to alternatives)) }
    }

    fun onTipsSeen(seen: Boolean) { settingsStore.update { it.copy(tipsSeen = seen) }; _ui.update { it.copy(settings = it.settings.copy(tipsSeen = seen), sheet = if (!seen) Sheet.TIPS else it.sheet) } }

    /** What the maths needs to know right now: whether to evaluate automatically, and the modes (angle unit, result format). */
    private fun processingSettings(): ProcessingSettings {
        val calc = _ui.value.settings.calc
        return ProcessingSettings(_ui.value.settings.autoEvaluate, calc, insights.definitionsOf(editor.state, calc))         // main thread only: the insights cache is not thread-safe
    }

    /** The definitions the expressions on screen were last evaluated with; when this changes, the expressions that use names are evaluated again. */
    private var lastDefinitionsSignature = ""

    /**
     * Changes the modes. Everything already on the canvas is evaluated again from its recognised text (nothing is re-read from the ink), so the
     * answers, steps, systems and graphs all follow the new mode at once.
     */
    fun onCalcSettings(change: (CalcSettings) -> CalcSettings) {
        val updated = change(_ui.value.settings.calc).normalized()
        if (updated == _ui.value.settings.calc) return
        settingsStore.update { it.copy(calc = updated) }
        _ui.update { it.copy(settings = it.settings.copy(calc = updated)) }           // immediately, not when the settings flow next emits
        reevaluateAll()
    }

    private fun reevaluateAll() { reevaluateExpressions(); publish(schedule = false) }

    /** Evaluates every settled expression again from its recognised text, in the current modes (no ink is re-read). */
    /** Returns whether anything was evaluated again. [onlyNameUsers]: only expressions that mention a name (after a definition changed). */
    private fun reevaluateExpressions(onlyNameUsers: Boolean = false): Boolean {
        val settings = processingSettings(); val now = System.currentTimeMillis()
        lastDefinitionsSignature = settings.definitions.signature()
        val settled = setOf(ProcessingStatus.EVALUATED, ProcessingStatus.ERROR, ProcessingStatus.RECOGNIZED)
        var changed = false
        for (e in editor.state.expressions.values.toList()) {
            val text = e.recognized ?: continue
            if (e.status !in settled) continue
            if (onlyNameUsers && !insights.usesNames(text)) continue
            val outcome = runCatching { process.reevaluate(text, e.confidence, e.resultInline, settings) }.getOrNull() ?: continue
            if (editor.applyRecognition(e.id, editor.signatureOf(e.id)) { outcome.applyTo(it, now) }) changed = true
        }
        return changed
    }

    fun onAutoEvaluate(v: Boolean) { settingsStore.update { it.copy(autoEvaluate = v) } }
    fun onThemeMode(m: ThemeMode) { settingsStore.update { it.copy(themeMode = m) } }
    fun onIdleDelay(ms: Long) { settingsStore.update { it.copy(idleDelayMs = ms) } }

    // ----------------------------------------------------------------- recognition scheduling
    private fun scheduleRecognition() {
        // lines of writing that grew together are separated first, so each line is read (and solved) on its own
        if (runCatching { editor.splitMergedLines(editor.expressionsNeedingRecognition()) }.onFailure { android.util.Log.e("CanvasViewModel", "line splitting failed", it) }.getOrDefault(false)) {
            val st = editor.state
            _ui.update { it.copy(strokes = st.strokes, expressions = st.expressions, canUndo = editor.canUndo, canRedo = editor.canRedo, selectedId = it.selectedId?.takeIf { id -> id in st.expressions }) }
        }
        editor.expressionsNeedingRecognition().forEach(::scheduleRecognition)
    }

    private fun scheduleRecognition(id: String) {
        val sig = editor.signatureOf(id)
        if (runningSignature[id] == sig) return
        recognitionJobs.remove(id)?.cancel()                          // outdated request for older ink
        runningSignature[id] = sig
        val strokes = editor.state.strokesOf(id)
        val settings = _ui.value.settings
        recognitionJobs[id] = viewModelScope.launch {
            try {
                val immediate = try { withContext(cpu) { EqualsDetector.endsWithEquals(strokes) || ColumnArithmetic.detect(strokes) != null } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                delay(if (immediate) 0L else settings.idleDelayMs)      // writing '=' asks for the result right now
                if (editor.markRecognizing(id, sig)) publish(schedule = false)   // progress feedback; ignored if the ink changed meanwhile
                // A cancelled request (the user kept drawing) is a normal event; a failing recogniser must never take the app down.
                val outcome = try {
                    val current = processingSettings()                                                     // the latest modes and definitions, not the ones from when this was scheduled
                    withContext(cpu) { process(strokes, current) { !isActive } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed("Recognition failed: ${e.message ?: e.javaClass.simpleName}")
                } catch (e: OutOfMemoryError) {
                    failed("Recognition ran out of memory")
                }
                val now = System.currentTimeMillis()
                if (editor.applyRecognition(id, sig) { outcome.applyTo(it, now) }) { rememberAlternatives(id, outcome.alternatives); publish(schedule = false); suggestGraphFor(id) }
            } finally {
                if (runningSignature[id] == sig) runningSignature.remove(id)
            }
        }
    }

    private fun failed(message: String) = ProcessingOutcome.Failed("", 0f, "", message)

    private fun cancelAllWork() { recognitionJobs.values.forEach { it.cancel() }; recognitionJobs.clear(); runningSignature.clear() }

    // ----------------------------------------------------------------- state publication & autosave
    private fun publish(schedule: Boolean = true) {
        pushState()
        // A value or function was written, changed or erased: everything that uses names gets its answer again, so no answer is ever stale
        if (_ui.value.insights.definitions.signature() != lastDefinitionsSignature && reevaluateExpressions(onlyNameUsers = true)) pushState()
        if (schedule) scheduleRecognition()
        scheduleSave()
    }

    private val graphCache = HashMap<String, Pair<String, com.bsoft.bhandbook.domain.graph.GraphData?>>()

    private fun pushState() {
        val st = editor.state
        _ui.update {
            it.copy(strokes = st.strokes, expressions = st.expressions, canUndo = editor.canUndo, canRedo = editor.canRedo,
                selectedId = it.selectedId?.takeIf { id -> id in st.expressions }, multi = it.multi.prune(st.expressions.keys))
        }
        refreshInsights()
    }

    /** Simultaneous equations, graphable expressions and the data of every visible graph; derived from the canvas, cheap enough to redo on every change. */
    private fun refreshInsights() {
        try { computeInsights() } catch (e: Exception) {
            // derived extras (systems, graphs) must never take the canvas down: log, and show the canvas without them
            android.util.Log.e("CanvasViewModel", "could not analyse the canvas", e)
            _ui.update { it.copy(insights = Insights(emptyMap(), emptySet()), graphs = emptySet(), graphData = emptyMap(), graphSuggestion = null) }
        }
    }

    private fun computeInsights() {
        val st = editor.state
        val calc = _ui.value.settings.calc
        val ins = insights.analyse(st, calc)
        val before = GraphPanelState(_ui.value.graphs, _ui.value.graphOffsets, _ui.value.graphScales, _ui.value.graphViews)
        val panels = GraphPanels.reconcile(before, st, ins)           // forget deleted expressions; keep a graph's place while its equation is being re-read
        val keys = panels.visible
        // graph data is only rebuilt when the equations it is made from change: tracing a curve is far more work than drawing a line
        val rebuilt = HashSet<String>()
        val data = keys.mapNotNull { k ->
            val view = panels.views[k]
            val signature = graphSignature(k, st, ins, calc, view)
            val hit = graphCache[k]?.takeIf { it.first == signature }
            val d = if (hit != null) hit.second else { rebuilt += k; insights.graphFor(k, st, ins, calc, view).also { graphCache[k] = signature to it } }
            d?.let { k to it }
        }.toMap()
        graphCache.keys.retainAll(keys)
        if (panels != before) saveGraphPanels(panels)
        _ui.update { it.copy(insights = ins, graphs = keys, graphData = data, graphOffsets = panels.offsets, graphScales = panels.scales, graphViews = panels.views, graphTrace = it.graphTrace.filterKeys { k -> k in keys && k !in rebuilt }, graphSuggestion = it.graphSuggestion?.takeIf { s -> s in st.expressions && s in ins.graphable && s !in keys }, alternatives = it.alternatives.filterKeys { k -> k in st.expressions }) }
    }

    /** Moves a graph panel by ([dx], [dy]) world units from where it is now; the position is remembered with the canvas. */
    fun onMoveGraph(key: String, dx: Float, dy: Float) {
        if (key !in _ui.value.graphs) return
        _ui.update { it.copy(graphOffsets = it.graphOffsets + (key to ((it.graphOffsets[key] ?: Vec2.Zero) + Vec2(dx, dy)))) }
        saveGraphPanels()
    }

    /** Writes the graph panels of the open canvas (small, and only when something changed). */
    /** What a graph's data depends on: the equations, the angle unit, the definitions they use, and the chosen view. */
    private fun graphSignature(key: String, st: com.bsoft.bhandbook.domain.model.CanvasState, ins: Insights, calc: CalcSettings, view: com.bsoft.bhandbook.domain.graph.GraphViewport?) =
        calc.angle.name + "#" + (ins.systems[key]?.memberIds ?: listOf(key)).joinToString("|") { st.expressions[it]?.recognized.orEmpty() } + "#" + ins.definitions.signature() + "@" + (view?.let { "${it.xMin},${it.xMax},${it.yMin},${it.yMax}" } ?: "auto")

    /**
     * Shows another part of a graph (pan / zoom inside its panel), or with null frames it automatically again. Only this graph is rebuilt, and the
     * view is remembered with the canvas.
     */
    fun onGraphView(key: String, view: com.bsoft.bhandbook.domain.graph.GraphViewport?) {
        if (key !in _ui.value.graphs) return
        val v = view?.takeIf { it.isValid }
        _ui.update { it.copy(graphViews = if (v == null) it.graphViews - key else it.graphViews + (key to v)) }
        try {
            val st = editor.state; val calc = _ui.value.settings.calc; val ins = _ui.value.insights
            val d = insights.graphFor(key, st, ins, calc, v)
            graphCache[key] = graphSignature(key, st, ins, calc, v) to d
            _ui.update { it.copy(graphData = if (d == null) it.graphData - key else it.graphData + (key to d)) }
        } catch (e: Exception) { android.util.Log.e("CanvasViewModel", "could not redraw the graph", e) }
        saveGraphPanels()
    }

    /** Traces the point of graph [key] nearest to the tap; tapping the traced point again (or a spot with no curve) clears it. */
    fun onTraceGraph(key: String, gx: Double, gy: Double, tolX: Double, tolY: Double) {
        val data = _ui.value.graphData[key] ?: return
        val current = _ui.value.graphTrace[key]
        val found = com.bsoft.bhandbook.domain.graph.GraphTrace.at(data, gx, gy, tolX, tolY)
        val next = if (found == null || (current != null && com.bsoft.bhandbook.domain.graph.GraphTrace.same(current, found, tolX, tolY))) null else found
        _ui.update { it.copy(graphTrace = if (next == null) it.graphTrace - key else it.graphTrace + (key to next)) }
    }

    private fun saveGraphPanels(state: GraphPanelState = GraphPanelState(_ui.value.graphs, _ui.value.graphOffsets, _ui.value.graphScales, _ui.value.graphViews)) {
        val id = _ui.value.canvas?.id ?: return
        graphPanels.save(id, state)
    }

    /** Enlarges or shrinks one graph panel by [factor] (the corner grip reports how far it was dragged); other graphs are not affected. */
    fun onResizeGraph(key: String, factor: Float) {
        if (key !in _ui.value.graphs || factor.isNaN() || factor <= 0f) return
        val current = _ui.value.graphScales[key] ?: _ui.value.settings.graph.defaultSize
        _ui.update { it.copy(graphScales = it.graphScales + (key to com.bsoft.bhandbook.domain.editor.ExpressionLayout.clampGraphScale(current * factor))) }
        saveGraphPanels()
    }

    fun onGraphSettings(change: (com.bsoft.bhandbook.domain.model.GraphSettings) -> com.bsoft.bhandbook.domain.model.GraphSettings) { settingsStore.update { it.copy(graph = change(it.graph).normalized()) } }

    /** Offers a Graph button for an expression that has just been read and can be plotted (the select tool is not needed). */
    private fun suggestGraphFor(id: String) {
        val ins = _ui.value.insights
        val key = insights.graphKey(id, ins)
        if (key in ins.graphable && key !in _ui.value.graphs) _ui.update { it.copy(graphSuggestion = key) }
    }

    /** Shows or hides the graph of an expression; for simultaneous equations the graph belongs to the lowest equation. */
    fun onToggleGraph(id: String) {
        val key = insights.graphKey(id, _ui.value.insights)
        if (key !in _ui.value.insights.graphable && key !in _ui.value.graphs) return
        _ui.update { it.copy(graphs = if (key in it.graphs) it.graphs - key else it.graphs + key) }
        refreshInsights()
        saveGraphPanels()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(AUTOSAVE_DELAY_MS); flushNow() }
    }

    private suspend fun flushNow() {
        val s = _ui.value
        val info = s.canvas ?: return
        val t = s.transform
        canvases.save(info.id, CanvasViewSettings(s.background, t.scale, t.offset.x, t.offset.y), editor.state, System.currentTimeMillis())
    }

    /** Called when the app goes to the background so nothing is lost. */
    fun flush() { saveJob?.cancel(); viewModelScope.launch { flushNow() } }

    companion object {
        const val SEARCH_DELAY_MS = 250L
        private const val MAX_SEARCH_HITS = 100
        private const val MAX_HITS_PER_CANVAS = 20
        private const val AUTOSAVE_DELAY_MS = 1_200L
        /** Stroke colour 0 means "theme ink": black on light backgrounds, white on dark. */
        const val ADAPTIVE_INK = 0L
    }
}
