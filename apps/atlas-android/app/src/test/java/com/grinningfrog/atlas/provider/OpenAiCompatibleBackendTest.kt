package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiCompatibleBackendTest {
    @Test fun sendsPortableConversationAndParsesToolProposal() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{
          "id":"response-1",
          "choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"call-1","type":"function","function":{"name":"get_device_state","arguments":"{}"}}
          ]}}]
        }"""))
        server.start()
        try {
            val request = InferenceRequest(
                sessionId = "session", turnId = "turn", capability = RouteCapability.FAST,
                systemPrompt = "Atlas owns the session.", userText = "How is the phone?",
                messages = listOf(
                    InferenceMessage(MessageRole.USER, "Remember cable A?"),
                    InferenceMessage(MessageRole.ASSISTANT, "Yes."),
                    InferenceMessage(MessageRole.USER, "How is the phone?"),
                ),
                tools = listOf(ToolDefinition("get_device_state", "Read health", "{\"type\":\"object\",\"properties\":{}}", ToolRisk.READ_ONLY)),
            )
            val endpoint = ProviderEndpoint("test", "test", server.url("/v1").toString(), "model", supportsTools = true)

            val response = OpenAiCompatibleBackend().infer(endpoint, null, request)

            assertEquals("response-1", response.providerContinuationId)
            assertEquals("tool_calls", response.finishReason)
            assertEquals(listOf(ToolCallProposal("call-1", "get_device_state", "{}")), response.toolCalls)
            val recorded = server.takeRequest()
            assertEquals("true", recorded.getHeader("X-Atlas-Requires-Tools"))
            assertNull(recorded.getHeader("Authorization"))
            val json = JSONObject(recorded.body.readUtf8())
            assertEquals(listOf("system", "user", "assistant", "user"), (0 until json.getJSONArray("messages").length()).map { json.getJSONArray("messages").getJSONObject(it).getString("role") })
            assertEquals("get_device_state", json.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun preservesAssistantToolCallAndToolResultOnContinuation() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"response-2","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Battery is healthy."}}]}"""))
        server.start()
        try {
            val proposal = ToolCallProposal("call-1", "get_device_state", "{}")
            val request = InferenceRequest(
                sessionId = "session", capability = RouteCapability.FAST, systemPrompt = "system", userText = "",
                messages = listOf(
                    InferenceMessage(MessageRole.USER, "How is the phone?"),
                    InferenceMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(proposal)),
                    InferenceMessage(MessageRole.TOOL, "{\"ok\":true}", toolCallId = "call-1"),
                ),
            )
            val endpoint = ProviderEndpoint("test", "test", server.url("/v1").toString(), "model")

            val response = OpenAiCompatibleBackend().infer(endpoint, null, request)

            assertEquals("Battery is healthy.", response.text)
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals("call-1", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("call-1", messages.getJSONObject(3).getString("tool_call_id"))
        } finally {
            server.shutdown()
        }
    }
}
