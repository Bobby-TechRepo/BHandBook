package com.bsoft.bhandbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bsoft.bhandbook.feature.canvas.CanvasActions
import com.bsoft.bhandbook.feature.canvas.CanvasScreen
import com.bsoft.bhandbook.feature.canvas.CanvasViewModel
import com.bsoft.bhandbook.ui.theme.BHandBookTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: CanvasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.ui.collectAsStateWithLifecycle()
            val actions = remember(viewModel) { viewModel.asActions() }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }     // never lose work when backgrounded
            BHandBookTheme(state.settings.themeMode) { CanvasScreen(state, actions) }
        }
    }
}

private fun CanvasViewModel.asActions() = CanvasActions(
    onStrokeFinished = ::onStrokeFinished, onEraseStart = ::onEraseStart, onEraseMove = ::onEraseMove, onEraseEnd = ::onEraseEnd,
    onTransformChanged = ::onTransformChanged, onToolSelected = ::onToolSelected, onInputModeChanged = ::onInputModeChanged,
    onPenWidthSelected = ::onPenWidthSelected, onUndo = ::onUndo, onRedo = ::onRedo, onClear = ::onClear,
    onSelect = ::onSelect, onMoveExpression = ::onMoveExpression, onDeleteSelected = ::onDeleteSelected,
    onRecalculate = ::onRecalculate, onToggleSolution = ::onToggleSolution, onToggleGraph = ::onToggleGraph, onMoveGraph = ::onMoveGraph, onResizeGraph = ::onResizeGraph, onTipsSeen = ::onTipsSeen, onGraphView = ::onGraphView, onTraceGraph = ::onTraceGraph, onExportCanvas = ::onExportCanvas, onImportCanvas = ::onImportCanvas, onNoticeShown = ::onNoticeShown, onResizeExpressions = ::onResizeExpressions, onMultiStart = ::onMultiStart, onMultiStop = ::onMultiStop, onMultiSelectAll = ::onMultiSelectAll, onMoveExpressions = ::onMoveExpressions, onDeleteMulti = ::onDeleteMulti, onSearch = ::onSearch, onOpenHit = ::onOpenHit, onFocusHandled = ::onFocusHandled, onFileHandled = ::onFileHandled, onCalcSettings = ::onCalcSettings, onGraphSettings = ::onGraphSettings, onEditRecognition = ::onEditRecognition,
    onApplyCorrection = ::onApplyCorrection, onContributeSample = ::onContributeSample, onNewCanvas = ::onNewCanvas, onOpenCanvas = ::onOpenCanvas,
    onRenameCanvas = ::onRenameCanvas, onDeleteCanvas = ::onDeleteCanvas, onSheet = ::onSheet, onBackground = ::onBackground,
    onAutoEvaluate = ::onAutoEvaluate, onThemeMode = ::onThemeMode, onIdleDelay = ::onIdleDelay, onSave = ::flush,
)
