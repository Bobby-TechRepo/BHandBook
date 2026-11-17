package com.bsoft.bhandbook.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The version and the licence notices (read from assets/licenses/NOTICE.txt). */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val notice = remember { runCatching { context.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() } }.getOrDefault("The licence notices could not be loaded.") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Infinite Canvas Calculator") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("about_text"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $version", style = MaterialTheme.typography.titleSmall)
                Text(notice, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("about_close")) { Text("Close") } },
    )
}
