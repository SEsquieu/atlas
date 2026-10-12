package com.grinningfrog.atlas.runtime

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import kotlinx.coroutines.test.runTest
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
            providerToolUses = listOf(ProviderToolUse("web_search", status = "completed", queries = listOf("conversion documentation")))))
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
    @Test fun policyIsGenericAndDoesNotTreatResearchAsAuthorization() {
        assertFalse(Regex("(?i)meatspace|\\bhumans?\\b|physical tasks|remote presence|\\bmcp\\b").containsMatchIn(CapabilityDiscovery.instructions))
        assertTrue(CapabilityDiscovery.instructions.contains("separate explicit user authorization path"))
        assertTrue(runCatching { CapabilityDiscovery.validate(JSONObject(candidate).put("install", true)) }.isFailure)
    }
}
