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
}
