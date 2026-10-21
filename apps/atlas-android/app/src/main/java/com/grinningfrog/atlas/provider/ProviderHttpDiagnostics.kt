package com.grinningfrog.atlas.provider

import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Bounded allowlist: never logs auth headers, cookies, request content, or raw HTML/error bodies. */
object ProviderHttpDiagnostics {
    fun request(json: JSONObject): JSONObject {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val tools = json.optJSONArray("tools") ?: JSONArray()
        val functions = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val nested = tool.optJSONArray("tools")
            if (nested != null) for (j in 0 until nested.length()) nested.optJSONObject(j)?.optString("name")?.let(functions::add)
        }
        return JSONObject().put("payload_bytes", body.size).put("payload_sha256", MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })
            .put("requested_reasoning_effort", json.optJSONObject("reasoning")?.opt("effort") ?: "provider_default")
            .put("model", json.optString("model")).put("input_items", json.optJSONArray("input")?.length() ?: 0)
            .put("instruction_characters", json.optString("instructions").length).put("stream", json.optBoolean("stream"))
            .put("tool_types", JSONArray((0 until tools.length()).map { tools.optJSONObject(it)?.optString("type") }))
            .put("function_names", JSONArray(functions)).put("hosted_search", (0 until tools.length()).any { tools.optJSONObject(it)?.optString("type") == "web_search" })
    }
    fun response(response: Response, body: String? = null): JSONObject {
        fun identifier(value: String?) = value?.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9_.:-]+")) }
        val parsed = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val error = parsed?.optJSONObject("error")
        val result = JSONObject().put("http_status", response.code)
            .put("content_type", response.header("content-type")?.substringBefore(';')?.takeIf { it.matches(Regex("[A-Za-z0-9.+/-]{1,100}")) })
            .put("provider_request_id", identifier(response.header("x-request-id") ?: response.header("openai-request-id")))
            .put("edge_request_id", identifier(response.header("cf-ray")))
        if (body != null) {
            result.put("body_kind", when { body.isBlank() -> "empty"; parsed != null -> "json"; body.trimStart().startsWith("<") -> "html"; else -> "text" })
            result.put("sample_bytes", body.toByteArray().size)
            for (key in listOf("code", "type", "param")) identifier(error?.optString(key))?.takeIf { it.isNotBlank() }?.let { result.put("provider_$key", it) }
            result.put("summary", "HTTP ${response.code}; ${result.getString("body_kind")} response. Raw body excluded.")
            result.put("recovery", if (response.code == 401 || response.code == 403) "Check provider authorization details before signing in again." else "Compare a minimal DEV probe with tools/search enabled. A connected account does not prove inference availability.")
        }
        return result
    }
}
