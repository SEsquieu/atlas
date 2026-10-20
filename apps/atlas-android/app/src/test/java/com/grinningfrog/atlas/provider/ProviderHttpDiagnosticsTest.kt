package com.grinningfrog.atlas.provider

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderHttpDiagnosticsTest {
    @Test fun metadataOmitsRequestContentAndAuthentication() {
        val json = JSONObject("""{"model":"test","instructions":"private instruction","input":[{"content":"private history"}],"tools":[{"type":"web_search"}]}""")
        val result = ProviderHttpDiagnostics.request(json)
        assertTrue(result.getBoolean("hosted_search")); assertEquals(64, result.getString("payload_sha256").length)
        assertFalse(result.toString().contains("private")); assertEquals(1, result.getInt("input_items"))
    }
    @Test fun failureDetailsAreBoundedAllowlistedMetadataNotRawBodyOrCookies() {
        val response = Response.Builder().request(Request.Builder().url("https://example.org").build()).protocol(Protocol.HTTP_1_1).code(520).message("Error")
            .header("Content-Type", "text/html; charset=utf-8").header("Set-Cookie", "secret-cookie").header("x-request-id", "req_123").header("cf-ray", "abc123-IAD").build()
        val result = ProviderHttpDiagnostics.response(response, "<html>Bearer secret-token private echoed input</html>")
        assertEquals("text/html", result.getString("content_type")); assertEquals("req_123", result.getString("provider_request_id"))
        assertEquals("html", result.getString("body_kind"))
        assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("private"))
    }
}
