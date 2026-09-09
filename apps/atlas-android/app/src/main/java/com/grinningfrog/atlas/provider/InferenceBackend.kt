package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.ProviderEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface InferenceBackend {
    suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse

    fun stream(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): Flow<InferenceStreamEvent> = flow {
        val response = infer(endpoint, apiKey, request)
        if (response.text.isNotEmpty()) emit(InferenceStreamEvent.TextDelta(response.text))
        emit(InferenceStreamEvent.Completed(response))
    }
}

class InferenceUnavailableException(
    message: String,
    cause: Throwable? = null,
    val outcomeAmbiguous: Boolean = false,
) : Exception(message, cause)
