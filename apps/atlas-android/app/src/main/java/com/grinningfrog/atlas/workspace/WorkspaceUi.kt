package com.grinningfrog.atlas.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.DEFAULT_PERSONAL_WORKSPACE_ID
import com.grinningfrog.atlas.runtime.AtlasMobileRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun WorkspacePage(
    runtime: AtlasMobileRuntime,
    snapshot: RuntimeSnapshot,
    database: AtlasDatabase,
    hasProvider: Boolean,
    actionScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var displayedId by remember(snapshot.session?.activeComposableWorkspaceId) { mutableStateOf(snapshot.session?.activeComposableWorkspaceId) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val workspaces = remember(refresh, snapshot.session?.activeComposableWorkspaceId, snapshot.messages.size) { database.listComposableWorkspaces(includeArchived = true) }
    val workspace = displayedId?.let(database::loadComposableWorkspace)
    val revision = workspace?.let { database.loadLiveWorkspaceRevision(it.id) }
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExport.orEmpty()) }
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
        pendingExport = null
    }

    fun runAction(block: suspend () -> Unit) {
        actionScope.launch {
            runCatching { block() }.onSuccess { refresh++ }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    if (workspace == null || revision == null) {
        WorkspaceLibrary(
            workspaces = workspaces,
            error = error,
            onOpen = { selected ->
                displayedId = selected.id
                snapshot.session?.takeIf { it.status != SessionStatus.DONE }?.let { runAction { runtime.activateComposableWorkspace(selected.id) } }
            },
            onCreate = { name, description ->
                runAction {
                    val created = database.createComposableWorkspace(name, description, realmId = snapshot.session?.workspaceId ?: DEFAULT_PERSONAL_WORKSPACE_ID)
                    displayedId = created.id
                    snapshot.session?.takeIf { it.status != SessionStatus.DONE }?.let { runtime.activateComposableWorkspace(created.id) }
                }
            },
            onArchive = { selected -> runAction {
                if (snapshot.session?.activeComposableWorkspaceId == selected.id) runtime.activateComposableWorkspace(null)
                database.setComposableWorkspaceArchived(selected.id, true)
                if (displayedId == selected.id) displayedId = null
            } },
            onRestore = { selected -> runAction { database.setComposableWorkspaceArchived(selected.id, false) } },
            onDelete = { selected -> runAction { database.deleteComposableWorkspace(selected.id) } },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { displayedId = null }) { Icon(Icons.Default.ArrowBack, "Workspace library") }
            Column(Modifier.weight(1f)) {
                Text(workspace.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("rev ${revision.id.take(8)}${if (snapshot.session?.activeComposableWorkspaceId == workspace.id) " · active for Atlas" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { runAction {
                if (snapshot.session?.activeComposableWorkspaceId == workspace.id) runtime.activateComposableWorkspace(null)
                database.setComposableWorkspaceArchived(workspace.id, true); displayedId = null
            } }) { Icon(Icons.Default.Archive, "Archive workspace") }
            IconButton(onClick = {
                pendingExport = database.exportComposableWorkspace(workspace.id).toString(2)
                exportLauncher.launch("${workspace.name.replace(Regex("[^A-Za-z0-9._-]+"), "-")}.atlas-workspace.json")
            }) { Icon(Icons.Default.IosShare, "Export workspace") }
        }
        HorizontalDivider()
        WorkspaceRenderer(workspace, revision, database, refresh, { refresh++ }, Modifier.weight(1f))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        WorkspaceComposer(runtime, snapshot, hasProvider, actionScope)
    }
}

