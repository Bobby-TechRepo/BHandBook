package com.bsoft.bhandbook.feature.canvas

import app.cash.turbine.test
import com.bsoft.bhandbook.core.math.DefaultMathParser
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.core.recognition.CachingRecognizer
import com.bsoft.bhandbook.core.recognition.HandwritingMathRecognizer
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.core.recognition.RecognitionCache
import com.bsoft.bhandbook.data.SettingsStore
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.repository.*
import com.bsoft.bhandbook.domain.solver.DefaultSolutionEngine
import com.bsoft.bhandbook.domain.usecase.ProcessExpressionUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** In-memory repository so the ViewModel can be tested without Room. */
class FakeCanvasRepository : CanvasRepository {
    val docs = linkedMapOf<String, CanvasDocument>()
    var saveCount = 0
    private var nextId = 0
    var lastSaved: CanvasState? = null
    override suspend fun listCanvases() = docs.values.map { it.info }.sortedByDescending { it.updatedAt }
    override suspend fun create(name: String, background: CanvasBackground, now: Long): CanvasInfo {
        val info = CanvasInfo("id${++nextId}", name, now, now)
        docs[info.id] = CanvasDocument(info, CanvasViewSettings(background), CanvasState()); return info
    }
    override suspend fun rename(id: String, name: String) { docs[id]?.let { docs[id] = it.copy(info = it.info.copy(name = name)) } }
    override suspend fun delete(id: String) { docs.remove(id) }
    override suspend fun load(id: String) = docs[id]
    override suspend fun save(id: String, view: CanvasViewSettings, state: CanvasState, now: Long) {
        saveCount++; lastSaved = state; docs[id]?.let { docs[id] = it.copy(view = view, state = state, info = it.info.copy(updatedAt = now)) }
    }
    override suspend fun mostRecentId() = listCanvases().firstOrNull()?.id
}

class FakeSampleRepository : SampleRepository {
    val saved = mutableListOf<InkSample>()
    override suspend fun save(sample: InkSample): String? { saved += sample; return "fake" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeCanvasRepository
    private lateinit var settings: SettingsStore
    private val sampleRepo = FakeSampleRepository()
    private val settingsFlow = MutableStateFlow(AppSettings(idleDelayMs = 700L))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeCanvasRepository()
        settings = mockk(relaxed = true) { every { this@mockk.settings } returns settingsFlow }
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val panels = FakeGraphPanels()
    private fun vm(engine: com.bsoft.bhandbook.core.recognition.MathRecognitionEngine = CachingRecognizer(HandwritingMathRecognizer(), RecognitionCache(32))) = CanvasViewModel(
        repo, settings,
        ProcessExpressionUseCase(engine, DefaultMathParser(), ExactCalculationEngine(), DefaultSolutionEngine()),
        com.bsoft.bhandbook.domain.editor.CanvasInsights(DefaultMathParser(), DefaultSolutionEngine()),
        panels,
        sampleRepo,
        dispatcher,
    )
    private fun throwing(t: Throwable) = object : com.bsoft.bhandbook.core.recognition.MathRecognitionEngine {
        override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean): com.bsoft.bhandbook.core.recognition.RecognitionResult = throw t
    }

    private fun CanvasViewModel.write(strokes: List<Stroke>) = strokes.forEach { onStrokeFinished(it.points) }
    private fun clean(text: String, seed: Long = 3) = Pen(seed, sloppiness = 0.5f).text(text).strokes

