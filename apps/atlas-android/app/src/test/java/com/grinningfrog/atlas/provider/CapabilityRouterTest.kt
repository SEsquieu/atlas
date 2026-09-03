package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.RouteTable
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRouterTest {
    @Test fun fallsBackInDeclaredOrderAndMarksDegradedResponse() = runTest {
        val attempts = mutableListOf<String>()
        val endpoints = listOf(endpoint("primary"), endpoint("fallback"))
        val backend = object : InferenceBackend {
            override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse {
                attempts += endpoint.id
                if (endpoint.id == "primary") throw InferenceUnavailableException("offline before acceptance")
                return InferenceResponse(request.requestId, endpoint.id, "available", 12)
            }
        }
        val router = CapabilityRouter(backend, { endpoints }, { RouteTable(reasoning = listOf("primary"), fallback = listOf("fallback")) }, { null })

        val response = router.route(request(RouteCapability.REASONING))

        assertEquals(listOf("primary", "fallback"), attempts)
        assertEquals("fallback", response.endpointId)
        assertTrue(response.degraded)
    }

    @Test fun missingCapabilityRouteFailsClosed() = runTest {
        val router = CapabilityRouter(
            backend = object : InferenceBackend {
                override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest) = error("must not run")
            },
            endpoints = { listOf(endpoint("text-only")) }, routes = { RouteTable() }, apiKey = { null },
        )

        val error = runCatching { router.route(request(RouteCapability.VISION)) }.exceptionOrNull()
        assertTrue(error is InferenceUnavailableException)
    }

    @Test fun toolTurnsOnlyUseEndpointsThatAdvertiseToolSupport() = runTest {
        val endpoints = listOf(endpoint("plain"), endpoint("agent").copy(supportsTools = true))
        val router = CapabilityRouter(
            backend = object : InferenceBackend {
                override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest) =
                    InferenceResponse(request.requestId, endpoint.id, "ok", 1)
            },
            endpoints = { endpoints }, routes = { RouteTable(fast = listOf("plain", "agent")) }, apiKey = { null },
        )
        val request = request(RouteCapability.FAST).copy(tools = listOf(ToolDefinition("look", "look", "{}", ToolRisk.READ_ONLY)))

        assertTrue(router.hasToolCapableRoute(request))
        assertEquals("agent", router.route(request).endpointId)
    }

    @Test fun ambiguousProviderOutcomeNeverFallsThroughToAnotherPaidRoute() = runTest {
        val attempts = mutableListOf<String>()
        val router = CapabilityRouter(
            backend = object : InferenceBackend {
                override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse {
                    attempts += endpoint.id
                    throw InferenceUnavailableException("timed out after dispatch", outcomeAmbiguous = true)
                }
            },
            endpoints = { listOf(endpoint("one"), endpoint("two")) },
            routes = { RouteTable(fast = listOf("one", "two")) }, apiKey = { null },
        )

        val error = runCatching { router.route(request(RouteCapability.FAST)) }.exceptionOrNull()
        assertTrue(error is InferenceUnavailableException)
        assertEquals(listOf("one"), attempts)
    }

    @Test fun streamNeverFallsThroughAfterAnyOutputWasAccepted() = runTest {
        val attempts = mutableListOf<String>()
        val router = CapabilityRouter(
            backend = object : InferenceBackend {
                override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest) = error("unused")
                override fun stream(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest) = flow {
                    attempts += endpoint.id
                    emit(InferenceStreamEvent.TextDelta("already billed"))
                    throw InferenceUnavailableException("connection ended", outcomeAmbiguous = false)
                }
            },
            endpoints = { listOf(endpoint("one"), endpoint("two")) },
            routes = { RouteTable(fast = listOf("one", "two")) }, apiKey = { null },
        )

        val error = runCatching { router.stream(request(RouteCapability.FAST)).toList() }.exceptionOrNull()
        assertTrue(error is InferenceUnavailableException)
        assertEquals(listOf("one"), attempts)
    }

    private fun endpoint(id: String) = ProviderEndpoint(id, id, "http://localhost:11434", "test", supportsVision = true)
    private fun request(capability: RouteCapability) = InferenceRequest(sessionId = "session", capability = capability, systemPrompt = "system", userText = "hello")
}
