package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject

/** Narrow edits replace named subtrees on a copy. Missing/ambiguous targets fail before any write. */
object WorkspaceDefinitionPatch {
    fun apply(definition: JSONObject, changes: JSONArray): JSONObject {
        require(changes.length() in 1..32) { "Patch requires 1..32 changes" }
        val root = JSONObject(definition.toString())
        require(root.optString("format") == WorkspaceRuntimeV2.FORMAT) { "Patches require workspace v2" }
        val targets = mutableSetOf<String>()
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            val kind = change.getString("kind"); val id = change.getString("id")
            require(targets.add("$kind:$id")) { "Duplicate patch target: $id" }
            val replacement = JSONObject(change.getJSONObject("replacement").toString())
            when (kind) {
                "action" -> { val actions = root.getJSONObject("actions"); require(actions.has(id)) { "Unknown action: $id" }; actions.put(id, replacement) }
                "component" -> {
                    require(replacement.optString("id") == id) { "Component replacement must retain id: $id" }
                    val matches = mutableListOf<Pair<JSONArray, Int>>()
                    fun find(items: JSONArray, depth: Int) {
                        require(depth <= 12) { "Component nesting exceeds 12" }
                        for (j in 0 until items.length()) items.optJSONObject(j)?.let { component ->
                            if (component.optString("id") == id) matches.add(items to j)
                            component.optJSONArray("children")?.let { find(it, depth + 1) }
                        }
                    }
                    val views = root.getJSONArray("views")
                    for (j in 0 until views.length()) find(views.getJSONObject(j).getJSONArray("components"), 0)
                    require(matches.size == 1) { "Component $id must identify exactly one target (found ${matches.size})" }
                    matches.single().let { (array, index) -> array.put(index, replacement) }
                }
                else -> error("Unsupported patch kind: $kind")
            }
        }
        require(root.toString().toByteArray().size <= WorkspaceRuntimeV2.MAX_DEFINITION_BYTES) { "Patched definition exceeds 256 KiB" }
        return root
    }
}
