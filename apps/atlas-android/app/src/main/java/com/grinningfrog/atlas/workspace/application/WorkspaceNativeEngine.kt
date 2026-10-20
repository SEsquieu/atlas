package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject

/** No host bindings are installed. Production callers must use the isolated service supervisor. */
internal object WorkspaceNativeEngine {
    init { System.loadLibrary("atlas_workspace_js") }
    private external fun evaluateNative(files: ByteArray, module: ByteArray, function: ByteArray, input: ByteArray): ByteArray

    fun run(bundle: JSONObject, exportName: String, input: Any?): JSONObject {
        val errors = WorkspaceApplicationContract.validate(bundle)
        require(errors.isEmpty()) { "VALIDATION_FAILED: ${errors.joinToString("; ")}" }
        val export = bundle.getJSONObject("manifest").getJSONObject("exports").getJSONObject(exportName)
        WorkspaceJsonSchema.validate(export.getJSONObject("input_schema"), input)
        val raw = evaluateNative(bundle.getJSONObject("files").toString().toByteArray(Charsets.UTF_8), export.getString("module").toByteArray(Charsets.UTF_8),
            export.getString("function").toByteArray(Charsets.UTF_8), WorkspaceApplicationContract.canonical(input).toByteArray(Charsets.UTF_8))
        require(raw.size <= WorkspaceApplicationContract.MAX_MESSAGE_BYTES + 1024) { "QUOTA_EXCEEDED" }
        val response = JSONObject(raw.toString(Charsets.UTF_8))
        if (response.optBoolean("ok")) {
            val value = response.get("value")
            WorkspaceApplicationContract.checkJson(value)
            WorkspaceJsonSchema.validate(export.getJSONObject("output_schema"), value)
        }
        return response
    }

    fun input(raw: String): Any? {
        // Wrap scalar inputs in an object to apply the same bounded pre-parser.
        return WorkspaceApplicationContract.parseObject("{\"value\":$raw}").get("value")
    }
}
