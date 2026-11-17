package com.bsoft.bhandbook.feature.canvas

import androidx.compose.runtime.Immutable
import com.bsoft.bhandbook.core.graphics.CanvasTransform
import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.domain.editor.Insights
import com.bsoft.bhandbook.domain.graph.GraphData
import com.bsoft.bhandbook.domain.model.*
import com.bsoft.bhandbook.domain.repository.CanvasInfo

enum class Tool { PEN, ERASER, SELECT }

/** How a single finger behaves in the pen tool. A stylus always draws. Two fingers always pan/zoom. */
enum class InputMode { DRAW, NAVIGATE }

enum class Sheet { NONE, CANVASES, SETTINGS, MODES, TIPS }

@Immutable
data class CanvasUiState(
    val loading: Boolean = true,
    val canvas: CanvasInfo? = null,
    val canvases: List<CanvasInfo> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    val expressions: Map<String, ExpressionObject> = emptyMap(),
    val transform: CanvasTransform = CanvasTransform(),
    val tool: Tool = Tool.PEN,
    val inputMode: InputMode = InputMode.DRAW,
    /** Pen thickness in screen pixels at draw time; stored per stroke in world units so zooming keeps ink proportional. */
    val penWidthPx: Float = 4f,
    val background: CanvasBackground = CanvasBackground.GRID,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedId: String? = null,
    val correctionFor: String? = null,
    val settings: AppSettings = AppSettings(),
    val sheet: Sheet = Sheet.NONE,
    /** Simultaneous equations found on the canvas and which expressions can be graphed (derived, never saved). */
    val insights: Insights = Insights(emptyMap(), emptySet()),
    /** Ids whose graph panel is shown, and the data to draw. Temporary: not saved with the canvas. */
    val graphs: Set<String> = emptySet(),
    val graphData: Map<String, GraphData> = emptyMap(),
    /** An expression that was just recognised and can be graphed: the screen offers a Graph button for it until something else happens. */
    val graphSuggestion: String? = null,
    /** How far (world units) the user has dragged each graph panel from its default place beside its expression. Temporary, like the graphs. */
    val graphOffsets: Map<String, Vec2> = emptyMap(),
    /** Size multiplier chosen by hand for each graph panel (graphs not listed use the default size from settings). Temporary. */
    val graphScales: Map<String, Float> = emptyMap(),
    /** The part of each graph chosen by hand (pan / zoom inside its panel); a graph not listed is framed automatically. */
    val graphViews: Map<String, com.bsoft.bhandbook.domain.graph.GraphViewport> = emptyMap(),
    /** The point traced on each graph (tap a graph); transient, dropped when the graph is rebuilt from changed equations. */
    val graphTrace: Map<String, com.bsoft.bhandbook.domain.graph.TracePoint> = emptyMap(),
    /** Other readings of each expression's ink ("did you mean"), from the latest recognition; kept in memory only. */
    val alternatives: Map<String, List<String>> = emptyMap(),
    /** A message for the user, shown once (e.g. the result of an import). */
    val notice: String? = null,
    /** Several expressions selected at once (a mode: while active, taps add or remove). */
    val multi: com.bsoft.bhandbook.domain.editor.MultiSelection = com.bsoft.bhandbook.domain.editor.MultiSelection(),
    /** Search across all canvases (the Canvases sheet). */
    val search: SearchState = SearchState(),
    /** An expression to bring into view once its canvas is showing (a search hit that was opened). */
    val focusRequest: String? = null,
    /** A backup file that has been built and is waiting to be handed to the share sheet. */
    val pendingFile: PendingFile? = null,
)

/** What was searched for, what was found, and whether the search is still running. */
data class SearchState(val query: String = "", val hits: List<com.bsoft.bhandbook.domain.editor.SearchHit> = emptyList(), val searching: Boolean = false)

/** A backup file ready to be written and shared: its file name and its text. */
class PendingFile(val name: String, val text: String)

