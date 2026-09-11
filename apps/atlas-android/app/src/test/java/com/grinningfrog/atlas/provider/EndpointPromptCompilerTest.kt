package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.PromptProfile
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ResponseContract
import com.grinningfrog.atlas.model.ResponseMode
import com.grinningfrog.atlas.model.RouteCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPromptCompilerTest {
    @Test fun autoUsesCompactPromptForOnDeviceEndpoint() {
        val compiled = EndpointPromptCompiler.compile(endpoint("http://127.0.0.1:8080/v1"), request())

        assertTrue(compiled.systemPrompt.startsWith("You are Atlas on the user's phone."))
        assertTrue(compiled.systemPrompt.contains("Goal: Help with the current task."))
        assertTrue(compiled.systemPrompt.contains("PHYSICAL CONTEXT"))
        assertFalse(compiled.systemPrompt.contains("replaceable intelligence operating inside Atlas"))
        assertTrue(compiled.systemPrompt.length < request().systemPrompt.length)
        assertEquals(72, compiled.responseContract?.maxOutputTokens)
    }

    @Test fun explicitCompactSupportsLanModels() {
        val endpoint = endpoint("http://192.168.1.20:11434/v1").copy(promptProfile = PromptProfile.COMPACT)
        assertEquals(PromptProfile.COMPACT, EndpointPromptCompiler.resolvedProfile(endpoint))
    }

    @Test fun fullProfilePreservesCompleteRequest() {
        val original = request()
        val compiled = EndpointPromptCompiler.compile(endpoint("https://api.example.com/v1").copy(promptProfile = PromptProfile.FULL), original)

        assertSame(original, compiled)
        assertEquals(original.systemPrompt, compiled.systemPrompt)
        assertEquals(115, compiled.responseContract?.maxOutputTokens)
    }

    @Test fun immediateCompactResponseHasTinyGenerationBudget() {
        val original = request().copy(responseContract = ResponseContract(ResponseMode.IMMEDIATE, 1, 8, 1, 16))
        val compiled = EndpointPromptCompiler.compile(endpoint("http://localhost:8080/v1"), original)

        assertTrue(compiled.systemPrompt.contains("answer only"))
        assertEquals(16, compiled.responseContract?.maxOutputTokens)
    }

    private fun endpoint(url: String) = ProviderEndpoint("endpoint", "Endpoint", url, "model")

    private fun request() = InferenceRequest(
        sessionId = "session",
        capability = RouteCapability.FAST,
        systemPrompt = """
            You are the replaceable intelligence operating inside Atlas, a durable physical-agent runtime.
            Atlas Core owns session truth, memory admission, physical context, permissions, tool execution, and audit history.
            Goal: Help with the current task.
            Speak like a capable coworker. Optimize for shared understanding, not maximum response completeness.

            PHYSICAL CONTEXT: no usable observation is currently available.

            SPOKEN RESPONSE CONTRACT: mode=default; target about 35 words; hard maximum 65 words and 3 sentences.
        """.trimIndent(),
        userText = "Hello",
        provenance = com.grinningfrog.atlas.model.InferenceProvenance(com.grinningfrog.atlas.model.InferenceDomain.DIAGNOSTIC, com.grinningfrog.atlas.model.InferencePurpose.CONNECTION_TEST, userInitiated = true),
        responseContract = ResponseContract(ResponseMode.DEFAULT, 35, 65, 3, 115),
    )
}
