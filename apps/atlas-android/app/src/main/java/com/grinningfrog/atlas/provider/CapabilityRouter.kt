package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteTable
import kotlinx.coroutines.CancellationException

class CapabilityRouter(
    private val backend: InferenceBackend,
    private val endpoints: () -> List<ProviderEndpoint>,
    private val routes: () -> RouteTable,
    private val apiKey: suspend (ProviderEndpoint) -> String?,
    private val onAttempt: suspend (endpoint: ProviderEndpoint, success: Boolean, error: String?) -> Unit = { _, _, _ -> },
) {
    fun hasToolCapableRoute(request: InferenceRequest): Boolean {
        return hasToolCapableRoute(request.capability, request.image != null)
    }

    fun hasToolCapableRoute(capability: com.grinningfrog.atlas.model.RouteCapability, requiresVision: Boolean): Boolean {
        val configured = endpoints().associateBy { it.id }
        return routes().candidates(capability).mapNotNull(configured::get).any {
            it.supportsTools && (!requiresVision || it.supportsVision)
        }
    }

    suspend fun route(request: InferenceRequest): InferenceResponse {
        val configured = endpoints().associateBy { it.id }
        val candidates = routes().candidates(request.capability)
            .mapNotNull(configured::get)
            .filter { request.image == null || it.supportsVision }
            .filter { request.tools.isEmpty() || it.supportsTools }
        if (candidates.isEmpty()) throw InferenceUnavailableException("No endpoint is configured for ${request.capability.name.lowercase()}")

        val failures = mutableListOf<String>()
        for (endpoint in candidates) {
            try {
                val result = backend.infer(endpoint, apiKey(endpoint), request)
                onAttempt(endpoint, true, null)
                return result.copy(degraded = endpoint.id !in routes().candidates(request.capability).take(1))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error::class.java.simpleName
                failures += "${endpoint.name}: $message"
                onAttempt(endpoint, false, message)
                if (error !is InferenceUnavailableException || error.outcomeAmbiguous) throw error
            }
        }
        throw InferenceUnavailableException("All ${request.capability.name.lowercase()} routes failed: ${failures.joinToString("; ")}")
    }
}