/** Everything the stateless [CanvasScreen] can ask for. Makes the screen trivially testable without Hilt. */
class CanvasActions(
    val onStrokeFinished: (List<StrokePoint>) -> Unit = {},
    val onEraseStart: () -> Unit = {},
    val onEraseMove: (Vec2, Float) -> Unit = { _, _ -> },
    val onEraseEnd: () -> Unit = {},
    val onTransformChanged: (CanvasTransform) -> Unit = {},
    val onToolSelected: (Tool) -> Unit = {},
    val onInputModeChanged: (InputMode) -> Unit = {},
    val onPenWidthSelected: (Float) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onMoveExpression: (String, Float, Float) -> Unit = { _, _, _ -> },
    val onDeleteSelected: () -> Unit = {},
    val onRecalculate: (String) -> Unit = {},
    val onToggleSolution: (String) -> Unit = {},
    val onToggleGraph: (String) -> Unit = {},
    val onMoveGraph: (String, Float, Float) -> Unit = { _, _, _ -> },
    val onResizeGraph: (String, Float) -> Unit = { _, _ -> },
    val onTipsSeen: (Boolean) -> Unit = {},
    /** Sets (or, with null, resets to automatic framing) the part of a graph that its panel shows. */
    val onGraphView: (String, com.bsoft.bhandbook.domain.graph.GraphViewport?) -> Unit = { _, _ -> },
    /** Traces the graph point nearest to a tap, given in graph coordinates with the tolerance (in graph units) of a finger; tapping the traced point again clears it. */
    val onTraceGraph: (String, Double, Double, Double, Double) -> Unit = { _, _, _, _, _ -> },
    /** Builds a backup file of a canvas and offers it to the share sheet. */
    val onExportCanvas: (String) -> Unit = {},
    /** Creates a NEW canvas from the text of a backup file (null: the file could not be read or was too large). */
    val onImportCanvas: (String?) -> Unit = {},
    val onNoticeShown: () -> Unit = {},
    /** Resizes expressions by a factor about a point (the ink, and with it their answers and steps); one undo step. */
    val onResizeExpressions: (Set<String>, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    val onMultiStart: () -> Unit = {},
    val onMultiStop: () -> Unit = {},
    val onMultiSelectAll: () -> Unit = {},
    val onMoveExpressions: (Set<String>, Float, Float) -> Unit = { _, _, _ -> },
    val onDeleteMulti: () -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onOpenHit: (com.bsoft.bhandbook.domain.editor.SearchHit) -> Unit = {},
    val onFocusHandled: () -> Unit = {},
    val onFileHandled: () -> Unit = {},
    /** Changes the modes (angle unit, result format, decimal places); everything already on the canvas is re-evaluated, the ink is not re-read. */
    val onCalcSettings: ((com.bsoft.bhandbook.core.math.CalcSettings) -> com.bsoft.bhandbook.core.math.CalcSettings) -> Unit = {},
    val onGraphSettings: ((com.bsoft.bhandbook.domain.model.GraphSettings) -> com.bsoft.bhandbook.domain.model.GraphSettings) -> Unit = {},
    val onEditRecognition: (String?) -> Unit = {},
    val onApplyCorrection: (String, String) -> Unit = { _, _ -> },
    val onContributeSample: (String, String) -> Unit = { _, _ -> },
    val onNewCanvas: () -> Unit = {},
    val onOpenCanvas: (String) -> Unit = {},
    val onRenameCanvas: (String, String) -> Unit = { _, _ -> },
    val onDeleteCanvas: (String) -> Unit = {},
    val onSheet: (Sheet) -> Unit = {},
    val onBackground: (CanvasBackground) -> Unit = {},
    val onAutoEvaluate: (Boolean) -> Unit = {},
    val onThemeMode: (ThemeMode) -> Unit = {},
    val onIdleDelay: (Long) -> Unit = {},
    val onSave: () -> Unit = {},
)
