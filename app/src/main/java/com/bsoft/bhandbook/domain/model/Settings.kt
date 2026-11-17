package com.bsoft.bhandbook.domain.model

enum class CanvasBackground { WHITE, GRID, DARK }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How graphs look. [lineWidth] and [defaultSize] are multipliers (1.0 = the standard look). */
data class GraphSettings(
    val lineWidth: Float = 1f,
    val showGrid: Boolean = true,
    /** Dashed lines from each intersection to the axes, with its values printed on them. */
    val showGuides: Boolean = true,
    /** The "(x, y)" label next to each intersection. */
    val showMarkerLabels: Boolean = true,
    val showLegend: Boolean = true,
    /** Size of a graph panel that has not been resized by hand. */
    val defaultSize: Float = 1f,
) {
    fun normalized() = copy(lineWidth = clamp(lineWidth, MIN_LINE_WIDTH, MAX_LINE_WIDTH), defaultSize = clamp(defaultSize, MIN_SIZE, MAX_SIZE))

    companion object {
        /** Curve width at [lineWidth] = 1, in units of 1/160 of the panel width (it used to be a fixed 2.2, which was too heavy). */
        const val BASE_LINE_UNITS = 1.3f
        const val MIN_LINE_WIDTH = 0.4f; const val MAX_LINE_WIDTH = 3f
        const val MIN_SIZE = 0.5f; const val MAX_SIZE = 3f
        private fun clamp(v: Float, lo: Float, hi: Float) = if (v.isNaN()) 1f else v.coerceIn(lo, hi)
    }
}

/** App-wide preferences. [idleDelayMs] is the pause after the last stroke before recognition starts. */
data class AppSettings(
    val autoEvaluate: Boolean = true,
    val idleDelayMs: Long = 900L,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultBackground: CanvasBackground = CanvasBackground.GRID,
    val graph: GraphSettings = GraphSettings(),
    /** The welcome tips have been dismissed (or the user started writing). */
    val tipsSeen: Boolean = false,
    /** The "modes": angle unit (degrees by default), fractions or decimals, decimal places. */
    val calc: com.bsoft.bhandbook.core.math.CalcSettings = com.bsoft.bhandbook.core.math.CalcSettings(angle = com.bsoft.bhandbook.core.math.AngleMode.DEGREES),
)
