package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.RouteTable
import kotlinx.coroutines.test.runTest
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
                if (endpoint.id == "primary") throw InferenceUnavailableException("offline")
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

    private fun endpoint(id: String) = ProviderEndpoint(id, id, "http://localhost:11434", "test", supportsVision = true)
    private fun request(capability: RouteCapability) = InferenceRequest(sessionId = "session", capability = capability, systemPrompt = "system", userText = "hello")
}
