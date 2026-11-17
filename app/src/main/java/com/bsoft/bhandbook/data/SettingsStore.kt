package com.bsoft.bhandbook.data

import android.content.Context
import android.content.SharedPreferences
import com.bsoft.bhandbook.domain.model.AppSettings
import com.bsoft.bhandbook.domain.model.CanvasBackground
import com.bsoft.bhandbook.domain.model.GraphSettings
import com.bsoft.bhandbook.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Tiny preferences store (SharedPreferences: no extra dependency needed for four values). */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read() = AppSettings(
        autoEvaluate = prefs.getBoolean("autoEvaluate", true),
        idleDelayMs = prefs.getLong("idleDelayMs", 900L).coerceIn(150L, 5000L),
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        defaultBackground = runCatching { CanvasBackground.valueOf(prefs.getString("background", "GRID")!!) }.getOrDefault(CanvasBackground.GRID),
        tipsSeen = prefs.getBoolean("tipsSeen", false),
        calc = com.bsoft.bhandbook.core.math.CalcSettings(
            angle = runCatching { com.bsoft.bhandbook.core.math.AngleMode.valueOf(prefs.getString("angleMode", "DEGREES")!!) }.getOrDefault(com.bsoft.bhandbook.core.math.AngleMode.DEGREES),
            style = runCatching { com.bsoft.bhandbook.core.math.ResultStyle.valueOf(prefs.getString("resultStyle", "AUTO")!!) }.getOrDefault(com.bsoft.bhandbook.core.math.ResultStyle.AUTO),
            places = prefs.getInt("decimalPlaces", -1).takeIf { it >= 0 },
            grouping = prefs.getBoolean("grouping", false), currency = prefs.getString("currency", "").orEmpty(),
        ).normalized(),
        graph = GraphSettings(
            lineWidth = prefs.getFloat("graphLineWidth", 1f), showGrid = prefs.getBoolean("graphGrid", true), showGuides = prefs.getBoolean("graphGuides", true),
            showMarkerLabels = prefs.getBoolean("graphMarkerLabels", true), showLegend = prefs.getBoolean("graphLegend", true), defaultSize = prefs.getFloat("graphSize", 1f),
        ).normalized(),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_settings.value)
        prefs.edit().putBoolean("autoEvaluate", n.autoEvaluate).putLong("idleDelayMs", n.idleDelayMs)
            .putString("theme", n.themeMode.name).putString("background", n.defaultBackground.name)
            .putBoolean("tipsSeen", n.tipsSeen).putString("angleMode", n.calc.angle.name).putString("resultStyle", n.calc.style.name).putInt("decimalPlaces", n.calc.places ?: -1).putBoolean("grouping", n.calc.grouping).putString("currency", n.calc.currency)
            .putFloat("graphLineWidth", n.graph.lineWidth).putBoolean("graphGrid", n.graph.showGrid).putBoolean("graphGuides", n.graph.showGuides)
            .putBoolean("graphMarkerLabels", n.graph.showMarkerLabels).putBoolean("graphLegend", n.graph.showLegend).putFloat("graphSize", n.graph.defaultSize).apply()
        _settings.value = n
    }
}
