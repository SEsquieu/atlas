package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.*
import org.junit.Assert.*
import org.junit.Test

class InferencePacketCompilerTest {
    private fun request() = InferenceRequest(sessionId="s",capability=RouteCapability.REASONING,systemPrompt="policy",userText="work",
        provenance=InferenceProvenance(domain=InferenceDomain.INTERACTIVE,purpose=InferencePurpose.USER_RESPONSE,userInitiated=true))
    @Test fun countsArgumentsDescriptionsSchemasAndDynamicSystemBeforeBudgeting() {
        val original=request().copy(messages=listOf(InferenceMessage(MessageRole.ASSISTANT,"",toolCalls=listOf(ToolCallProposal("id","stage","x".repeat(20000))))),
            tools=listOf(ToolDefinition("stage","d".repeat(10000),"{}",ToolRisk.SESSION_WRITE)))
        val stats=InferencePacketCompiler.compile(original)
        assertTrue(stats.getJSONObject("components").getInt("messages")>20000)
        assertTrue(stats.getJSONObject("components").getInt("tools")>10000)
        assertTrue(runCatching {InferencePacketCompiler.compile(original,35000)}.isFailure)
        assertEquals(20000,original.messages[0].toolCalls[0].argumentsJson.length)
    }
    @Test fun utf8AccountingAndPreparationAreDeterministic() {
        val r=request().copy(systemPrompt="😀".repeat(500))
        val a=InferencePacketCompiler.compile(r);val b=InferencePacketCompiler.compile(r)
        assertEquals(a.toString(),b.toString())
        assertTrue(a.getInt("text_utf8_bytes")>a.getInt("text_characters"))
        assertEquals(8192,a.getInt("response_headroom_characters"))
    }

    @Test fun sharesBudgetAcrossSourceDiagnosticsAndSchemasWithoutChangingProtocol() {
        val r = request().copy(systemPrompt="policy".repeat(3000),
            messages=listOf(InferenceMessage(MessageRole.USER,"Continue candidate " + "u".repeat(22000)),
                InferenceMessage(MessageRole.ASSISTANT,"",toolCalls=listOf(ToolCallProposal("exact-operation","stage","{\"candidate_id\":\"exact\"}"))),
                InferenceMessage(MessageRole.TOOL,"{\"passed\":false}",toolCallId="exact-operation")),
            tools=listOf(ToolDefinition("stage","schema".repeat(6000),"{}",ToolRisk.SESSION_WRITE)))
        val sections = listOf(PacketSection("task", "candidate=exact; passed=false; activated=false", true),
            PacketSection("source", "s".repeat(30000)), PacketSection("diagnostics", "d".repeat(30000)))
        assertTrue(runCatching { InferencePacketCompiler.compile(r.copy(systemPrompt=r.systemPrompt+sections.joinToString("") { it.text })) }.isFailure)
        val prepared = InferencePacketCompiler.prepare(r, sections)
        assertEquals(r.messages, prepared.request.messages)
        assertEquals(r.tools, prepared.request.tools)
        assertTrue(prepared.request.systemPrompt.contains("candidate=exact; passed=false; activated=false"))
        assertEquals("optional_evidence_projected",prepared.diagnostics.getString("recovery"))
        assertTrue(prepared.diagnostics.getInt("text_characters")+8192<=128000)
        assertEquals(prepared.diagnostics.toString(),InferencePacketCompiler.prepare(r,sections).diagnostics.toString())
    }

    @Test fun requiredOverflowProducesComponentDiagnosticsInsteadOfDiscardingUserSteering() {
        val r=request().copy(userText="u".repeat(128000))
        val error=runCatching { InferencePacketCompiler.prepare(r,listOf(PacketSection("task","passed=false",true))) }.exceptionOrNull()
        assertTrue(error is InferencePacketCapacityException)
        val stats=(error as InferencePacketCapacityException).diagnostics
        assertEquals(128000,stats.getJSONObject("components").getInt("user_text"))
        assertEquals("essential_context_exceeds_capacity",stats.getString("recovery"))
        assertEquals("task",stats.getJSONArray("sections").getJSONObject(0).getString("name"))
        assertEquals(128000,r.userText.length)
    }

    @Test fun optionalSectionsAreWholeAndDoNotDisplaceLaterSmallEvidence() {
        val sections=listOf(PacketSection("huge","😀".repeat(80000)),PacketSection("small","exact-small-detail"))
        val result=InferencePacketCompiler.prepare(request(),sections)
        assertFalse(result.request.systemPrompt.contains("😀"))
        assertTrue(result.request.systemPrompt.contains("exact-small-detail"))
        assertEquals("huge",result.diagnostics.getJSONArray("omitted_optional_sections").getString(0))
        assertEquals("small",result.diagnostics.getJSONArray("retained_optional_sections").getString(0))
    }
}
