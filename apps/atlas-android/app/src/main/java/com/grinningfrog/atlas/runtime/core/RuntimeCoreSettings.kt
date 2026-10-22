package com.grinningfrog.atlas.runtime.core

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.grinningfrog.atlas.AtlasApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publisher trust is supplied through native UI, never by generated workspace content. */
@Composable
fun RuntimeCoreSettings() {
    val context = LocalContext.current
    val store = (context.applicationContext as AtlasApplication).runtimeBundles
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(store.selectionLabel()) }
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                pending = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytesBounded() }
                    val raw = bytes.toString(Charsets.UTF_8)
                    RuntimeBundle.parse(raw, key) // Verify before showing activation prompt.
                    raw
                }
            } catch (e: Exception) { message = e.message }
            finally { busy = false }
        }
    }
    pending?.let { raw ->
        val bundle = RuntimeBundle.parse(raw, key)
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Activate runtime ${bundle.version}?") },
            text = { Text("Publisher signature verified. New tasks will use this runtime. Existing tasks keep their current version. This can change agent guidance and continuation decisions. Source SHA-256: ${bundle.sha256}") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try { withContext(Dispatchers.IO) { store.install(raw, key) }; status = store.selectionLabel(); pending = null; message = "Runtime selected for new tasks" }
                    catch (e: Exception) { message = e.message; pending = null }
                    finally { busy = false }
                }
            }) { Text("Activate") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Experimental agent runtime", style = MaterialTheme.typography.titleMedium)
            Text("Compare portable orchestration with native execution. Changes apply to new tasks; existing tasks remain pinned.")
            Text(status, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { store.selectNative(); status = store.selectionLabel() }) { Text("Native") }
                OutlinedButton(enabled = !busy, onClick = { store.selectBuiltin(); status = store.selectionLabel() }) { Text("Bundled core") }
            }
            OutlinedTextField(value = key, onValueChange = { key = it }, enabled = !busy && pending == null,
                label = { Text("Trusted publisher public key (SPKI base64)") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy && key.isNotBlank(), onClick = { picker.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Import signed runtime") }
            Text("Import only publisher keys you independently trust. Runtime bundles cannot change device permissions or native capabilities.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it) }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= RuntimeBundle.MAX_BYTES) { "Runtime bundle too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun RuntimeBundleStore.selectionLabel(): String {
    val selection = selected()
    if (selection == "native") return "New tasks: Native runtime"
    return runCatching { "New tasks: ${resolve(selection).version} (${selection.take(12)})" }
        .getOrDefault("Selected runtime unavailable. Choose Native to recover new tasks.")
}
