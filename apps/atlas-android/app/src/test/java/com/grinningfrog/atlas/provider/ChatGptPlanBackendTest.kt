package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceDomain
import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.InferenceProvenance
import com.grinningfrog.atlas.model.InferencePurpose
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ProviderKind
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanBackendTest {
    @Test fun requestUsesStrictPlanInferenceContract() {
        val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" })
        val json = backend.requestJson(endpoint(), request().copy(tools = listOf(ToolDefinition("observe", "Observe", "{\"type\":\"object\"}", ToolRisk.READ_ONLY))))
        assertFalse(json.getBoolean("store")); assertTrue(json.getBoolean("stream"))
        assertFalse(json.has("temperature")); assertFalse(json.has("max_output_tokens")); assertFalse(json.has("previous_response_id"))
        assertEquals("web_search", json.getJSONArray("tools").getJSONObject(0).getString("type"))
        val namespace = json.getJSONArray("tools").getJSONObject(1)
        assertEquals("namespace", namespace.getString("type")); assertEquals("atlas", namespace.getString("name"))
        assertEquals("function", namespace.getJSONArray("tools").getJSONObject(0).getString("type"))
    }

    @Test fun hostedSearchIsNormalizedWithVisibleSources() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Current answer.\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_search\",\"model\":\"gpt-test\",\"output\":[" +
                "{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"queries\":[\"current events\"]}}," +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Current answer.\",\"annotations\":[{\"type\":\"url_citation\",\"title\":\"Example News\",\"url\":\"https://example.com/news\"}]}]}]}}\n\n",
        ))
        server.start()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" }, responsesUrl = server.url("/v1/responses").toString())
            val completed = backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList()
                .filterIsInstance<InferenceStreamEvent.Completed>().single().response
            assertEquals("web_search", completed.providerToolUses.single().type)
            assertEquals(listOf("current events"), completed.providerToolUses.single().queries)
            assertEquals("https://example.com/news", completed.citations.single().url)
            assertTrue(completed.text.contains("[Example News](https://example.com/news)"))
        } finally { server.shutdown() }
    }

    @Test fun hostedOpenPagePreservesItsUrl() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Page checked.\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_open\",\"model\":\"gpt-test\",\"output\":[" +
                "{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"open_page\",\"url\":\"https://example.com/ad\"}}]}}\n\n",
        ))
        server.start()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" }, responsesUrl = server.url("/v1/responses").toString())
            val completed = backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList()
                .filterIsInstance<InferenceStreamEvent.Completed>().single().response
            assertEquals("open_page", completed.providerToolUses.single().action)
            assertEquals(listOf("https://example.com/ad"), completed.providerToolUses.single().urls)
        } finally { server.shutdown() }
    }

    @Test fun streamNormalizesTextToolsUsageAndCompletion() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test\"}}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Checking\"}\n\n" +
            "data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"item_1\",\"call_id\":\"call_1\",\"name\":\"observe\",\"arguments\":\"\"}}\n\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"item_id\":\"item_1\",\"delta\":\"{\\\"detail\\\":true}\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"usage\":{\"input_tokens\":10,\"output_tokens\":4,\"total_tokens\":14}}}\n\n"
        ))
        server.start()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "oauth-token" }, responsesUrl = server.url("/v1/responses").toString())
            val events = backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList()
            assertEquals("Bearer oauth-token", server.takeRequest().getHeader("Authorization"))
            val completed = events.filterIsInstance<InferenceStreamEvent.Completed>().single().response
            assertEquals("Checking", completed.text); assertEquals(1, completed.toolCalls.size)
            assertEquals("{\"detail\":true}", completed.toolCalls.single().argumentsJson)
            assertEquals(14, completed.totalTokens); assertEquals("resp_1", completed.providerContinuationId)
        } finally { server.shutdown() }
    }

    @Test fun planLimitIsSafeForRouterFallback() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}"))
        server.start()
        try {
            var reportedCode: String? = null
            val tokens = object : ChatGptTokenProvider {
                override suspend fun accessToken(forceRefresh: Boolean) = "token"
                override fun onProviderFailure(code: String) { reportedCode = code }
            }
            val backend = ChatGptPlanBackend(tokens, responsesUrl = server.url("/v1/responses").toString())
            val error = runCatching { backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList() }.exceptionOrNull()
            assertTrue(error is InferenceUnavailableException)
            val classified = error as InferenceUnavailableException
            assertFalse(classified.outcomeAmbiguous)
            assertEquals("subscription_sharing_usage_limit_exceeded", classified.providerCode)
            assertEquals("subscription_sharing_usage_limit_exceeded", reportedCode)
            assertTrue(classified.message!!.contains("usage limit"))
        } finally { server.shutdown() }
    }

    @Test fun unsupportedHostedSearchRetriesInferenceWithoutDisablingChatGpt() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("{\"error\":{\"code\":\"subscription_sharing_unsupported_capability\"}}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Still available\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_retry\",\"model\":\"gpt-test\"}}\n\n",
        ))
        server.start()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" }, responsesUrl = server.url("/v1/responses").toString())
            val completed = backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList()
                .filterIsInstance<InferenceStreamEvent.Completed>().single().response
            assertEquals("Still available", completed.text)
            assertEquals("unsupported", completed.providerToolUses.single().status)
            assertEquals("web_search", JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals(0, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("tools").length())
        } finally { server.shutdown() }
    }

    @Test fun streamedPlanLimitIsClassifiedAndReported() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n",
        ))
        server.start()
        try {
            var reportedCode: String? = null
            val tokens = object : ChatGptTokenProvider {
                override suspend fun accessToken(forceRefresh: Boolean) = "token"
                override fun onProviderFailure(code: String) { reportedCode = code }
            }
            val backend = ChatGptPlanBackend(tokens, responsesUrl = server.url("/v1/responses").toString())
            val error = runCatching { backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList() }.exceptionOrNull()
            val classified = error as InferenceUnavailableException
            assertEquals("subscription_sharing_usage_limit_exceeded", classified.providerCode)
            assertEquals("subscription_sharing_usage_limit_exceeded", reportedCode)
            assertFalse(classified.outcomeAmbiguous)
        } finally { server.shutdown() }
    }

    @Test fun streamedValidationFailurePreservesMessageWhenCodeIsNull() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":null,\"type\":\"invalid_request_error\",\"param\":\"input\",\"message\":\"No matching function call found for function_call_output.\"}}}\n\n",
        ))
        server.start()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" }, responsesUrl = server.url("/v1/responses").toString())
            val error = runCatching { backend.stream(endpoint(server.url("/v1").toString()), null, request()).toList() }.exceptionOrNull()
                as InferenceUnavailableException
            assertEquals("invalid_request_error", error.providerCode)
            assertTrue(error.message!!.contains("No matching function call"))
            assertTrue(error.message!!.contains("parameter input"))
            assertFalse(error.message!!.contains(": null"))
        } finally { server.shutdown() }
    }

    private fun endpoint(base: String = "https://api.openai.com/v1") = ProviderEndpoint("chatgpt-plan", "ChatGPT plan", base, "gpt-test",
        supportsVision = true, supportsTools = true, supportsStreaming = true, kind = ProviderKind.CHATGPT_PLAN)
    private fun request() = InferenceRequest(sessionId = "session", capability = RouteCapability.FAST, systemPrompt = "You are Atlas", userText = "hello",
        messages = listOf(InferenceMessage(MessageRole.USER, "hello")),
        provenance = InferenceProvenance(InferenceDomain.DIAGNOSTIC, InferencePurpose.CONNECTION_TEST, userInitiated = true))
}
