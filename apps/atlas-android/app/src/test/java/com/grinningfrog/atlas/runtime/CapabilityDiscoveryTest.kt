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
    @Test fun hostedAdvertisementIsBoundToLatestResponseAndActualRequest() {
        val discovery = CapabilityDiscovery(db)
        fun response(request: String, endpoint: String) = db.appendEvent(session.id, "provider.responded", JSONObject().put("turnId",turn.id).put("requestId", request).put("endpointId", endpoint))
        fun advert(request: String, available: Boolean) = db.appendEvent(session.id, "provider.http_diagnostic", JSONObject().put("turnId", turn.id).put("request_id",request).put("endpoint_id","plan").put("phase","request").put("hosted_search",available))
        advert("one",true); response("one","plan")
        assertTrue(discovery.providerResearch(turn.id).getBoolean("advertised"))
        advert("one",false)
        assertFalse(discovery.providerResearch(turn.id).getBoolean("advertised"))
        advert("two",true); response("two","plan")
        assertTrue(discovery.providerResearch(turn.id).getBoolean("advertised"))
        response("two","local")
        assertFalse(discovery.providerResearch(turn.id).getBoolean("advertised"))
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
    @Test fun researchProvenanceAndResumeSurviveCoreLinkedSlicesButNotAnotherTask() = runTest {
        val store=ExecutionTaskStore(db)
        val task=store.create(db.loadSession(session.id)!!,"Analyze an unsupported format",turn.id,ExecutionBudget())
        var lease=store.claim(task.id,"runner")!!
        val h=ToolHarness(db,{_,_->error("Unexpected capture")},{DeviceHealth()},additionalTools=listOf(adapter("research",ToolRisk.READ_ONLY,research=true)),executionLease={lease})
        suspend fun execute(name:String,args:String="{}",id:String=turn.id):ToolExecutionResult {
            val c=call(name,args,id);db.insertToolCall(c)
            val result=h.execute(c);db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson=result.resultJson)
            return result
        }
        execute(CapabilityDiscovery.TOOL,blocked)
        execute("research")
        store.finish(lease,SliceOutcome.YIELDED,1)
        val continuation=store.nextTurn(task.id);lease=store.claim(task.id,"continued")!!
        assertTrue(JSONObject(execute(CapabilityDiscovery.TOOL,candidate,continuation).resultJson).getBoolean("ok"))
        execute(CapabilityDiscovery.TOOL,"""{"stage":"resume_local","reason":"Use installed local tools"}""",continuation)
        assertTrue(CapabilityDiscovery(db).localWorkResumed(continuation))
        store.finish(lease,SliceOutcome.RESPONDED,1)
        val unrelated=db.createTurn(session.id,"text")
        val other=store.create(db.loadSession(session.id)!!,"Different objective",unrelated.id,ExecutionBudget())
        lease=store.claim(other.id,"other")!!
        execute(CapabilityDiscovery.TOOL,blocked,unrelated.id)
        assertTrue(runCatching {execute(CapabilityDiscovery.TOOL,candidate,unrelated.id)}.isFailure)
    }

    @Test fun resumeRestoresOnlyInstalledLocalMutationsAndNewBlockerReentersDiscovery() = runTest {
        var allowed = true
        val local = object : AtlasToolAdapter {
            override val supportsLocalMutation = true
            override val definition = ToolDefinition("local_edit", "Local", "{}", risk = ToolRisk.SESSION_WRITE)
            override fun evaluate(session: AtlasSession, proposal: ToolCallProposal) = ToolPolicyDecision(allowed, false, ToolRisk.SESSION_WRITE, "Realm check")
            override suspend fun execute(call: AtlasToolCall): ToolExecutionResult { executions++; return ToolExecutionResult("{}") }
        }
        val h = harness(local, adapter("external", ToolRisk.EXTERNAL_EFFECT), adapter("untrusted_write", ToolRisk.SESSION_WRITE))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        assertFalse(h.evaluate(session, ToolCallProposal("edit", "local_edit", "{}")).allowed)
        h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"no_candidate","reason":"Use existing local behavior"}"""))
        assertFalse(h.evaluate(session, ToolCallProposal("edit", "local_edit", "{}")).allowed)
        h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"resume_local","reason":"Existing local behavior satisfies the request"}"""))
        assertTrue(h.evaluate(session, ToolCallProposal("edit", "local_edit", "{}")).allowed)
        h.execute(call("local_edit")); assertEquals(1, executions)
        for (name in listOf("external", "untrusted_write")) {
            assertFalse(h.evaluate(session, ToolCallProposal(name, name, "{}")).allowed)
            assertTrue(runCatching { h.execute(call(name)) }.isFailure)
        }
        allowed = false
        assertFalse(h.evaluate(session, ToolCallProposal("edit", "local_edit", "{}")).allowed)
        assertTrue(runCatching { h.execute(call("local_edit")) }.isFailure)
        allowed = true
        h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"blocked","objective":"Another objective","missing_capability":"Another capability"}"""))
        assertTrue(runCatching { h.execute(call("local_edit")) }.isFailure)
        assertEquals(1, executions)
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "capability.local_work_resumed" })
    }
    @Test fun resumedLocalConfirmationReachesNativeGateButDoesNotGrantApproval() = runTest {
        val local = object : AtlasToolAdapter {
            override val supportsLocalMutation = true
            override val definition = ToolDefinition("confirm_local", "Local approval", "{}", ToolRisk.SESSION_WRITE, requiresConfirmation=true)
            override suspend fun execute(call: AtlasToolCall): ToolExecutionResult { executions++; return ToolExecutionResult("{}") }
        }
        val h=harness(local)
        h.execute(call(CapabilityDiscovery.TOOL,blocked))
        h.execute(call(CapabilityDiscovery.TOOL,"""{"stage":"resume_local","reason":"Use installed local tool under its ordinary approval policy"}"""))
        val decision=h.evaluate(session,ToolCallProposal("proposal","confirm_local","{}"))
        assertTrue(decision.allowed);assertTrue(decision.requiresConfirmation)
        assertTrue(runCatching { h.execute(call("confirm_local")) }.isFailure)
        h.execute(call("confirm_local").copy(status=ToolCallStatus.APPROVED,requiresConfirmation=true))
        assertEquals(1,executions)
        h.execute(call(CapabilityDiscovery.TOOL,JSONObject(blocked).put("missing_capability","New blocker").toString()))
        assertFalse(h.evaluate(session,ToolCallProposal("proposal","confirm_local","{}")).allowed)
        assertTrue(runCatching {h.execute(call("confirm_local").copy(status=ToolCallStatus.APPROVED,requiresConfirmation=true))}.isFailure)
        assertEquals(1,executions)
        assertEquals(session.permissions,db.loadSession(session.id)!!.permissions)
    }
    @Test fun resumeCannotUsePriorTurnBlockerOrGrantAuthorization() = runTest {
        val h = harness()
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        val next = db.createTurn(session.id, "user")
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"resume_local","reason":"Resume"}""", next.id)) }.isFailure)
        val result = JSONObject(h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"resume_local","reason":"Resume existing local tools"}""")).resultJson)
        assertTrue(result.getBoolean("local_work_resumed")); assertFalse(result.getBoolean("authorization_granted")); assertFalse(result.getBoolean("candidate_invoked"))
    }
    @Test fun noCandidateCannotLaunderUnrecordedSources() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        val args = JSONObject().put("stage", "no_candidate").put("reason", "No suitable candidate")
            .put("sources", org.json.JSONArray().put("https://example.org/docs"))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, args.toString())) }.isFailure)
        h.execute(call("web_search"))
        h.execute(call(CapabilityDiscovery.TOOL, args.toString()))
        val event = db.loadRecentEvents(session.id).last { it.type == "capability.no_candidate" }
        assertEquals("https://example.org/docs", JSONObject(event.dataJson).getJSONArray("source_provenance").getJSONObject(0).getString("url"))
        val other = db.createTurn(session.id, "user")
        h.execute(call(CapabilityDiscovery.TOOL, blocked, other.id))
        assertTrue(runCatching { h.execute(call(CapabilityDiscovery.TOOL, args.toString(), other.id)) }.isFailure)
        h.execute(call(CapabilityDiscovery.TOOL, """{"stage":"no_candidate","reason":"No recorded evidence"}""", other.id))
    }
    @Test fun rejectedCrossTurnParentReturnsCurrentIdsAndCanRecoverWithNewRoot() = runTest {
        val h = harness()
        val first = h.execute(call(CapabilityDiscovery.TOOL, blocked))
        val priorId = JSONObject(first.resultJson).getString("blocker_id")
        val next = db.createTurn(session.id, "user")
        val failure = runCatching { h.execute(call(CapabilityDiscovery.TOOL, JSONObject(blocked).put("parent_blocker_id", priorId).toString(), next.id)) }.exceptionOrNull() as StructuredToolRejection
        val payload = ToolFailurePayload.json(failure, failure.message!!)
        assertEquals("invalid_parent_blocker", payload.getString("error_code"))
        assertEquals(next.id, payload.getJSONObject("recovery").getString("current_turn_id"))
        assertEquals(0, payload.getJSONObject("recovery").getJSONArray("current_blockers").length())
        assertFalse(payload.getBoolean("authorization_granted"))
        h.execute(call(CapabilityDiscovery.TOOL, blocked, next.id))
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "capability.control_rejected" })
    }
    @Test fun sourceRejectionIdentifiesExactMissingUrlsAndAllowsCorrectedCandidate() = runTest {
        val h = harness(adapter("web_search", ToolRisk.READ_ONLY, true))
        h.execute(call(CapabilityDiscovery.TOOL, blocked))
        h.execute(call("web_search"))
        val mixed = JSONObject(candidate).put("sources", org.json.JSONArray().put("https://example.org/docs").put("https://example.org/unread"))
        val failure = runCatching { h.execute(call(CapabilityDiscovery.TOOL, mixed.toString())) }.exceptionOrNull() as StructuredToolRejection
        assertEquals("unrecorded_sources", failure.code)
        assertEquals("https://example.org/unread", failure.recovery.getJSONArray("unrecorded_sources").getString(0))
        assertEquals("https://example.org/docs", failure.recovery.getJSONArray("recorded_sources").getString(0))
        assertFalse(db.loadRecentEvents(session.id).any { it.type == "capability.candidate_proposed" })
        h.execute(call(CapabilityDiscovery.TOOL, candidate))
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "capability.candidate_proposed" })
        val next = db.createTurn(session.id, "user")
        val missing = runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate, next.id)) }.exceptionOrNull() as StructuredToolRejection
        assertEquals("missing_blocker", missing.code)
        h.execute(call(CapabilityDiscovery.TOOL, blocked, next.id))
        val crossTurn = runCatching { h.execute(call(CapabilityDiscovery.TOOL, candidate, next.id)) }.exceptionOrNull() as StructuredToolRejection
        assertEquals(0, crossTurn.recovery.getJSONArray("recorded_sources").length())
    }
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
            """data: {"type":"response.output_text.delta","delta":"Found public format documentation."}

data: {"type":"response.completed","response":{"id":"proof-response","output":[{"id":"proof-search","type":"web_search_call","status":"completed","action":{"type":"search","query":"format conversion documentation","sources":[{"type":"url","url":"https://example.org/docs"}]}}]}}

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
            assertEquals(request.requestId, proof.getString("requestId"))
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
            .put("missing_capability", "Format conversion").put("parent_blocker_id", root.getString("blocker_id"))
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
