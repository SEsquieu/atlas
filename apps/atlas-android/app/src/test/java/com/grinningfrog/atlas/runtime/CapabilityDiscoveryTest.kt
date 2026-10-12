package com.grinningfrog.atlas.runtime

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.grinningfrog.atlas.provider.ChatGptPlanBackend
import com.grinningfrog.atlas.provider.ChatGptTokenProvider
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapabilityDiscoveryTest {
    private lateinit var db: AtlasDatabase
    private lateinit var session: AtlasSession
    private lateinit var turn: AgentTurn
    private var executions = 0
    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("atlas.db")
        db = AtlasDatabase(RuntimeEnvironment.getApplication())
        session = db.createSession("Discovery test", "Analyze an unsupported file format")
        db.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        turn = db.createTurn(session.id, "user")
    }
    @After fun cleanup() { db.close() }
    private fun adapter(name: String, risk: ToolRisk, research: Boolean = false, fail: Boolean = false) = object : AtlasToolAdapter {
        override val supportsCapabilityDiscovery = research
        override val definition = ToolDefinition(name, "Test executor", "{}", risk = risk)
        override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
            executions++
            if (fail) error("Research unavailable")
            return ToolExecutionResult("""{"ok":true,"results":[{"url":"https://example.org/docs","description":"Format conversion documented"}]}""")
        }
    }
    private fun harness(vararg adapters: AtlasToolAdapter) = ToolHarness(db, { _, _ -> error("Unexpected capture") }, { DeviceHealth() }, additionalTools = adapters.toList())
    private fun call(name: String, args: String = "{}", turnId: String = turn.id) = AtlasToolCall(UUID.randomUUID().toString(), session.id, turnId, name, args, ToolCallStatus.PROPOSED, ToolRisk.SESSION_WRITE, false, UUID.randomUUID().toString(), createdAtMs = 0, updatedAtMs = 0)
    private val blocked = """{"stage":"blocked","objective":"Analyze this unsupported format","missing_capability":"Format conversion"}"""
    private val candidate = """{"stage":"candidate","candidate":"Example converter","sources":["https://example.org/docs"],"assessment":"unverified","applicability":"Documentation describes conversion of the requested format","requirements":"Availability, access and cost remain unverified"}"""
    @Test fun recordsFullDiscoveryTraceWithoutInvokingCandidate() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        h.execute(call("web_search", """{"query":"format conversion services documentation"}"""))
        val result = h.execute(call(CapabilityDiscovery.TOOL, candidate))
        assertEquals(1, executions)
        assertFalse(JSONObject(result.resultJson).getBoolean("authorization_granted"))
        assertFalse(JSONObject(result.resultJson).getBoolean("candidate_invoked"))
        val types = db.loadRecentEvents(session.id).map { it.type }.filter { it.startsWith("capability.") }
        assertEquals(listOf("capability.blocked", "capability.missing_identified", "capability.search_attempted", "capability.search_completed", "capability.candidate_evaluated", "capability.candidate_proposed"), types)
    }
    @Test fun executionRechecksDiscoveryEvenForPreviouslyPreparedWrite() = runTest {
        val h = harness(adapter("mutate", ToolRisk.SESSION_WRITE))
        assertTrue(h.evaluate(session, ToolCallProposal("proposal", "mutate", "{}")).allowed)
        val prepared = call("mutate")
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertFalse(h.evaluate(session, ToolCallProposal("proposal", "mutate", "{}")).allowed)
        assertTrue(runCatching { h.execute(prepared) }.isFailure)
        assertEquals(0, executions)
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "capability.discovery_denied" })
    }
    @Test fun readOnlyRiskAloneDoesNotAuthorizeAResearchExecutor() = runTest {
        val h = harness(adapter("untrusted_read", ToolRisk.READ_ONLY))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertTrue(runCatching { h.execute(call("untrusted_read")) }.isFailure)
        assertEquals(0, executions)
    }
    @Test fun inventedSourcesAndCandidateBeforeBlockerAreRejected() = runTest {
        val h = harness()
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate)) }.isFailure)
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate)) }.isFailure)
        assertFalse(db.loadRecentEvents(session.id).any { it.type == "capability.candidate_proposed" })
    }
    @Test fun failedSearchIsObservableAndCannotSupportCandidate() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true, true))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertTrue(runCatching { h.execute(call("web_search")) }.isFailure)
        val event = db.loadRecentEvents(session.id).last { it.type == "capability.search_completed" }
        assertFalse(JSONObject(event.dataJson).getBoolean("ok"))
        h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"no_candidate","reason":"Research unavailable"}"""))
        assertEquals(1, executions)
    }
    @Test fun hostedSearchEvidenceIsLabeledAsObservedAfterResponse() = runTest {
        val h = harness()
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        CapabilityDiscovery(db).hostedResearch(session.id, turn.id, InferenceResponse("request", "provider", "", 1,
            citations = listOf(ProviderCitation("Docs", "https://example.org/docs")),
            providerToolUses = listOf(ProviderToolUse("web_search", status = "completed", queries = listOf("conversion documentation"), readOnlyResearch = true))))
        h.execute(call(CapabilityDiscovery.TOOL, candidate))
        val attempt = db.loadRecentEvents(session.id).last { it.type == "capability.search_attempted" }
        assertTrue(JSONObject(attempt.dataJson).getBoolean("observed_after_response"))
        assertEquals(0, executions)
    }
    @Test fun discoveryModeDoesNotGrantPermissionsOrLeakIntoAnotherTurn() = runTest {
        val h = harness(adapter("mutate", ToolRisk.SESSION_WRITE))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertEquals(session.permissions, db.loadSession(session.id)!!.permissions)
        db.updateTurn(turn.id, TurnStatus.COMPLETED)
        val next = db.createTurn(session.id, "user")
        assertFalse(CapabilityDiscovery(db).active(next.id))
        assertTrue(h.evaluate(session, ToolCallProposal("proposal", "mutate", "{}")).allowed)
    }
    @Test fun researchBeforeBlockerStillSupportsSameTurnCandidate() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true))
        val research = call("web_search")
        h.execute(research)
        assertFalse(CapabilityDiscovery(db).active(turn.id))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        h.execute(call(CapabilityDiscovery.TOOL, candidate))
        val proposed = db.loadRecentEvents(session.id).last { it.type == "capability.candidate_proposed" }
        val proof = JSONObject(proposed.dataJson).getJSONArray("source_provenance").getJSONObject(0)
        assertEquals(research.id, proof.getString("research_id"))
        assertEquals("https://example.org/docs", proof.getString("url"))
        assertEquals(1, executions)
    }
    @Test fun streamedResearchSourcesReachCoreCandidateValidationEndToEnd() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            """data: {"type":"response.completed","response":{"id":"proof-response","output":[{"id":"proof-search","type":"web_search_call","status":"completed","action":{"type":"search","query":"format conversion documentation","sources":[{"type":"url","url":"https://example.org/docs"}]}}]}}

