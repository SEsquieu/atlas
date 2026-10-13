package com.grinningfrog.atlas.dev

import android.app.Application
import com.grinningfrog.atlas.AtlasApplication
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.provider.*
import com.grinningfrog.atlas.runtime.AssembledContext
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DevelopmentProbeTest {
    private fun endpoint() = ProviderEndpoint("plan", "Plan", "https://api.openai.com/v1", "test-model", supportsTools = true, supportsStreaming = true, kind = ProviderKind.CHATGPT_PLAN)
    @Test fun minimalOmitsAllOptionalContextAndProviderTools() {
        val request = ProbeRequestBuilder.build("probe", ProbeOptions(), null, emptyList())
        val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" }, omitEmptyOptionalFields = true)
        val json = backend.requestJson(endpoint(), request, includeHostedSearch = false)
        assertEquals(setOf("model", "store", "stream", "input"), json.keys().asSequence().toSet())
        assertEquals(1, json.getJSONArray("input").length())
        assertTrue(json.toString().contains("Reply exactly OK"))
    }
    @Test fun fullInstalledCatalogSerializesAndSubsetSelectionIsExact() {
        val app = AtlasApplication().apply { attachForTest() }
        val definitions = DevelopmentProbeRunner(app).definitions()
        val context = AssembledContext("Atlas instructions", listOf(InferenceMessage(MessageRole.USER, "History")), emptyList(), 0, 0)
        val options = ProbeOptions(instructions = true, history = true, hostedSearch = true, tools = definitions.map { it.name }.toSet())
        val request = ProbeRequestBuilder.build("probe", options, context, definitions)
        val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "token" }, omitEmptyOptionalFields = true)
        val json = backend.requestJson(endpoint(), request, true)
        val namespace = json.getJSONArray("tools").getJSONObject(1).getJSONArray("tools")
        assertEquals(definitions.size, namespace.length()); assertTrue(definitions.size >= 20)
        for (i in 0 until namespace.length()) assertEquals("object", namespace.getJSONObject(i).getJSONObject("parameters").getString("type"))
        assertEquals("Atlas instructions", json.getString("instructions"))
        val subset = ProbeRequestBuilder.build("probe", options.copy(history = false, tools = setOf("workspace_capabilities")), context, definitions)
        assertEquals(listOf("workspace_capabilities"), subset.tools.map { it.name })
        app.database.close()
    }
    @Test fun successfulModelToolProposalsAreRecordedWithoutExecutingOrMutatingWorkspaces() = runTest {
        val app = AtlasApplication().apply { attachForTest() }
        val before = app.database.listComposableWorkspaces().size
        val fake = object : InferenceBackend {
            override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest) = InferenceResponse(request.requestId, endpoint.id, "", 1,
                toolCalls = listOf(ToolCallProposal("call", "workspace_create", """{"name":"Must not exist"}""")))
        }
        val result = DevelopmentProbeRunner(app, fake).run(endpoint(), ProbeOptions(), null)
        assertEquals(before, app.database.listComposableWorkspaces().size)
        assertEquals(0, app.database.exportSession(result.sessionId).getJSONArray("toolCalls").length())
        assertTrue(result.report.contains("workspace_create")); assertTrue(result.report.contains("\"tools_executed\": 0"))
        assertEquals(SessionStatus.DONE, app.database.loadSession(result.sessionId)!!.status)
        app.database.close()
    }
    @Test fun minimalTransportUsesSameAuthAndDoesNotAdvertiseHostedSearch() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(520).setHeader("content-type", "text/html").setHeader("cf-ray", "abc123-IAD").setBody("<html>edge failure</html>"))
        server.start()
        val events = mutableListOf<JSONObject>()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "secret-token" }, responsesUrl = server.url("/v1/responses").toString(), allowHostedSearch = false, omitEmptyOptionalFields = true, diagnosticSink = { _, data -> events.add(data) })
            val request = ProbeRequestBuilder.build("probe", ProbeOptions(), null, emptyList())
            val failure = runCatching { backend.infer(endpoint(), null, request) }.exceptionOrNull() as InferenceUnavailableException
            assertTrue(failure.outcomeAmbiguous)
            val sent = server.takeRequest(); assertEquals("Bearer secret-token", sent.getHeader("Authorization"))
            val json = JSONObject(sent.body.readUtf8()); assertFalse(json.has("tools")); assertFalse(json.has("include"))
            val diagnostic = events.last(); assertEquals(520, diagnostic.getInt("http_status")); assertEquals("html", diagnostic.getString("body_kind"))
            assertEquals("abc123-IAD", diagnostic.getString("edge_request_id"))
            assertFalse(events.toString().contains("secret-token")); assertFalse(events.toString().contains("edge failure"))
        } finally { server.shutdown() }
    }
    private fun AtlasApplication.attachForTest() {
        val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true; attach.invoke(this, RuntimeEnvironment.getApplication())
    }
}
