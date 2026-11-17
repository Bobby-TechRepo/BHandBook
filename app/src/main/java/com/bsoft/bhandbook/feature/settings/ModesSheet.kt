package com.bsoft.bhandbook.feature.settings

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.core.math.AngleMode
import com.bsoft.bhandbook.core.math.Bin
import com.bsoft.bhandbook.core.math.CalcResult
import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.core.math.ExactCalculationEngine
import com.bsoft.bhandbook.core.math.Expr
import com.bsoft.bhandbook.core.math.Func
import com.bsoft.bhandbook.core.math.MoneyFormat
import com.bsoft.bhandbook.core.math.Num
import com.bsoft.bhandbook.core.math.NumberFormatter
import com.bsoft.bhandbook.core.math.Op
import com.bsoft.bhandbook.core.math.Rational
import com.bsoft.bhandbook.core.math.ResultStyle
import com.bsoft.bhandbook.feature.canvas.CanvasActions
import com.bsoft.bhandbook.feature.canvas.CanvasUiState
import com.bsoft.bhandbook.feature.canvas.Sheet

/** Short label for the mode chip on the canvas, e.g. "DEG", "RAD · FIX 2", "GRAD · DEC". */
fun modeLabel(calc: CalcSettings): String = buildList {
    add(when (calc.angle) { AngleMode.DEGREES -> "DEG"; AngleMode.RADIANS -> "RAD"; AngleMode.GRADIANS -> "GRAD" })
    if (calc.style == ResultStyle.DECIMAL) add("DEC")
    calc.places?.let { add("FIX $it") }
    if (calc.currency.isNotEmpty()) add(calc.currency)
}.joinToString(" · ")

/** The modes: how angles are read, and how answers are written. Changing one re-evaluates everything already on the canvas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModesSheet(state: CanvasUiState, actions: CanvasActions) {
    val calc = state.settings.calc
    ModalBottomSheet({ actions.onSheet(Sheet.NONE) }, Modifier.testTag("modes_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Modes", style = MaterialTheme.typography.titleLarge)

            Text("Angle unit", style = MaterialTheme.typography.titleSmall)
            Text("sin, cos and tan read their input in this unit; asin, acos and atan answer in it. Graphs use it too.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, mode) in listOf("Degrees" to AngleMode.DEGREES, "Radians" to AngleMode.RADIANS, "Gradians" to AngleMode.GRADIANS))
                    FilterChip(calc.angle == mode, { actions.onCalcSettings { it.copy(angle = mode) } }, { Text(label) }, Modifier.testTag("mode_angle_$label"))
            }

            Text("Results", style = MaterialTheme.typography.titleSmall)
            Text("Auto keeps exact fractions (1/3) with the decimal beside them; Decimal always shows decimals.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, style) in listOf("Auto" to ResultStyle.AUTO, "Decimal" to ResultStyle.DECIMAL))
                    FilterChip(calc.style == style, { actions.onCalcSettings { it.copy(style = style) } }, { Text(label) }, Modifier.testTag("mode_style_$label"))
            }

            Text("Decimal places", style = MaterialTheme.typography.titleSmall)
            Text("A fixed number of places (like FIX on a calculator): 5 becomes 5.00 with 2 places.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(calc.places == null, { actions.onCalcSettings { it.copy(places = null) } }, { Text("Auto") }, Modifier.testTag("mode_places_auto"))
                for (n in listOf(0, 1, 2, 3, 4, 6, 8)) FilterChip(calc.places == n, { actions.onCalcSettings { it.copy(places = n) } }, { Text("$n") }, Modifier.testTag("mode_places_$n"))
            }

            Text("Money", style = MaterialTheme.typography.titleSmall)
            Text("Show answers with a currency symbol and thousands separators (₦1,234,500.00). Pair with 2 decimal places for amounts.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Thousands separators", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Switch(calc.grouping, { v -> actions.onCalcSettings { it.copy(grouping = v) } }, Modifier.testTag("mode_grouping"))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(calc.currency.isEmpty(), { actions.onCalcSettings { it.copy(currency = "") } }, { Text("No currency") }, Modifier.testTag("mode_currency_none"))
                for (sym in listOf("₦", "$", "€", "£", "GHS", "KES")) FilterChip(calc.currency == sym, { actions.onCalcSettings { it.copy(currency = sym) } }, { Text(sym) }, Modifier.testTag("mode_currency_$sym"))
            }

            val defined = state.insights.definitions.describe()
            Text("Values and functions on this canvas", style = MaterialTheme.typography.titleSmall)
            if (defined.isEmpty()) {
                Text("Write a = 5 to name a value, or f(x) = 2x + 1 to define a function, then use them in other expressions: 2a + 1 = or f(3) = . The letters x, y and z are for equations and graphs and cannot be named.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("definitions_hint"))
            } else {
                for (line in defined) Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("definition_$line"))
            }

            Text("With these modes", style = MaterialTheme.typography.titleSmall)
            val examples = remember(calc) { previews(calc) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((label, value) in examples) Text("$label  =  $value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("mode_example"))
            }
        }
    }
}

/** A few live examples, computed with the real engine and formatter, so the effect of each mode is visible before the sheet is closed. */
private fun previews(calc: CalcSettings): List<Pair<String, String>> {
    val engine = ExactCalculationEngine(calc.angle)
    fun n(v: Long, d: Long = 1) = Num(Rational.of(v, d))
    fun show(e: Expr): String = (engine.evaluate(e) as? CalcResult.Success)?.let { r ->
        NumberFormatter.format(r.value, calc).let { f -> f.primary + (f.decimal?.let { "  ≈ $it" } ?: "") }
    } ?: "undefined"
    return listOf(
        "sin(30)" to show(Func("sin", listOf(n(30)))),
        "cos(60)" to show(Func("cos", listOf(n(60)))),
        "sin⁻¹(1/2)" to show(Func("asin", listOf(n(1, 2)))),
        "10 ÷ 4" to show(Bin(Op.DIV, n(10), n(4))),
        "1234.5 × 1000" to show(Bin(Op.MUL, n(2469, 2), n(1000))).let { MoneyFormat.decorate(it.substringBefore("  ≈"), calc) },
    )
}
