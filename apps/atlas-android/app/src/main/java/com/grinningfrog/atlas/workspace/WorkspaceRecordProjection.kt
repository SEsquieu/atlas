package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The renderer and diagnostics use exactly the same ordering and limits. Input order is stable insertion order. */
object WorkspaceRecordProjection {
    fun project(component: JSONObject, rows: List<JSONObject>): List<JSONObject> {
        val field = component.optString("sort_by")
        val descending = component.optString("sort_direction", "ascending") == "descending"
        val sorted = if (field.isBlank()) rows else rows.sortedWith { a, b ->
            val av = a.opt(field); val bv = b.opt(field)
            val comparison = when {
                av == null || av == JSONObject.NULL -> if (bv == null || bv == JSONObject.NULL) 0 else 1
                bv == null || bv == JSONObject.NULL -> -1
                av is Number && bv is Number -> av.toDouble().compareTo(bv.toDouble())
                av is Boolean && bv is Boolean -> av.compareTo(bv)
                else -> av.toString().lowercase(Locale.ROOT).compareTo(bv.toString().lowercase(Locale.ROOT))
            }
            // Keep missing values last in both directions; equal values retain insertion order.
            if (descending && av != null && av != JSONObject.NULL && bv != null && bv != JSONObject.NULL) -comparison else comparison
        }
        return sorted.take(component.optInt("limit", 50).coerceIn(1, 200))
    }

    fun diagnostics(root: JSONObject, state: JSONObject, records: Map<String, List<JSONObject>>): JSONObject {
        val viewId = state.optString("_view", root.optString("entry_view"))
        val views = root.optJSONArray("views") ?: JSONArray()
        val view = (0 until views.length()).mapNotNull(views::optJSONObject).firstOrNull { it.optString("id") == viewId }
        val lists = JSONArray()
        val charts = JSONArray()
        val errors = JSONArray()
        fun visit(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val component = array.optJSONObject(i) ?: continue
                fun evaluate(key: String): Any? = component.opt(key)?.let { value ->
                    runCatching { WorkspaceRuntimeV2.evaluate(value, state, records) }.getOrElse { error -> errors.put(JSONObject().put("component_id", component.optString("id")).put("property", key).put("error", error.message)); null }
                }
                val visible = evaluate("visible") as? Boolean ?: true
                if (!visible) continue
                listOf("value", "enabled", "max").forEach { evaluate(it) }
                if (component.optString("type") == "list") {
                    val collection = component.optString("collection")
                    val rows = records[collection].orEmpty()
                    lists.put(JSONObject().put("component_id", component.optString("id")).put("collection", collection)
                        .put("sort_by", component.optString("sort_by", "created_at")).put("sort_direction", component.optString("sort_direction", "ascending"))
                        .put("total_records", rows.size).put("limit", component.optInt("limit", 50).coerceIn(1, 200))
                        .put("ordered_record_ids", JSONArray(project(component, rows).map { it.getString("_id") })))
                }
                if (component.optString("type") == "chart") charts.put(WorkspaceChart.diagnostics(component, records))
                visit(component.optJSONArray("children"))
            }
        }
        visit(view?.optJSONArray("components"))
        return JSONObject().put("view_id", viewId).put("lists", lists).put("charts", charts).put("expression_errors", errors)
    }
}
