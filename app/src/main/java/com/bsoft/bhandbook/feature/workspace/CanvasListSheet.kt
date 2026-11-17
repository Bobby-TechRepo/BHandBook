package com.bsoft.bhandbook.feature.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bsoft.bhandbook.core.codec.CanvasFileCodec
import com.bsoft.bhandbook.domain.repository.CanvasInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bsoft.bhandbook.feature.canvas.CanvasActions
import com.bsoft.bhandbook.feature.canvas.CanvasUiState
import com.bsoft.bhandbook.feature.canvas.Sheet
import java.text.DateFormat
import java.util.Date

/** Saved canvases: open, rename, delete, create. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasListSheet(state: CanvasUiState, actions: CanvasActions) {
    var renaming by remember { mutableStateOf<CanvasInfo?>(null) }
    var deleting by remember { mutableStateOf<CanvasInfo?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the system file picker; the file is read off the main thread and handed over as text (null if unreadable or too large)
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { readBounded(it, CanvasFileCodec.MAX_CHARS) } }.getOrNull() }
            actions.onImportCanvas(text)
        }
    }
    ModalBottomSheet({ actions.onSheet(Sheet.NONE) }, Modifier.testTag("canvas_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Canvases", style = MaterialTheme.typography.titleLarge)
            Button({ actions.onNewCanvas() }, Modifier.fillMaxWidth().testTag("sheet_new_canvas")) { Text("New canvas") }
            OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().testTag("import_canvas")) { Text("Import canvas file") }
            OutlinedTextField(state.search.query, { actions.onSearch(it) }, Modifier.fillMaxWidth().testTag("search_field"), singleLine = true, label = { Text("Search all canvases") })
            if (state.search.query.isNotBlank()) {
                when {
                    state.search.searching -> Text("Searching\u2026", style = MaterialTheme.typography.bodySmall)
                    state.search.hits.isEmpty() -> Text("No matches", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("search_none"))
                    else -> for (hit in state.search.hits) Column(Modifier.fillMaxWidth().clickable { actions.onOpenHit(hit) }.padding(vertical = 6.dp).testTag("hit_${hit.canvasName}_${hit.text}")) {
                        Text(hit.text + (hit.result?.let { "   \u2192   $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                        Text(hit.canvasName + if (hit.expressionId == null) " (canvas)" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.canvases, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.fillMaxWidth().clickable { actions.onOpenCanvas(c.id) }.testTag("canvas_${c.name}")) {
                            Text(c.name + if (c.id == state.canvas?.id) "  (open)" else "", style = MaterialTheme.typography.titleMedium)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.updatedAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ renaming = c }, Modifier.semantics { contentDescription = "Rename ${c.name}" }) { Text("Rename") }
                            TextButton({ actions.onExportCanvas(c.id) }, Modifier.semantics { contentDescription = "Back up ${c.name} to a file" }.testTag("backup_${c.name}")) { Text("Back up") }
                            TextButton({ deleting = c }, Modifier.semantics { contentDescription = "Delete ${c.name}" }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog({ renaming = null }, title = { Text("Rename canvas") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.testTag("rename_field")) },
            confirmButton = { TextButton({ actions.onRenameCanvas(c.id, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { c ->
        AlertDialog({ deleting = null }, title = { Text("Delete “${c.name}”?") }, text = { Text("This permanently deletes the canvas and all of its handwriting.") },
            confirmButton = { TextButton({ actions.onDeleteCanvas(c.id); deleting = null }, Modifier.testTag("confirm_delete_canvas")) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
}

/** Reads at most [maxBytes] bytes as UTF-8 text; null if the stream is longer (a file that large is not a canvas backup and must not fill memory). */
private fun readBounded(input: java.io.InputStream, maxBytes: Int): String? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buffer, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
