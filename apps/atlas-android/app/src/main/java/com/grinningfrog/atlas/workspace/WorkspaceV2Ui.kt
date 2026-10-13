package com.grinningfrog.atlas.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun WorkspaceV2Renderer(
    workspace: ComposableWorkspace,
    definition: JSONObject,
    database: AtlasDatabase,
    refresh: Any,
    onChanged: () -> Unit,
    modifier: Modifier,
) {
    var runtimeError by remember(workspace.id) { mutableStateOf<String?>(null) }
    val state = remember(workspace.id, refresh) { database.loadWorkspaceRuntimeState(workspace.id, definition) }
    val viewId = state.optString("_view", definition.optString("entry_view"))
    val views = definition.getJSONArray("views")
    val view = (0 until views.length()).mapNotNull(views::optJSONObject).firstOrNull { it.optString("id") == viewId } ?: views.getJSONObject(0)
    val records = remember(workspace.id, refresh) {
        buildMap<String, List<JSONObject>> {
            definition.optJSONObject("collections")?.keys()?.forEach { collection ->
                put(collection, database.listWorkspaceRecords(workspace.id, collection, 5_000).map { JSONObject(it.dataJson).put("_id", it.id) })
            }
        }
    }
    var undoToken by remember(workspace.id, refresh) { mutableStateOf(database.pendingWorkspaceUndo(workspace.id).optJSONObject(0)?.optString("undo_token")) }
    fun rejected(action: String, error: Throwable, recordId: String? = null) {
        runtimeError = error.message ?: "The workspace action could not be completed."
        database.recordWorkspaceEvent("workspace.action_rejected", workspace.id, JSONObject().put("revision_id", workspace.liveRevisionId).put("action", action).put("record_id", recordId).put("source", "UI").put("error", runtimeError))
        onChanged()
    }
    fun execute(action: String, collection: String? = null, row: JSONObject? = null) {
        runCatching {
            require(action.isNotBlank()) { "An action is required" }
            database.applyWorkspaceAction(workspace.id, definition, action, rowCollection = collection, rowId = row?.getString("_id"), expectedData = row?.let { JSONObject(it.toString()).apply { remove("_id") } })
        }.onSuccess { result ->
            result.undoTokens.lastOrNull()?.let { undoToken = it }
            runtimeError = null
            onChanged()
        }.onFailure {
            rejected(action, it, row?.optString("_id"))
        }
    }
    fun actionEnabled(actionId: String, row: JSONObject?): Boolean {
        val action = definition.optJSONObject("actions")?.optJSONObject(actionId) ?: return false
        return !action.has("enabled_if") || (runCatching { WorkspaceRuntimeV2.evaluate(action.opt("enabled_if"), state, records, row = row) }.getOrNull() as? Boolean == true)
    }
    val hostView = LocalView.current
    val graphicsLayer = rememberGraphicsLayer()
    val owner = remember(workspace.id, workspace.liveRevisionId) { Any() }
    val listState = rememberLazyListState()
    val renderScope = rememberCoroutineScope()
    val projection = runCatching { WorkspaceRecordProjection.diagnostics(definition, state, records) }
    val expressionError = projection.getOrNull()?.optJSONArray("expression_errors")?.takeIf { it.length() > 0 }?.toString()
    val surfaceColor = MaterialTheme.colorScheme.background
    DisposableEffect(owner, graphicsLayer, hostView) {
        WorkspaceRenderSurface.register(owner, workspace.id, workspace.liveRevisionId) {
            check(hostView.isShown && hostView.windowVisibility == android.view.View.VISIBLE && hostView.hasWindowFocus()) { "Atlas workspace must be visible in the foreground to capture" }
            check(graphicsLayer.size.width > 0 && graphicsLayer.size.height > 0) { "Workspace has not drawn yet" }
            check(graphicsLayer.size.width.toLong() * graphicsLayer.size.height <= 8_000_000) { "Workspace viewport exceeds capture budget" }
            graphicsLayer.toImageBitmap().asAndroidBitmap()
        }
        onDispose { WorkspaceRenderSurface.unregister(owner) }
    }
    val captureModifier = modifier.fillMaxWidth().drawWithContent {
        graphicsLayer.record { this@drawWithContent.drawContent() }
        drawLayer(graphicsLayer)
        val data = JSONObject().put("workspace_id", workspace.id).put("revision_id", workspace.liveRevisionId).put("drawn_at_ms", System.currentTimeMillis())
            .put("scope", "visible_workspace_viewport").put("render_epoch", refresh.toString()).put("scroll_index", listState.firstVisibleItemIndex).put("scroll_offset", listState.firstVisibleItemScrollOffset).put("width", size.width.toInt()).put("height", size.height.toInt())
            .put("projection", projection.getOrNull()).put("error", runtimeError ?: expressionError ?: projection.exceptionOrNull()?.message)
            .put("visible_component_ids", JSONArray(listState.layoutInfo.visibleItemsInfo.map { it.key }.filterIsInstance<String>()))
        if (WorkspaceRenderSurface.drawn(owner, data)) renderScope.launch(Dispatchers.IO) {
            database.recordWorkspaceEvent(if (data.optString("error").isBlank()) "workspace.rendered" else "workspace.render_failed", workspace.id, data)
        }
    }.background(surfaceColor)
    LazyColumn(captureModifier, state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(view.optString("title", definition.optString("title")), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            view.optString("description").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        runtimeError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        undoToken?.let { token -> item {
            TextButton(onClick = {
                runCatching { database.restoreWorkspaceRecord(workspace.id, workspace.liveRevisionId, token) }
                    .onSuccess { undoToken = null; runtimeError = null; onChanged() }
                    .onFailure { rejected("restore_record", it) }
            }) { Text("Undo removal") }
        } }
        val components = view.optJSONArray("components") ?: JSONArray()
        for (index in 0 until components.length()) {
            val component = components.optJSONObject(index) ?: continue
            item(key = component.optString("id", "v2-$index")) { V2Component(component, state, records, { execute(it) }, ::actionEnabled, { action, collection, row -> execute(action, collection, row) }, { key, value ->
                runCatching { database.setWorkspaceStateValue(workspace.id, definition, key, value) }
                    .onSuccess { runtimeError = null; onChanged() }
                    .onFailure { rejected("set_state:$key", it) }
            }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun V2Component(component: JSONObject, state: JSONObject, records: Map<String, List<JSONObject>>, execute: (String) -> Unit, actionEnabled: (String, JSONObject?) -> Boolean, executeRow: (String, String, JSONObject) -> Unit, setState: (String, Any?) -> Unit, modifier: Modifier = Modifier) {
    fun value(key: String, fallback: Any? = ""): Any? = component.opt(key)?.let { runCatching { WorkspaceRuntimeV2.evaluate(it, state, records) }.getOrElse { error -> "Render error: ${error.message}" } } ?: fallback
    if (!(value("visible", true) as? Boolean ?: true)) return
    val label = component.optString("label", component.optString("id"))
    val textStyle = when (component.optString("text_size", "normal")) {
        "small" -> MaterialTheme.typography.bodySmall
        "large" -> MaterialTheme.typography.bodyLarge
        else -> MaterialTheme.typography.bodyMedium
    }
    val textWeight = if (component.optBoolean("bold")) FontWeight.Bold else FontWeight.Normal
    when (component.optString("type")) {
        "text" -> Text(value("value", component.optString("text")).toString(), style = textStyle, fontWeight = textWeight, modifier = modifier)
        "status", "metric" -> Card(modifier) { Column(Modifier.padding(if (component.optBoolean("compact")) 8.dp else 14.dp)) {
            if (component.optBoolean("show_label", true)) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value("value", "—").toString(), style = if (component.has("text_size")) textStyle else MaterialTheme.typography.headlineSmall, fontWeight = if (component.has("bold")) textWeight else FontWeight.Bold)
        } }
        "button" -> {
            val action = component.optString("action")
            Button(onClick = { execute(action) }, enabled = (value("enabled", true) as? Boolean ?: true) && actionEnabled(action, null), modifier = modifier.fillMaxWidth()) { Text(label) }
        }
        "input" -> {
            val binding = component.optString("binding")
            var editing by remember(binding, state.opt(binding)) { mutableStateOf(state.opt(binding)?.toString().orEmpty()) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(editing, { editing = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { setState(binding, editing); component.optString("action").takeIf(String::isNotBlank)?.let(execute) }) { Text(component.optString("submit_label", "Save")) }
            }
        }
        "toggle" -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(state.optBoolean(component.optString("binding")), { execute(component.optString("action")) })
        }
        "progress" -> {
            val current = (value("value", 0) as? Number)?.toFloat() ?: 0f
            val maximum = (value("max", 100) as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 100f
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label); LinearProgressIndicator({ (current / maximum).coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) }
        }
        "list" -> {
            val items = records[component.optString("collection")].orEmpty()
            Card(modifier) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, fontWeight = FontWeight.Bold)
                if (items.isEmpty()) Text(component.optString("empty_text", "Nothing here yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkspaceRecordProjection.project(component, items).forEach { row ->
                    val rowAction = component.optString("row_action")
                    val checkedField = component.optString("checked_field")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (checkedField.isNotBlank()) Checkbox(
                            checked = row.optBoolean(checkedField),
                            onCheckedChange = { executeRow(rowAction, component.getString("collection"), row) },
                            enabled = actionEnabled(rowAction, row),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(row.optString(component.optString("primary_field", "name"), row.toString()))
                            component.optString("secondary_field").takeIf(String::isNotBlank)?.let { Text(row.optString(it), style = MaterialTheme.typography.bodySmall) }
                        }
                        component.optString("remove_action").takeIf(String::isNotBlank)?.let { removeAction ->
                            TextButton(onClick = { executeRow(removeAction, component.getString("collection"), row) }, enabled = actionEnabled(removeAction, row)) { Text(component.optString("remove_label", "Remove")) }
                        }
                        if (rowAction.isNotBlank() && checkedField.isBlank()) TextButton(
                            onClick = { executeRow(rowAction, component.getString("collection"), row) },
                            enabled = actionEnabled(rowAction, row),
                        ) { Text(component.optString("row_action_label", "Edit")) }
                    }
                }
            } }
        }
        "section" -> Card(modifier) { Column(Modifier.padding(if (component.optBoolean("compact")) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (component.optBoolean("compact")) 6.dp else 10.dp)) {
            if (component.optBoolean("show_label", component.has("label")) && label.isNotBlank()) Text(label, fontWeight = FontWeight.Bold)
            val children = component.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { V2Component(it, state, records, execute, actionEnabled, executeRow, setState) }
        } }
        "row" -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val children = component.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { V2Component(it, state, records, execute, actionEnabled, executeRow, setState, Modifier.weight(1f)) }
        }
        "grid" -> {
            val columns = component.optInt("columns", 2).coerceIn(1, 6)
            FlowRow(modifier.fillMaxWidth(), maxItemsInEachRow = columns, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val children = component.optJSONArray("children") ?: JSONArray()
                for (i in 0 until children.length()) children.optJSONObject(i)?.let {
                    V2Component(it, state, records, execute, actionEnabled, executeRow, setState, Modifier.weight(1f))
                }
            }
        }
        "divider" -> HorizontalDivider(modifier)
    }
}
