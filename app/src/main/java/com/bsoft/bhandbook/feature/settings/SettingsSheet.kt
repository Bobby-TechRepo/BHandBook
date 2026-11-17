package com.bsoft.bhandbook.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.domain.model.CanvasBackground
import com.bsoft.bhandbook.domain.model.GraphSettings
import com.bsoft.bhandbook.domain.model.ThemeMode
import com.bsoft.bhandbook.feature.canvas.CanvasActions
import com.bsoft.bhandbook.feature.canvas.CanvasUiState
import com.bsoft.bhandbook.feature.canvas.Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: CanvasUiState, actions: CanvasActions) {
    ModalBottomSheet({ actions.onSheet(Sheet.NONE) }, Modifier.testTag("settings_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Evaluate automatically", style = MaterialTheme.typography.titleSmall)
                    Text("Show the result as soon as you stop writing. Drawing “=” always asks for the result.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.settings.autoEvaluate, actions.onAutoEvaluate, Modifier.semantics { contentDescription = "Evaluate automatically" }.testTag("switch_auto"))
            }

            Text("Recognition delay: ${state.settings.idleDelayMs} ms", style = MaterialTheme.typography.titleSmall)
            Slider(state.settings.idleDelayMs.toFloat(), { actions.onIdleDelay((it / 50f).toInt() * 50L) }, valueRange = 300f..2000f, modifier = Modifier.testTag("slider_delay"))

            Text("Background (this canvas)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, bg) in listOf("White" to CanvasBackground.WHITE, "Grid" to CanvasBackground.GRID, "Dark" to CanvasBackground.DARK))
                    FilterChip(state.background == bg, { actions.onBackground(bg) }, { Text(label) }, Modifier.testTag("bg_$label"))
            }

            androidx.compose.material3.TextButton({ actions.onTipsSeen(false) }, Modifier.testTag("show_tips")) { Text("Show tips") }
            val showAbout = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.TextButton({ showAbout.value = true }, Modifier.testTag("about")) { Text("About & licenses") }
            if (showAbout.value) AboutDialog { showAbout.value = false }

            Text("Graphs", style = MaterialTheme.typography.titleMedium)
            val gs = state.settings.graph
            Text("Line thickness: ${"%.1f".format(gs.lineWidth)}×", style = MaterialTheme.typography.titleSmall)
            Slider(gs.lineWidth, { v -> actions.onGraphSettings { it.copy(lineWidth = (v * 10f).toInt() / 10f) } }, valueRange = GraphSettings.MIN_LINE_WIDTH..GraphSettings.MAX_LINE_WIDTH, modifier = Modifier.testTag("slider_graph_width"))
            Text("New graph size: ${"%.1f".format(gs.defaultSize)}×  (drag a graph's corner to resize just that one)", style = MaterialTheme.typography.titleSmall)
            Slider(gs.defaultSize, { v -> actions.onGraphSettings { it.copy(defaultSize = (v * 10f).toInt() / 10f) } }, valueRange = GraphSettings.MIN_SIZE..GraphSettings.MAX_SIZE, modifier = Modifier.testTag("slider_graph_size"))
            GraphSwitch("Grid lines", "Light lines behind the curves.", gs.showGrid, "switch_graph_grid") { v -> actions.onGraphSettings { it.copy(showGrid = v) } }
            GraphSwitch("Guide lines", "Dashed lines from each intersection to the axes, with its values printed on them.", gs.showGuides, "switch_graph_guides") { v -> actions.onGraphSettings { it.copy(showGuides = v) } }
            GraphSwitch("Point labels", "Show the (x, y) next to each intersection.", gs.showMarkerLabels, "switch_graph_labels") { v -> actions.onGraphSettings { it.copy(showMarkerLabels = v) } }
            GraphSwitch("Legend", "Show each curve's equation inside the graph.", gs.showLegend, "switch_graph_legend") { v -> actions.onGraphSettings { it.copy(showLegend = v) } }

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, m) in listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK))
                    FilterChip(state.settings.themeMode == m, { actions.onThemeMode(m) }, { Text(label) }, Modifier.testTag("theme_$label"))
            }
        }
    }
}

@Composable
private fun GraphSwitch(title: String, subtitle: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange, Modifier.semantics { contentDescription = title }.testTag(tag))
    }
}
