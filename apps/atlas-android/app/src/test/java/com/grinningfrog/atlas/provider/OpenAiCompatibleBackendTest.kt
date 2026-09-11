package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteCapability
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleBackendTest {
    @Test
    fun `disables thinking for an on-device endpoint`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(SUCCESS_BODY).setHeader("Content-Type", "application/json"))
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val endpoint = ProviderEndpoint("local", "Local", server.url("/").toString(), "atlas-local")
            OpenAiCompatibleBackend().infer(endpoint, null, request())

            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(payload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `enables thinking when explicitly requested for an on-device endpoint`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(SUCCESS_BODY).setHeader("Content-Type", "application/json"))
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val endpoint = ProviderEndpoint("local", "Local", server.url("/").toString(), "atlas-local", reasoningEnabled = true)
            OpenAiCompatibleBackend().infer(endpoint, null, request())

            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(payload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `on-device endpoint sends compact prompt and reduced output budget`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(SUCCESS_BODY).setHeader("Content-Type", "application/json"))
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val endpoint = ProviderEndpoint("local", "Local", server.url("/").toString(), "atlas-local")
            val request = request().copy(
                systemPrompt = "You are the replaceable intelligence operating inside Atlas.\nGoal: Converse quickly.",
                responseContract = com.grinningfrog.atlas.model.ResponseContract(com.grinningfrog.atlas.model.ResponseMode.DEFAULT, 35, 65, 3, 115),
            )
            OpenAiCompatibleBackend().infer(endpoint, null, request)

            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(payload.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("You are Atlas on the user's phone."))
            assertTrue(payload.getInt("max_tokens") == 72)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `stream ignores null content frames`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
        ))
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val endpoint = ProviderEndpoint("local", "Local", server.url("/").toString(), "atlas-local", supportsStreaming = true)
            val events = OpenAiCompatibleBackend().stream(endpoint, null, request()).toList()
            val completed = events.filterIsInstance<InferenceStreamEvent.Completed>().single()
            assertTrue(completed.response.text == "Hello")
        } finally {
            server.shutdown()
        }
    }

    private fun request() = InferenceRequest(
        sessionId = "test",
        capability = RouteCapability.FAST,
        systemPrompt = "Reply briefly",
        userText = "Hello",
        provenance = com.grinningfrog.atlas.model.InferenceProvenance(com.grinningfrog.atlas.model.InferenceDomain.DIAGNOSTIC, com.grinningfrog.atlas.model.InferencePurpose.CONNECTION_TEST, userInitiated = true),
    )

    private companion object {
        const val SUCCESS_BODY = """{"choices":[{"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""
    }
}
