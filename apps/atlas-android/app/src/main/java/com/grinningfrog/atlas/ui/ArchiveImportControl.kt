package com.grinningfrog.atlas.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.grinningfrog.atlas.data.ArchiveCodec
import com.grinningfrog.atlas.data.ArchiveImportResult
import com.grinningfrog.atlas.data.ArchivePreview
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArchiveImportControl(database: AtlasDatabase, kind: String, onImported: suspend (ArchiveImportResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ArchivePreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; status = null
            try {
                val parsed = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open archive" }.use(ArchiveCodec::read)
                }
                require(parsed.kind == kind) { "Choose a $kind archive, rather than a ${parsed.kind} archive" }
                preview = parsed
            } catch (error: Exception) { status = "Import failed: ${error.message ?: error.javaClass.simpleName}" }
            finally { busy = false }
        }
    }
    Column {
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain")) }) {
            Text(if (busy) "Working…" else "Import $kind")
        }
        status?.let { Text(it) }
    }
    preview?.let { proposed ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("Import ${proposed.name}?") },
            text = { Text("${proposed.itemCount} ${if (kind == "session") "messages" else "records"}. Creates a separate copy; an identical archive reuses its previous import.\n\n" + proposed.warnings.joinToString("\n\n")) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val result = withContext(Dispatchers.IO) { database.importArchive(proposed) }
                        preview = null
                        status = if (result.duplicate) "Already imported; opened existing copy." else "Imported successfully."
                        try { onImported(result) } catch (error: Exception) { status = "Imported and saved, but could not open: ${error.message}" }
                    } catch (error: Exception) { status = "Import failed; nothing was restored: ${error.message ?: error.javaClass.simpleName}"; preview = null }
                    finally { busy = false }
                }
            }) { Text("Import") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}
