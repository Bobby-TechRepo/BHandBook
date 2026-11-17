package com.bsoft.bhandbook.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.feature.canvas.CanvasActions
import com.bsoft.bhandbook.feature.canvas.Sheet

/** What a new user needs to know, in the order they will want it. Shown as a welcome card on a first empty canvas and as a sheet from Settings. */
val TIP_ITEMS: List<Pair<String, String>> = listOf(
    "Calculate" to "Write something like 25+15= . The answer appears when you stop writing, or at once after the equals sign.",
    "Add up a column" to "Write numbers one under another and draw a line underneath: the total appears below the line.",
    "Solve and graph" to "Write an equation like 2x+5=15 to solve it with steps. Two equations, one under the other, are solved together. Equations in x and y get a Graph button. On a graph, two fingers move and zoom it, a tap traces a point, and a double tap resets the view.",
    "Name values and functions" to "Write a = 5, then 2a + 1 = gives 11. Write f(x) = x^2 - 4, then f(3) = gives 5, and y = f(x) can be graphed. The letters x, y and z are for equations and cannot be named.",
    "Fix a misread" to "If an answer looks wrong, choose the select tool, tap the expression, then Edit: pick one of the suggested readings or type your own.",
    "Angles and decimals" to "Tap the DEG chip under the top bar to switch between degrees, radians and gradians, and between fractions and decimals.",
    "Move around" to "Use the hand tool (or two fingers) to move and zoom, and \"Show all\" to frame everything you have written. To handle several things at once, choose the select tool, tap one and press \"Select more\", then tap the others: drag to move them together, drag the round handle to resize them, or delete them. The Canvases sheet searches every canvas. Share exports an image or a PDF.",
)

@Composable
fun TipsList(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((title, text) in TIP_ITEMS) Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsSheet(actions: CanvasActions) {
    ModalBottomSheet({ actions.onSheet(Sheet.NONE) }, Modifier.testTag("tips_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tips", style = MaterialTheme.typography.titleLarge)
            TipsList()
            Button({ actions.onSheet(Sheet.NONE) }, Modifier.fillMaxWidth().testTag("tips_done")) { Text("Got it") }
        }
    }
}
