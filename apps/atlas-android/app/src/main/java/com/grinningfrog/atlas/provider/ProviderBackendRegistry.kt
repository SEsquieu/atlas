package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ProviderKind
import kotlinx.coroutines.flow.Flow

class ProviderBackendRegistry(
    private val openAiCompatible: InferenceBackend,
    private val chatGptPlan: InferenceBackend,
    private val managed: InferenceBackend = openAiCompatible,
) : InferenceBackend {
    override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse =
        backend(endpoint).infer(endpoint, apiKey, request)

    override fun stream(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): Flow<InferenceStreamEvent> =
        backend(endpoint).stream(endpoint, apiKey, request)

    private fun backend(endpoint: ProviderEndpoint) = when (endpoint.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> openAiCompatible
        ProviderKind.CHATGPT_PLAN -> chatGptPlan
        ProviderKind.MANAGED -> managed
    }
}
