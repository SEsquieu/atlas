package com.grinningfrog.atlas.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grinningfrog.atlas.data.AtlasDatabase
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun WorkspaceV2Renderer(
    workspace: ComposableWorkspace,
    definition: JSONObject,
    database: AtlasDatabase,
    refresh: Int,
    onChanged: () -> Unit,
    modifier: Modifier,
) {
    val state = remember(workspace.id, refresh) { database.loadWorkspaceRuntimeState(workspace.id, definition) }
    val viewId = state.optString("_view", definition.optString("entry_view"))
    val views = definition.getJSONArray("views")
    val view = (0 until views.length()).mapNotNull(views::optJSONObject).firstOrNull { it.optString("id") == viewId } ?: views.getJSONObject(0)
    val records = remember(workspace.id, refresh) {
        buildMap<String, List<JSONObject>> {
            definition.optJSONObject("collections")?.keys()?.forEach { collection ->
                put(collection, database.listWorkspaceRecords(workspace.id, collection, 200).map { JSONObject(it.dataJson) })
            }
        }
    }
    fun execute(action: String) {
        if (action.isNotBlank()) database.applyWorkspaceAction(workspace.id, definition, action)
        onChanged()
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(view.optString("title", definition.optString("title")), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            view.optString("description").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val components = view.optJSONArray("components") ?: JSONArray()
        for (index in 0 until components.length()) {
            val component = components.optJSONObject(index) ?: continue
            item(key = component.optString("id", "v2-$index")) { V2Component(component, state, records, ::execute) { key, value ->
                database.setWorkspaceStateValue(workspace.id, definition, key, value); onChanged()
            } }
        }
    }
}

@Composable
private fun V2Component(component: JSONObject, state: JSONObject, records: Map<String, List<JSONObject>>, execute: (String) -> Unit, setState: (String, Any?) -> Unit) {
    fun value(key: String, fallback: Any? = ""): Any? = component.opt(key)?.let { WorkspaceRuntimeV2.evaluate(it, state, records) } ?: fallback
    if (!(value("visible", true) as? Boolean ?: true)) return
    val label = component.optString("label", component.optString("id"))
    when (component.optString("type")) {
        "text" -> Text(value("value", component.optString("text")).toString(), style = MaterialTheme.typography.bodyLarge)
        "status", "metric" -> Card { Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value("value", "—").toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        } }
        "button" -> Button(onClick = { execute(component.optString("action")) }, enabled = value("enabled", true) as? Boolean ?: true, modifier = Modifier.fillMaxWidth()) { Text(label) }
        "input" -> {
            val binding = component.optString("binding")
            var editing by remember(binding, state.opt(binding)) { mutableStateOf(state.opt(binding)?.toString().orEmpty()) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(editing, { editing = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { setState(binding, editing); component.optString("action").takeIf(String::isNotBlank)?.let(execute) }) { Text(component.optString("submit_label", "Save")) }
            }
        }
        "toggle" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(state.optBoolean(component.optString("binding")), { execute(component.optString("action")) })
        }
        "progress" -> {
            val current = (value("value", 0) as? Number)?.toFloat() ?: 0f
            val maximum = (component.opt("max") as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 100f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label); LinearProgressIndicator({ (current / maximum).coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) }
        }
        "list" -> {
            val items = records[component.optString("collection")].orEmpty()
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, fontWeight = FontWeight.Bold)
                if (items.isEmpty()) Text(component.optString("empty_text", "Nothing here yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.take(component.optInt("limit", 50)).forEach { row ->
                    Text(row.optString(component.optString("primary_field", "name"), row.toString()))
                    component.optString("secondary_field").takeIf(String::isNotBlank)?.let { Text(row.optString(it), style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
        "section" -> Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (label.isNotBlank()) Text(label, fontWeight = FontWeight.Bold)
            val children = component.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { V2Component(it, state, records, execute, setState) }
        } }
        "divider" -> HorizontalDivider()
    }
}
