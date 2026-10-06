package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject

enum class WorkspaceStatus { ACTIVE, ARCHIVED }

data class ComposableWorkspace(
    val id: String,
    val realmId: String,
    val name: String,
    val description: String,
    val status: WorkspaceStatus,
    val liveRevisionId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class WorkspaceRevision(
    val id: String,
    val workspaceId: String,
    val parentRevisionId: String?,
    val definitionJson: String,
    val contentHash: String,
    val authorType: String,
    val sourceSessionId: String?,
    val sourceTurnId: String?,
    val purpose: String,
    val createdAtMs: Long,
)

data class WorkspaceRecord(
    val id: String,
    val workspaceId: String,
    val collection: String,
    val dataJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class WorkspaceValidationResult(val valid: Boolean, val errors: List<String>)

object WorkspaceDefinitionValidator {
    const val FORMAT = "atlas.workspace.v1"
    private const val MAX_DEFINITION_BYTES = 32 * 1024
    private const val MAX_COMPONENTS = 80
    private const val MAX_COMPONENT_DEPTH = 8
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val componentTypes = setOf(
        "text", "status", "metric", "notes", "checklist", "counter", "form",
        "list", "gallery", "canvas", "button", "divider", "section",
    )

    fun validate(raw: String): WorkspaceValidationResult {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed?.optString("format") == WorkspaceRuntimeV2.FORMAT) return WorkspaceRuntimeV2.validate(parsed)
        val errors = mutableListOf<String>()
        if (raw.toByteArray().size > MAX_DEFINITION_BYTES) errors += "Definition exceeds 32 KiB"
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return WorkspaceValidationResult(false, listOf("Definition is not valid JSON"))
        }
        if (root.optString("format") != FORMAT) errors += "format must be $FORMAT"
        if (root.optString("title").isBlank()) errors += "title is required"
        val components = root.optJSONArray("components")
        if (components == null) errors += "components must be an array"
        else {
            var count = 0
            val ids = mutableSetOf<String>()
            fun visit(array: JSONArray, depth: Int) {
                if (depth > MAX_COMPONENT_DEPTH) {
                    errors += "Component nesting exceeds $MAX_COMPONENT_DEPTH levels"
                    return
                }
                for (index in 0 until array.length()) {
                    count++
                    val component = array.optJSONObject(index)
                    if (component == null) {
                        errors += "components[$index] must be an object"
                        continue
                    }
                    val type = component.optString("type")
                    if (type !in componentTypes) errors += "Unsupported component type: ${type.ifBlank { "<blank>" }}"
                    val id = component.optString("id")
                    if (id.isBlank()) errors += "Every component requires an id"
                    else if (!id.matches(identifier)) errors += "Invalid component id: $id"
                    else if (!ids.add(id)) errors += "Duplicate component id: $id"
                    component.optString("collection").takeIf(String::isNotBlank)?.let { collection ->
                        if (!collection.matches(identifier)) errors += "Invalid collection name: $collection"
                    }
                    component.optJSONArray("children")?.let { visit(it, depth + 1) }
                }
            }
            visit(components, 1)
            if (count > MAX_COMPONENTS) errors += "Definition exceeds $MAX_COMPONENTS components"
        }
        return WorkspaceValidationResult(errors.isEmpty(), errors.distinct())
    }

    fun starter(name: String, description: String = ""): String = JSONObject().apply {
        put("format", FORMAT)
        put("title", name)
        put("description", description)
        put("components", JSONArray().put(JSONObject().apply {
            put("id", "welcome")
            put("type", "text")
            put("text", description.ifBlank { "This workspace is ready for Atlas to shape." })
        }))
    }.toString()
}
