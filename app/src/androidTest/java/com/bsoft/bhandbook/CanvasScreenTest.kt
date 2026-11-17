package com.bsoft.bhandbook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.solver.SolutionStep
import com.bsoft.bhandbook.feature.canvas.*
import com.bsoft.bhandbook.ui.theme.BHandBookTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stateless-screen tests: no Hilt, no database. The state goes in, the actions come out. */
@RunWith(AndroidJUnit4::class)
class CanvasScreenTest {
    @get:Rule val rule = createComposeRule()

    private val steps = listOf(SolutionStep(1, "25 + 15 × 2", "Multiply 15 × 2 first", "25 + 30"), SolutionStep(2, "25 + 30", "Add 25 + 30", "55"))
    private val expr = ExpressionObject("e1", recognized = "25+15*2=", confidence = 0.9f, result = "55", steps = steps, displayText = "25 + 15 × 2", status = ProcessingStatus.EVALUATED)

    private fun show(state: CanvasUiState, actions: CanvasActions = CanvasActions(), width: Int = 400, height: Int = 800) =
        // requiredSize ignores the test window's own limits so wide layouts really compose
        rule.setContent { BHandBookTheme { Box(Modifier.requiredSize(width.dp, height.dp)) { CanvasScreen(state, actions) } } }