"""))
        server.start()
        try {
            val backend = ChatGptPlanBackend(ChatGptTokenProvider { _ -> "test-token" }, responsesUrl = server.url("/v1/responses").toString())
            val endpoint = ProviderEndpoint("test", "Test backend", server.url("/v1").toString(), "test-model",
                supportsTools = true, supportsStreaming = true, kind = ProviderKind.CHATGPT_PLAN)
            val request = InferenceRequest(sessionId = session.id, capability = RouteCapability.FAST, systemPrompt = "Test", userText = "Find format documentation",
                provenance = InferenceProvenance(InferenceDomain.DIAGNOSTIC, InferencePurpose.CONNECTION_TEST, userInitiated = true))
            val response = backend.stream(endpoint, null, request).toList().filterIsInstance<InferenceStreamEvent.Completed>().single().response
            CapabilityDiscovery(db).hostedResearch(session.id, turn.id, response)
            val h = harness()
            h.execute(call(CapabilityDiscovery.TOOL, blocked))
            h.execute(call(CapabilityDiscovery.TOOL, candidate))
            val proof = JSONObject(db.loadRecentEvents(session.id).last { it.type == "capability.candidate_proposed" }.dataJson)
                .getJSONArray("source_provenance").getJSONObject(0)
            assertEquals("proof-response", proof.getString("requestId"))
            assertEquals("proof-search", proof.getString("research_id"))
            assertTrue(response.citations.isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun responseResearchBeforeBlockerIsProviderAndCapabilityNeutral() = runTest {
        val h = harness()
        CapabilityDiscovery(db).hostedResearch(session.id, turn.id, InferenceResponse("request-proof", "any-backend", "", 1,
            providerToolUses = listOf(ProviderToolUse("public_document_lookup", status = "completed",
                urls = listOf("https://example.org/docs"), readOnlyResearch = true, id = "research-proof"))))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        h.execute(call(CapabilityDiscovery.TOOL, candidate))
        val proof = JSONObject(db.loadRecentEvents(session.id).last { it.type == "capability.candidate_proposed" }.dataJson)
            .getJSONArray("source_provenance").getJSONObject(0)
        assertEquals("research-proof", proof.getString("research_id"))
        assertEquals("request-proof", proof.getString("requestId"))
    }
    @Test fun citationsWithoutExecutedResearchCannotSupportCandidate() = runTest {
        val h = harness()
        CapabilityDiscovery(db).hostedResearch(session.id, turn.id, InferenceResponse("request", "provider", "", 1,
            citations = listOf(ProviderCitation("Docs", "https://example.org/docs")),
            providerToolUses = listOf(ProviderToolUse("not_research", status = "completed"))))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate)) }.isFailure)
    }
    @Test fun priorTurnSourcesCannotBeReusedAsCurrentTurnResearch() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true))
        h.execute(call("web_search"))
        db.updateTurn(turn.id, TurnStatus.COMPLETED)
        turn = db.createTurn(session.id, "user")
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate)) }.isFailure)
    }
    @Test fun failedResearchWithReturnedUrlsCannotSupportCandidate() = runTest {
        val h = harness()
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        CapabilityDiscovery(db).searchResult(call("research"), """{"ok":false,"results":[{"url":"https://example.org/docs"}]}""")
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate)) }.isFailure)
        CapabilityDiscovery(db).hostedResearch(session.id, turn.id, InferenceResponse("failed", "provider", "", 1,
            providerToolUses = listOf(ProviderToolUse("lookup", status = "failed", urls = listOf("https://example.org/docs"), readOnlyResearch = true))))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate)) }.isFailure)
    }
    @Test fun recursiveDependenciesPreserveRootAndResearchOnlyBoundary() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true), adapter("mutate", ToolRisk.SESSION_WRITE))
        val root = JSONObject(h.execute(call(CapabilityDiscovery.TOOL, blocked)).resultJson)
        val childArgs = JSONObject().put("stage", "blocked").put("objective", "Obtain access to a format converter")
            .put("missing_capability", "Locate an accessible converter").put("parent_blocker_id", root.getString("blocker_id"))
        val child = JSONObject(h.execute(call(CapabilityDiscovery.TOOL, childArgs.toString())).resultJson)
        val grandchild = JSONObject(h.execute(call(CapabilityDiscovery.TOOL, JSONObject().put("stage", "blocked")
            .put("objective", "Find converter documentation").put("missing_capability", "Document lookup").toString())).resultJson)
        assertEquals(root.getString("blocker_id"), child.getString("parent_blocker_id"))
        assertEquals(child.getString("blocker_id"), grandchild.getString("parent_blocker_id"))
        assertEquals("Analyze this unsupported format", grandchild.getString("root_objective"))
        h.execute(call("web_search"))
        val result = h.execute(call(CapabilityDiscovery.TOOL, JSONObject(candidate).put("blocker_id", grandchild.getString("blocker_id")).toString()))
        assertFalse(JSONObject(result.resultJson).getBoolean("authorization_granted"))
        assertEquals(1, executions)
        assertEquals(session.permissions, db.loadSession(session.id)!!.permissions)
        assertTrue(runCatching { h.execute(call("mutate")) }.isFailure)
        val proposed = JSONObject(db.loadRecentEvents(session.id).last { it.type == "capability.candidate_proposed" }.dataJson)
        assertEquals(grandchild.getString("blocker_id"), proposed.getString("blocker_id"))
        assertEquals(2, proposed.getInt("depth"))
        assertEquals(AgentLoopPolicy.MAX_TOOL_CALLS, h.definition(CapabilityDiscovery.TOOL)!!.maxCallsPerTurn)
    }
    @Test fun recursiveCyclesAndForeignParentIdsAreRejectedAndObservable() = runTest {
        val h = harness()
        val root = JSONObject(h.execute(call(CapabilityDiscovery.TOOL, blocked)).resultJson)
        val repeated = JSONObject(blocked).put("missing_capability", "  FORMAT   CONVERSION  ").put("parent_blocker_id", root.getString("blocker_id"))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, repeated.toString())) }.isFailure)
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "capability.recursion_stopped" })
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, JSONObject(blocked).put("parent_blocker_id", "foreign").toString())) }.isFailure)
        assertEquals(1, db.loadRecentEvents(session.id).count { it.type == "capability.blocked" })
    }
    @Test fun recursionCannotExceedExistingStepBudget() = runTest {
        val h = harness()
        repeat(AgentLoopPolicy.MAX_STEPS) { depth ->
            h.execute(call(CapabilityDiscovery.TOOL, JSONObject(blocked).put("missing_capability", "dependency $depth").toString()))
        }
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, JSONObject(blocked).put("missing_capability", "another dependency").toString())) }.isFailure)
        val stopped = JSONObject(db.loadRecentEvents(session.id).last { it.type == "capability.recursion_stopped" }.dataJson)
        assertEquals("existing_step_budget", stopped.getString("reason"))
    }

    @Test fun policyIsGenericAndDoesNotTreatResearchAsAuthorization() {
        assertFalse(Regex("(?i)meatspace|\\bhumans?\\b|physical tasks|physical work|staffing|remote presence|\\bmcp\\b").containsMatchIn(CapabilityDiscovery.instructions))
        assertTrue(CapabilityDiscovery.instructions.contains("separate explicit user authorization path"))
        assertTrue(runCatching { CapabilityDiscovery.validate(JSONObject(candidate).put("install", true)) }.isFailure)
    }
}
