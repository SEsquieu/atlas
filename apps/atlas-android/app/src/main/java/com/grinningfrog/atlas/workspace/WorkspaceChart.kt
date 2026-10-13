package com.grinningfrog.atlas.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal data class ChartPoint(val id: String, val label: String, val value: Double)
internal data class ChartScale(val minimum: Double, val maximum: Double) {
    // Normalize before subtracting: large finite values must not overflow the range.
    fun fraction(value: Double): Float {
        val magnitude = maxOf(kotlin.math.abs(minimum), kotlin.math.abs(maximum), 1.0)
        return ((value / magnitude - minimum / magnitude) / (maximum / magnitude - minimum / magnitude)).toFloat().coerceIn(0f, 1f)
    }
}
internal object WorkspaceChart {
    const val MAX_POINTS = 50
    fun points(component: JSONObject, records: Map<String, List<JSONObject>>): List<ChartPoint> {
        val embedded = component.optJSONArray("points")
        val rows = if (embedded != null) (0 until embedded.length()).mapNotNull(embedded::optJSONObject)
            else WorkspaceRecordProjection.project(component, records[component.optString("collection")].orEmpty())
        return rows.take(component.optInt("limit", MAX_POINTS).coerceIn(1, MAX_POINTS)).mapIndexedNotNull { index, row ->
            val value = row.opt(if (embedded != null) "value" else component.optString("value_field")) as? Number
            val number = value?.toDouble()?.takeIf { it.isFinite() } ?: return@mapIndexedNotNull null
            ChartPoint(row.optString("_id", "point-$index"), row.optString(if (embedded != null) "label" else component.optString("label_field")), number)
        }
    }
    fun scale(points: List<ChartPoint>): ChartScale {
        val minimum = minOf(0.0, points.minOfOrNull { it.value } ?: 0.0)
        val maximum = maxOf(0.0, points.maxOfOrNull { it.value } ?: 0.0)
        return if (minimum == maximum) ChartScale(0.0, 1.0) else ChartScale(minimum, maximum)
    }
    fun validate(component: JSONObject, collections: JSONObject, errors: MutableList<String>, path: String) {
        val embedded = component.optJSONArray("points")
        if (component.has("points") == component.has("collection")) errors += "$path.chart requires exactly one of points or collection"
        if (component.has("points")) {
            if (embedded == null || embedded.length() > MAX_POINTS) errors += "$path.chart points must be an array of at most $MAX_POINTS points"
            if (embedded != null) for (i in 0 until embedded.length()) {
                val row = embedded.optJSONObject(i)
                if (row == null || row.keys().asSequence().any { it !in setOf("label", "value") } || row.opt("label") !is String || row.optString("label").length !in 1..120 || row.opt("value") !is Number || !(row.opt("value") as? Number)?.toDouble().let { it != null && it.isFinite() }) errors += "$path.chart point[$i] requires a label and finite numeric value"
            }
            if (component.has("label_field") || component.has("value_field") || component.has("sort_by") || component.has("sort_direction")) errors += "$path.chart field bindings and sorting require a collection"
        } else {
            val fields = collections.optJSONObject(component.optString("collection"))?.optJSONObject("fields")
            if (fields == null) errors += "$path.chart requires an existing collection"
            if (fields?.optJSONObject(component.optString("label_field"))?.optString("type") !in setOf("string", "enum")) errors += "$path.chart label_field must be a string or enum field"
            if (fields?.optJSONObject(component.optString("value_field"))?.optString("type") !in setOf("integer", "decimal")) errors += "$path.chart value_field must be numeric"
            if (component.has("sort_by") && fields?.has(component.optString("sort_by")) != true) errors += "$path.chart sort_by must name a collection field"
        }
        if (component.has("sort_direction") && (component.optString("sort_direction") !in setOf("ascending", "descending") || !component.has("sort_by"))) errors += "$path.chart sort_direction requires sort_by and ascending or descending"
        if (component.has("limit") && (component.opt("limit") !is Int || component.optInt("limit") !in 1..MAX_POINTS)) errors += "$path.chart limit must be an integer between 1 and $MAX_POINTS"
    }
    fun diagnostics(component: JSONObject, records: Map<String, List<JSONObject>>): JSONObject {
        val points = points(component, records); val scale = scale(points)
        return JSONObject().put("component_id", component.optString("id")).put("kind", "bar").put("minimum", scale.minimum).put("maximum", scale.maximum)
            .put("points", JSONArray(points.map { JSONObject().put("id", it.id).put("label", it.label).put("value", it.value) }))
    }
}

/** Tap a bar to inspect its exact value. Selection is ephemeral UI state, never an external action. */
@Composable
internal fun WorkspaceBarChart(component: JSONObject, records: Map<String, List<JSONObject>>, modifier: Modifier = Modifier) {
    val points = WorkspaceChart.points(component, records)
    val scale = WorkspaceChart.scale(points)
    var selectedId by remember(component.optString("id"), points) { mutableStateOf<String?>(null) }
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    Card(modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(component.optString("label", "Chart"), style = MaterialTheme.typography.titleMedium)
        component.optString("value_label").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        if (points.isEmpty()) Text(component.optString("empty_text", "No data yet."))
        points.forEach { point ->
            Column(Modifier.fillMaxWidth().semantics { selected = selectedId == point.id }.clickable(role = Role.Button) { selectedId = if (selectedId == point.id) null else point.id }) {
                Text("${point.label}: ${point.value}")
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val zero = scale.fraction(0.0) * size.width
                    val end = scale.fraction(point.value) * size.width
                    drawRect(barColor, Offset(minOf(zero, end), 3.dp.toPx()), Size(kotlin.math.abs(end - zero), size.height - 6.dp.toPx()))
                    drawLine(axisColor, Offset(zero, 0f), Offset(zero, size.height), 1.dp.toPx())
                }
                if (selectedId == point.id) Text("Selected: ${point.label} = ${point.value}", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (points.isNotEmpty()) Text("Scale: ${scale.minimum} to ${scale.maximum}", style = MaterialTheme.typography.labelSmall)
    } }
}