@Composable
private fun WorkspaceLibrary(
    workspaces: List<ComposableWorkspace>,
    error: String?,
    onOpen: (ComposableWorkspace) -> Unit,
    onCreate: (String, String) -> Unit,
    onArchive: (ComposableWorkspace) -> Unit,
    onRestore: (ComposableWorkspace) -> Unit,
    onDelete: (ComposableWorkspace) -> Unit,
    modifier: Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ComposableWorkspace?>(null) }
    pendingDelete?.let { workspace ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${workspace.name}?") },
            text = { Text("This permanently deletes its definition revisions and records. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(workspace); pendingDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Workspaces", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Persistent tools and artifacts Atlas can build with you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { creating = !creating }) { Icon(Icons.Default.Add, "Create workspace") }
            }
        }
        if (creating) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("New workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, label = { Text("What is it for?") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onCreate(name, description); name = ""; description = ""; creating = false }, enabled = name.isNotBlank()) { Text("Create and open") }
                }
            }
        }
        if (workspaces.isEmpty()) item {
            Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No workspaces yet", style = MaterialTheme.typography.titleLarge)
                Text("Create one here, or start a session and ask Atlas to build one for what you're doing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        items(workspaces, key = { it.id }) { workspace ->
            Card(onClick = { if (workspace.status == WorkspaceStatus.ACTIVE) onOpen(workspace) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(workspace.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (workspace.description.isNotBlank()) Text(workspace.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("revision ${workspace.liveRevisionId.take(8)} · ${workspace.status.name.lowercase()}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (workspace.status == WorkspaceStatus.ACTIVE) {
                        IconButton(onClick = { onArchive(workspace) }) { Icon(Icons.Default.Archive, "Archive ${workspace.name}") }
                    } else {
                        IconButton(onClick = { onRestore(workspace) }) { Icon(Icons.Default.Unarchive, "Restore ${workspace.name}") }
                        IconButton(onClick = { pendingDelete = workspace }) { Icon(Icons.Default.Delete, "Delete ${workspace.name}") }
                    }
                }
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun WorkspaceRenderer(
    workspace: ComposableWorkspace,
    revision: WorkspaceRevision,
    database: AtlasDatabase,
    refresh: Int,
    onChanged: () -> Unit,
    modifier: Modifier,
) {
    val definition = remember(revision.id) { JSONObject(revision.definitionJson) }
    val components = definition.optJSONArray("components") ?: JSONArray()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(definition.optString("title", workspace.name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            definition.optString("description").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        for (index in 0 until components.length()) {
            val component = components.optJSONObject(index) ?: continue
            item(key = component.optString("id", "component-$index")) {
                WorkspaceComponent(workspace.id, component, database, refresh, onChanged)
            }
        }
    }
}

@Composable
private fun WorkspaceComponent(workspaceId: String, component: JSONObject, database: AtlasDatabase, refresh: Int, onChanged: () -> Unit) {
    val id = component.optString("id")
    val label = component.optString("label", component.optString("title", id))
    when (component.optString("type")) {
        "text" -> Text(component.optString("text"), style = MaterialTheme.typography.bodyLarge)
        "status" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp)) { Text(label, fontWeight = FontWeight.Bold); Text(component.optString("value", "Ready")) }
        }
        "metric" -> Card { Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(component.optString("value", "—"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        } }
        "divider" -> HorizontalDivider()
        "notes" -> {
            val records = remember(workspaceId, id, refresh) { database.listWorkspaceRecords(workspaceId, id, 1) }
            var text by remember(workspaceId, id, records.firstOrNull()?.id) { mutableStateOf(records.firstOrNull()?.let { JSONObject(it.dataJson).optString("text") }.orEmpty()) }
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label.ifBlank { "Notes" }, fontWeight = FontWeight.Bold)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = { database.addWorkspaceRecord(workspaceId, id, JSONObject().put("text", text).toString()); onChanged() }) { Text("Save note") }
            } }
        }
        "counter" -> {
            val last = remember(workspaceId, id, refresh) { database.listWorkspaceRecords(workspaceId, id, 1).firstOrNull() }
            var value by remember(workspaceId, id, last?.id) { mutableIntStateOf(last?.let { JSONObject(it.dataJson).optInt("value") } ?: component.optInt("initial", 0)) }
            Card { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Bold); Text(value.toString(), style = MaterialTheme.typography.headlineMedium) }
                OutlinedButton(onClick = { value--; database.addWorkspaceRecord(workspaceId, id, JSONObject().put("value", value).toString()); onChanged() }) { Text("−") }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { value++; database.addWorkspaceRecord(workspaceId, id, JSONObject().put("value", value).toString()); onChanged() }) { Text("+") }
            } }
        }
        "checklist" -> {
            val source = component.optJSONArray("items") ?: JSONArray()
            val checked = remember(workspaceId, id) { mutableStateListOf<Boolean>().apply { repeat(source.length()) { add(false) } } }
            Card { Column(Modifier.padding(14.dp)) {
                Text(label.ifBlank { "Checklist" }, fontWeight = FontWeight.Bold)
                for (index in 0 until source.length()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked[index], { checked[index] = it; database.addWorkspaceRecord(workspaceId, id, JSONObject().put("index", index).put("checked", it).toString()); onChanged() })
                    Text(source.optString(index))
                }
            } }
        }
        "button" -> Button(onClick = {
            val collection = component.optString("collection", id)
            val data = component.optJSONObject("data") ?: JSONObject().put("pressed", true).put("at_ms", System.currentTimeMillis())
            database.addWorkspaceRecord(workspaceId, collection, data.toString()); onChanged()
        }, modifier = Modifier.fillMaxWidth()) { Text(label.ifBlank { "Run" }) }
        "form" -> {
            val fields = component.optJSONArray("fields") ?: JSONArray()
            val values = remember(workspaceId, id) { mutableStateMapOf<String, String>() }
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label.ifBlank { "Form" }, fontWeight = FontWeight.Bold)
                for (index in 0 until fields.length()) {
                    val field = fields.optJSONObject(index) ?: continue
                    val key = field.optString("key")
                    if (key.isNotBlank()) OutlinedTextField(
                        values[key].orEmpty(), { values[key] = it },
                        label = { Text(field.optString("label", key)) }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = {
                    val data = JSONObject(); values.forEach { (key, value) -> data.put(key, value) }
                    database.addWorkspaceRecord(workspaceId, component.optString("collection", id), data.toString()); onChanged()
                }) { Text(component.optString("submit_label", "Save")) }
            } }
        }
        "list", "gallery" -> {
            val collection = component.optString("collection", id)
            val records = remember(workspaceId, collection, refresh) { database.listWorkspaceRecords(workspaceId, collection, component.optInt("limit", 50)) }
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(label.ifBlank { component.optString("type").replaceFirstChar(Char::uppercase) }, fontWeight = FontWeight.Bold)
                if (records.isEmpty()) Text(component.optString("empty_text", "Nothing here yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                records.forEach { record ->
                    val data = JSONObject(record.dataJson)
                    Text(data.optString(component.optString("primary_field", "name"), data.toString()), style = MaterialTheme.typography.bodyMedium)
                    component.optString("secondary_field").takeIf(String::isNotBlank)?.let { field ->
                        data.optString(field).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    HorizontalDivider()
                }
            } }
        }
        "section" -> Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.ifBlank { "Section" }, fontWeight = FontWeight.Bold)
            val children = component.optJSONArray("children") ?: JSONArray()
            for (index in 0 until children.length()) children.optJSONObject(index)?.let { WorkspaceComponent(workspaceId, it, database, refresh, onChanged) }
        } }
        "canvas" -> WorkspaceCanvas(workspaceId, id, label, database, refresh, onChanged)
        else -> Text("Unsupported component: ${component.optString("type")}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun WorkspaceCanvas(workspaceId: String, componentId: String, label: String, database: AtlasDatabase, refresh: Int, onChanged: () -> Unit) {
    val saved = remember(workspaceId, componentId, refresh) { database.listWorkspaceRecords(workspaceId, componentId, 1).firstOrNull() }
    val strokes = remember(workspaceId, componentId, saved?.id) {
        mutableStateListOf<SnapshotStateList<Offset>>().apply {
            saved?.let { record ->
                val serialized = runCatching { JSONObject(record.dataJson).optJSONArray("strokes") }.getOrNull() ?: JSONArray()
                for (strokeIndex in 0 until serialized.length()) {
                    val points = mutableStateListOf<Offset>()
                    val source = serialized.optJSONArray(strokeIndex) ?: continue
                    for (pointIndex in 0 until source.length()) {
                        val point = source.optJSONArray(pointIndex) ?: continue
                        points += Offset(point.optDouble(0).toFloat(), point.optDouble(1).toFloat())
                    }
                    if (points.isNotEmpty()) add(points)
                }
            }
        }
    }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.ifBlank { "Canvas" }, fontWeight = FontWeight.Bold)
            Canvas(
                Modifier.fillMaxWidth().height(320.dp).background(Color.White).pointerInput(workspaceId, componentId) {
                    detectDragGestures(
                        onDragStart = { offset -> strokes += mutableStateListOf(offset) },
                        onDrag = { change, _ -> change.consume(); strokes.lastOrNull()?.add(change.position) },
                    )
                }
            ) {
                strokes.forEach { points ->
                    if (points.size == 1) drawCircle(Color.Black, 3f, points.first())
                    else if (points.size > 1) {
                        val path = Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, Color.Black, style = Stroke(width = 6f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val serialized = JSONArray()
                    strokes.forEach { points -> serialized.put(JSONArray().apply { points.forEach { point -> put(JSONArray().put(point.x).put(point.y)) } }) }
                    database.addWorkspaceRecord(workspaceId, componentId, JSONObject().put("strokes", serialized).toString()); onChanged()
                }, enabled = strokes.isNotEmpty()) { Text("Save drawing") }
                OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun WorkspaceComposer(runtime: AtlasMobileRuntime, snapshot: RuntimeSnapshot, hasProvider: Boolean, actionScope: CoroutineScope) {
    var prompt by remember { mutableStateOf("") }
    val active = snapshot.session?.status == SessionStatus.ACTIVE
    val turnAvailable = snapshot.activeTurn == null || snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        snapshot.messages.lastOrNull { it.role == com.grinningfrog.atlas.model.MessageRole.ASSISTANT && it.kind == com.grinningfrog.atlas.model.MessageKind.DIALOGUE }?.let {
            Text(it.content.take(220), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                prompt, { prompt = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (active) "Ask Atlas about this workspace" else "Start a session to talk to Atlas") },
                enabled = active && turnAvailable && hasProvider,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                trailingIcon = { IconButton(enabled = prompt.isNotBlank() && active && turnAvailable, onClick = { val message = prompt; prompt = ""; actionScope.launch { runtime.ask(message) } }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } },
            )
            IconButton(enabled = active && (turnAvailable || snapshot.phase == RuntimePhase.SPEAKING), onClick = { actionScope.launch { runtime.listenAndAsk() } }) { Icon(Icons.Default.Mic, "Talk to Atlas") }
        }
    }
}