    @Test fun canvasAndToolbarAreVisible_atStartup() {
        show(CanvasUiState(loading = false))
        rule.onNodeWithTag(CANVAS_TAG).assertIsDisplayed()
        listOf("Pen", "Eraser", "Select", "Undo", "Redo", "Clear canvas").forEach { rule.onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    @Test fun switchingTools_reportsTheChoice() {
        var chosen: Tool? = null
        show(CanvasUiState(loading = false), CanvasActions(onToolSelected = { chosen = it }))
        rule.onNodeWithContentDescription("Eraser").performClick(); assertEquals(Tool.ERASER, chosen)
        rule.onNodeWithContentDescription("Select").performClick(); assertEquals(Tool.SELECT, chosen)
    }

    @Test fun undoRedo_areDisabledUntilThereIsHistory() {
        show(CanvasUiState(loading = false, canUndo = false, canRedo = false))
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled(); rule.onNodeWithContentDescription("Redo").assertIsNotEnabled()
    }

    @Test fun undoRedo_callBack_whenEnabled() {
        var undo = 0; var redo = 0
        show(CanvasUiState(loading = false, canUndo = true, canRedo = true), CanvasActions(onUndo = { undo++ }, onRedo = { redo++ }))
        rule.onNodeWithContentDescription("Undo").assertIsEnabled().performClick(); rule.onNodeWithContentDescription("Redo").performClick()
        assertEquals(1, undo); assertEquals(1, redo)
    }

    @Test fun clearCanvas_asksForConfirmation() {
        var cleared = 0
        show(CanvasUiState(loading = false), CanvasActions(onClear = { cleared++ }))
        rule.onNodeWithContentDescription("Clear canvas").performClick()
        assertEquals(0, cleared)
        rule.onNodeWithTag("confirm_clear").performClick(); assertEquals(1, cleared)
    }

    @Test fun drawingWithAFinger_producesAStroke_inWorldCoordinates() {
        var points: List<StrokePoint>? = null
        show(CanvasUiState(loading = false, transform = CanvasTransform(scale = 2f)), CanvasActions(onStrokeFinished = { points = it }))
        rule.onNodeWithTag(CANVAS_TAG).performTouchInput { swipe(Offset(100f, 300f), Offset(300f, 320f), durationMillis = 200) }
        rule.waitForIdle()
        val p = points!!
        assertTrue(p.size > 2); assertEquals(50f, p.first().x, 6f)       // screen x=100 at scale 2 => world x≈50
    }

    @Test fun fingerInNavigateMode_pansInsteadOfDrawing() {
        var strokes = 0; var last: CanvasTransform? = null
        show(CanvasUiState(loading = false, inputMode = InputMode.NAVIGATE), CanvasActions(onStrokeFinished = { strokes++ }, onTransformChanged = { last = it }))
        rule.onNodeWithTag(CANVAS_TAG).performTouchInput { swipe(Offset(100f, 300f), Offset(260f, 380f), durationMillis = 200) }
        rule.waitForIdle()
        assertEquals(0, strokes); assertTrue(last!!.offset.x > 100f)
    }

    @Test fun pinch_zoomsIn() {
        var last: CanvasTransform? = null
        show(CanvasUiState(loading = false, inputMode = InputMode.NAVIGATE), CanvasActions(onTransformChanged = { last = it }))
        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            pinch(start0 = Offset(250f, 400f), end0 = Offset(150f, 400f), start1 = Offset(310f, 400f), end1 = Offset(410f, 400f), durationMillis = 300)   // fingers move apart: 60px -> 260px
        }
        rule.waitForIdle(); assertTrue("scale=${last?.scale}", last!!.scale > 1.2f)
    }

    @Test fun selectedExpression_showsContextualActions() {
        var recalculated: String? = null; var deleted = 0
        show(CanvasUiState(loading = false, tool = Tool.SELECT, expressions = mapOf("e1" to expr), selectedId = "e1"),
            CanvasActions(onRecalculate = { recalculated = it }, onDeleteSelected = { deleted++ }))
        rule.onNodeWithTag("context_bar").assertIsDisplayed()
        rule.onNodeWithTag("ctx_recalculate").performClick(); assertEquals("e1", recalculated)
        rule.onNodeWithTag("ctx_delete").performClick(); assertEquals(1, deleted)
    }

    @Test fun nothingSelected_noContextBar() { show(CanvasUiState(loading = false)); rule.onNodeWithTag("context_bar").assertDoesNotExist() }

    @Test fun solutionCanBeHiddenAndShown() {
        var toggled = 0
        show(CanvasUiState(loading = false, tool = Tool.SELECT, expressions = mapOf("e1" to expr), selectedId = "e1"), CanvasActions(onToggleSolution = { toggled++ }))
        rule.onNodeWithText("Hide steps").performClick(); assertEquals(1, toggled)
    }

    @Test fun correctionDialog_sendsTheEditedText() {
        var sent: Pair<String, String>? = null
        show(CanvasUiState(loading = false, expressions = mapOf("e1" to expr), correctionFor = "e1"), CanvasActions(onApplyCorrection = { id, t -> sent = id to t }))
        rule.onNodeWithTag("correction_field").performTextClearance(); rule.onNodeWithTag("correction_field").performTextInput("2+2=")
        rule.onNodeWithTag("correction_apply").performClick(); assertEquals("e1" to "2+2=", sent)
    }

    @Test fun correctionDialog_canContributeTheHandwritingAsASample() {
        var sample: Pair<String, String>? = null; var applied = false
        show(CanvasUiState(loading = false, expressions = mapOf("e1" to expr), correctionFor = "e1"),
            CanvasActions(onContributeSample = { id, t -> sample = id to t }, onApplyCorrection = { _, _ -> applied = true }))
        rule.onNodeWithTag("correction_apply").performClick()
        assertNull("not shared unless ticked", sample); assertTrue(applied)
    }

    @Test fun correctionDialog_contributesOnlyWhenTheBoxIsTicked() {
        var sample: Pair<String, String>? = null
        show(CanvasUiState(loading = false, expressions = mapOf("e1" to expr), correctionFor = "e1"), CanvasActions(onContributeSample = { id, t -> sample = id to t }))
        rule.onNodeWithTag("correction_contribute").performClick()
        rule.onNodeWithTag("correction_apply").performClick()
        assertEquals("e1" to "25+15*2=", sample)
    }

    @Test fun tabletLayout_showsStepsInASidePanel() {
        show(CanvasUiState(loading = false, tool = Tool.SELECT, expressions = mapOf("e1" to expr), selectedId = "e1"), width = 1000, height = 700)
        // a 1000dp layout can be wider than the test device's window, so assert presence, not on-screen visibility
        rule.onNodeWithTag("side_panel").assertExists()
        rule.onNodeWithTag("step_1").assertExists(); rule.onNodeWithTag("step_2").assertExists()
    }

    @Test fun phoneLayout_hasNoSidePanel() { show(CanvasUiState(loading = false), width = 400); rule.onNodeWithTag("side_panel").assertDoesNotExist() }

    @Test fun settingsSheet_togglesAutoEvaluate() {
        var auto: Boolean? = null
        show(CanvasUiState(loading = false, sheet = Sheet.SETTINGS), CanvasActions(onAutoEvaluate = { auto = it }))
        rule.onNodeWithTag("switch_auto").performClick(); assertEquals(false, auto)
    }

    @Test fun canvasList_listsAndOpens() {
        var opened: String? = null
        val infos = listOf(com.bsoft.bhandbook.domain.repository.CanvasInfo("a", "Algebra", 0, 0), com.bsoft.bhandbook.domain.repository.CanvasInfo("b", "Geometry", 0, 0))
        show(CanvasUiState(loading = false, canvases = infos, sheet = Sheet.CANVASES), CanvasActions(onOpenCanvas = { opened = it }))
        rule.onNodeWithText("Geometry").performClick(); assertEquals("b", opened)
    }

    @Test fun canvasHasAnAccessibleSummary() {
        show(CanvasUiState(loading = false, expressions = mapOf("e1" to expr)))
        rule.onNodeWithTag(CANVAS_TAG).assert(androidx.compose.ui.test.hasContentDescription("55", substring = true))
    }
}