    @Test fun firstLaunch_createsAnEmptyCanvas() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        val s = vm.ui.value
        assertFalse(s.loading); assertNotNull(s.canvas); assertTrue(s.strokes.isEmpty()); assertEquals(1, repo.docs.size)
    }

    @Test fun reopening_restoresTheMostRecentCanvas() = runTest(dispatcher) {
        val info = repo.create("Saved", CanvasBackground.DARK, 5L)
        val stroke = Stroke("s1", listOf(StrokePoint(0f, 0f), StrokePoint(5f, 5f)), 3f, 0)
        repo.docs[info.id] = repo.docs.getValue(info.id).copy(state = CanvasState(listOf(stroke)), view = CanvasViewSettings(CanvasBackground.DARK, 2f, 30f, 40f))
        val vm = vm(); runCurrent()
        assertEquals("Saved", vm.ui.value.canvas!!.name); assertEquals(1, vm.ui.value.strokes.size)
        assertEquals(2f, vm.ui.value.transform.scale, 0f); assertEquals(CanvasBackground.DARK, vm.ui.value.background)
    }

    @Test fun finishingAStroke_addsInk_andEnablesUndo() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("7").take(1))
        assertEquals(1, vm.ui.value.strokes.size); assertEquals(1, vm.ui.value.expressions.size); assertTrue(vm.ui.value.canUndo)
        vm.onUndo(); assertTrue(vm.ui.value.strokes.isEmpty()); assertTrue(vm.ui.value.canRedo)
        vm.onRedo(); assertEquals(1, vm.ui.value.strokes.size)
    }

    @Test fun strokeWidth_isStoredInWorldUnits_soZoomedInWritingStaysProportional() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.onTransformChanged(vm.ui.value.transform.zoomAround(com.bsoft.bhandbook.core.graphics.Vec2.Zero, 4f))
        vm.onPenWidthSelected(4f); vm.write(clean("7").take(1))
        assertEquals(1f, vm.ui.value.strokes.single().width, 1e-4f)
    }

    @Test fun aTap_becomesADot() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.onStrokeFinished(listOf(StrokePoint(10f, 10f, 0L)))
        assertEquals(2, vm.ui.value.strokes.single().points.size)
    }

    @Test fun recognition_waitsForTheIdleDelay_thenShowsTheResult() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("25+15"))
        advanceTimeBy(600); runCurrent()
        assertNull("no recognition while the user may still be writing", vm.ui.value.expressions.values.single().result)
        advanceTimeBy(300); runCurrent()
        assertEquals("40", vm.ui.value.expressions.values.single().result)
    }

    @Test fun drawingEquals_triggersImmediateEvaluation() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("25+15="))
        advanceTimeBy(50); runCurrent()
        val e = vm.ui.value.expressions.values.single()
        assertEquals("40", e.result); assertTrue(e.resultInline)
    }

    @Test fun writingMore_discardsTheOutdatedRequest() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        val first = clean("25+1"); vm.write(first)
        advanceTimeBy(400)
        val x = first.maxOf { it.bounds.right }
        vm.write(Pen(3, sloppiness = 0.5f).at(x + 15f, 300f).text("5").strokes)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("40", vm.ui.value.expressions.values.single().result)       // 25+15, never the stale 26
    }

    @Test fun autoEvaluateOff_recognisesButDoesNotCompute() = runTest(dispatcher) {
        settingsFlow.value = AppSettings(autoEvaluate = false, idleDelayMs = 300L)
        val vm = vm(); runCurrent()
        vm.write(clean("25+15")); advanceTimeBy(1_000); runCurrent()
        val e = vm.ui.value.expressions.values.single()
        assertNull(e.result); assertEquals(ProcessingStatus.RECOGNIZED, e.status)
    }

    @Test fun equationIsSolved_withSteps() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("2x+5=15")); advanceTimeBy(1_500); runCurrent()
        val e = vm.ui.value.expressions.values.single()
        assertEquals("x = 5", e.result); assertEquals(4, e.steps.size)
    }

    @Test fun correctingTheText_overridesRecognition_andKeepsTheInk() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("25+15=")); advanceTimeBy(100); runCurrent()
        val id = vm.ui.value.expressions.keys.single(); val inkBefore = vm.ui.value.strokes
        vm.onApplyCorrection(id, "25+16=")
        assertEquals("41", vm.ui.value.expressions.getValue(id).result)
        assertEquals(inkBefore, vm.ui.value.strokes)
    }

    @Test fun aCancelledRecognition_neverCrashesTheApp() = runTest(dispatcher) {
        // regression: RecognitionCancelled used to be an ordinary exception and escaped viewModelScope.launch
        val vm = vm(throwing(com.bsoft.bhandbook.core.recognition.RecognitionCancelled())); runCurrent()
        vm.write(clean("25+15")); advanceTimeBy(2_000); runCurrent()
        val e = vm.ui.value.expressions.values.single()
        assertNull(e.result); assertNotEquals(ProcessingStatus.EVALUATED, e.status)
    }

    @Test fun aFailingRecogniser_showsAnErrorOnTheExpression_insteadOfCrashing() = runTest(dispatcher) {
        val vm = vm(throwing(IllegalStateException("onnx exploded"))); runCurrent()
        vm.write(clean("25+15")); advanceTimeBy(2_000); runCurrent()
        val e = vm.ui.value.expressions.values.single()
        assertEquals(ProcessingStatus.ERROR, e.status); assertTrue(e.error!!.contains("onnx exploded"))
        assertTrue("the user's handwriting is untouched", vm.ui.value.strokes.isNotEmpty())
    }

    @Test fun anOutOfMemoryRecogniser_showsAnError() = runTest(dispatcher) {
        val vm = vm(throwing(OutOfMemoryError())); runCurrent()
        vm.write(clean("25+15")); advanceTimeBy(2_000); runCurrent()
        assertEquals(ProcessingStatus.ERROR, vm.ui.value.expressions.values.single().status)
    }

    @Test fun contributingASample_savesInkWithLabelAndEngineReading() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        val ink = clean("25+15="); vm.write(ink); advanceTimeBy(100); runCurrent()
        val id = vm.ui.value.expressions.keys.single()
        vm.onContributeSample(id, " 25+15= "); runCurrent()
        val s = sampleRepo.saved.single()
        assertEquals("25+15=", s.label); assertEquals("25+15=", s.recognizedAs); assertEquals(ink.size, s.strokes.size)
    }

    @Test fun contributingWithBlankLabelOrNoInk_savesNothing() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("12")); val id = vm.ui.value.expressions.keys.single()
        vm.onContributeSample(id, "   "); vm.onContributeSample("missing", "12"); runCurrent()
        assertTrue(sampleRepo.saved.isEmpty())
    }

    @Test fun toggleSolution_isUndoable() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("25+15*2=")); advanceTimeBy(100); runCurrent()
        val id = vm.ui.value.expressions.keys.single()
        vm.onToggleSolution(id); assertFalse(vm.ui.value.expressions.getValue(id).solutionVisible)
        vm.onUndo(); assertTrue(vm.ui.value.expressions.getValue(id).solutionVisible)
    }

    @Test fun movingAnExpression_doesNotTriggerRecognitionAgain() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("25+15=")); advanceTimeBy(100); runCurrent()
        val id = vm.ui.value.expressions.keys.single(); val resultBefore = vm.ui.value.expressions.getValue(id)
        vm.onMoveExpression(id, 500f, 500f); advanceTimeBy(2_000); runCurrent()
        assertEquals(resultBefore, vm.ui.value.expressions.getValue(id))
    }

    @Test fun selection_isClearedWhenTheExpressionIsDeleted() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("12")); val id = vm.ui.value.expressions.keys.single()
        vm.onSelect(id); vm.onDeleteSelected()
        assertNull(vm.ui.value.selectedId); assertTrue(vm.ui.value.strokes.isEmpty())
    }

    @Test fun autosave_isDebounced_andSavesWhatWasDrawn() = runTest(dispatcher) {
        settingsFlow.value = AppSettings(idleDelayMs = 600_000L)      // recognition results are state changes too; keep them out of this window
        val vm = vm(); runCurrent()
        vm.write(clean("12")); vm.write(clean("34", 4))
        advanceTimeBy(CanvasViewModel.AUTOSAVE_DELAY_MS - 100); runCurrent()
        assertEquals(0, repo.saveCount)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, repo.saveCount); assertEquals(vm.ui.value.strokes.size, repo.lastSaved!!.strokes.size)
    }

    @Test fun autosave_restartsWhileTheStateKeepsChanging_includingRecognitionResults() = runTest(dispatcher) {
        settingsFlow.value = AppSettings(idleDelayMs = 700L)
        val vm = vm(); runCurrent()
        vm.write(clean("25+15")); advanceTimeBy(CanvasViewModel.AUTOSAVE_DELAY_MS + 50); runCurrent()
        assertEquals("recognition finished at 700 ms and postponed the save", 0, repo.saveCount)
        advanceTimeBy(CanvasViewModel.AUTOSAVE_DELAY_MS); runCurrent()
        assertEquals(1, repo.saveCount)
        assertEquals("the saved state already contains the recognised result", "40", repo.lastSaved!!.expressions.values.single().result)
    }

    @Test fun flush_savesImmediately() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); vm.write(clean("12")); vm.flush(); runCurrent()
        assertEquals(1, repo.saveCount)
    }

    @Test fun newAndOpenCanvas_switchWorkspaces() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        val firstId = vm.ui.value.canvas!!.id
        vm.write(clean("12")); vm.onNewCanvas(); runCurrent()
        assertNotEquals(firstId, vm.ui.value.canvas!!.id); assertTrue(vm.ui.value.strokes.isEmpty())
        vm.onOpenCanvas(firstId); runCurrent()
        assertTrue("work on the first canvas was saved before switching", vm.ui.value.strokes.isNotEmpty())
    }

    @Test fun deletingTheOpenCanvas_opensAnother() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        val id = vm.ui.value.canvas!!.id
        vm.onDeleteCanvas(id); runCurrent()
        assertNotNull(vm.ui.value.canvas); assertFalse(repo.docs.containsKey(id))
    }

    @Test fun renamingACanvas_updatesTheTitle() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); val id = vm.ui.value.canvas!!.id
        vm.onRenameCanvas(id, "Homework"); runCurrent()
        assertEquals("Homework", vm.ui.value.canvas!!.name)
    }

    @Test fun stateFlow_emitsLoadedState() = runTest(dispatcher) {
        val vm = vm()
        vm.ui.test {
            awaitItem()                         // initial (loading)
            runCurrent()
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertFalse(s.loading); cancelAndIgnoreRemainingEvents()
        }
    }


    @Test fun togglingTheGraphOfSomethingThatCannotBeGraphed_doesNothing() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        vm.write(clean("25+15")); advanceTimeBy(2_000); runCurrent()
        vm.onToggleGraph(vm.ui.value.expressions.keys.single())
        assertTrue(vm.ui.value.graphs.isEmpty()); assertTrue(vm.ui.value.graphData.isEmpty())
    }
    @Test fun aNewCanvas_startsWithNoGraphs() = runTest(dispatcher) { val vm = vm(); runCurrent(); assertTrue(vm.ui.value.graphs.isEmpty()) }


    @Test fun deletingACanvas_forgetsItsGraphPanels() = runTest(dispatcher) {
        val vm = vm(); runCurrent()
        val id = vm.ui.value.canvas!!.id
        panels.save(id, com.bsoft.bhandbook.domain.model.GraphPanelState(visible = setOf("x")))
        vm.onDeleteCanvas(id); runCurrent()
        assertEquals(com.bsoft.bhandbook.domain.model.GraphPanelState(), panels.load(id))
    }
    @Test fun importingSomethingThatIsNotACanvasFile_justShowsAMessage() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); val before = vm.ui.value.canvases.size
        vm.onImportCanvas("this is not a canvas file"); runCurrent()
        assertNotNull(vm.ui.value.notice); assertEquals(before, vm.ui.value.canvases.size)
        vm.onNoticeShown(); assertNull(vm.ui.value.notice)
        vm.onImportCanvas(null); runCurrent(); assertNotNull(vm.ui.value.notice)
    }
    @Test fun aCanvasWithoutSavedPanels_opensWithoutGraphs() = runTest(dispatcher) { val vm = vm(); runCurrent(); assertTrue(vm.ui.value.graphs.isEmpty() && vm.ui.value.graphOffsets.isEmpty() && vm.ui.value.graphScales.isEmpty()) }
}

class FakeGraphPanels : com.bsoft.bhandbook.domain.repository.GraphPanelRepository {
    val map = HashMap<String, com.bsoft.bhandbook.domain.model.GraphPanelState>()
    override fun load(canvasId: String) = map[canvasId] ?: com.bsoft.bhandbook.domain.model.GraphPanelState()
    override fun save(canvasId: String, state: com.bsoft.bhandbook.domain.model.GraphPanelState) { map[canvasId] = state }
    override fun delete(canvasId: String) { map.remove(canvasId) }
}
