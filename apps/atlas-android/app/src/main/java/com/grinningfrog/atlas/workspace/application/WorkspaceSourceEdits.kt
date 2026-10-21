package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject

/** Exact preimages, applied as one stage transaction; never fuzzy patches or silent replacements. */
internal object WorkspaceSourceEdits {
    fun changes(files: JSONObject, replacements: JSONObject, edits: JSONArray?): JSONObject {
        val result = JSONObject(replacements.toString())
        if (edits == null) return result
        require(edits.length() in 1..32) { "SOURCE_EDIT_INVALID: supply 1..32 edits" }
        val paths = mutableSetOf<String>()
        for (i in 0 until edits.length()) {
            val edit = edits.getJSONObject(i)
            WorkspaceApplicationContract.exactKeys(edit, setOf("path", "expected_sha256", "find", "replace"), setOf("path", "expected_sha256", "find", "replace"))
            val path = edit.getString("path")
            require(WorkspaceApplicationContract.validPath(path) && paths.add(path) && !replacements.has(path)) { "SOURCE_EDIT_CONFLICT: edits[$i] repeats or replaces the same path" }
            require(files.has(path) && files.get(path) is String) { "SOURCE_EDIT_CONFLICT: edits[$i] file is missing" }
            val source = files.getString(path)
            require(WorkspaceApplicationContract.hash(source) == edit.getString("expected_sha256")) { "SOURCE_EDIT_CONFLICT: edits[$i] file hash changed; reread pinned source" }
            require(edit.get("find") is String && edit.get("replace") is String) { "SOURCE_EDIT_INVALID: edits[$i] requires string find/replace" }
            val find = edit.getString("find")
            val at = source.indexOf(find)
            require(find.isNotEmpty() && at >= 0 && source.indexOf(find, at + 1) == -1) { "SOURCE_EDIT_CONFLICT: edits[$i] find must match exactly once" }
            result.put(path, source.substring(0, at) + edit.getString("replace") + source.substring(at + find.length))
        }
        return result
    }
}
