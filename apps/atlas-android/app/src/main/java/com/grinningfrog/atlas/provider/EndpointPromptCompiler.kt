package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceRisk
import com.grinningfrog.atlas.model.PromptProfile
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ResponseMode

/** Adapts Core's semantic context to an endpoint's inference envelope. */
object EndpointPromptCompiler {
    fun compile(endpoint: ProviderEndpoint, request: InferenceRequest): InferenceRequest {
        if (resolvedProfile(endpoint) == PromptProfile.FULL) return request

        val contract = request.responseContract
        val prompt = buildString {
            appendLine("You are Atlas on the user's phone. Answer the latest request directly.")
            goal(request.systemPrompt)?.let { appendLine("Goal: $it") }
            appendLine("Follow user-requested exact wording, format, and length. Never add an introduction or follow-up offer.")
            appendLine("Use only supplied conversation and context. Never invent observations, tool results, or actions.")
            if (request.tools.isNotEmpty()) appendLine("Use a supplied tool only when needed; never claim it ran before its result.")
            if (request.risk != InferenceRisk.NORMAL) appendLine("Prioritize safety and state uncertainty plainly.")
            contract?.let {
                append("Limit: ")
                if (it.mode == ResponseMode.IMMEDIATE) append("answer only; ")
                append("at most ${it.hardMaxWords} words and ${it.maxSentences} sentence")
                if (it.maxSentences != 1) append('s')
                appendLine('.')
            }
            retainedState(request.systemPrompt).takeIf(String::isNotBlank)?.let {
                appendLine()
                append(it)
            }
        }.trim()
        val compactContract = contract?.copy(maxOutputTokens = minOf(contract.maxOutputTokens, compactTokenCap(contract.mode)))
        return request.copy(systemPrompt = prompt, responseContract = compactContract)
    }

    fun resolvedProfile(endpoint: ProviderEndpoint): PromptProfile = when (endpoint.promptProfile) {
        PromptProfile.FULL -> PromptProfile.FULL
        PromptProfile.COMPACT -> PromptProfile.COMPACT
        PromptProfile.AUTO -> if (runCatching { EndpointSecurity.assess(endpoint.baseUrl).location == EndpointLocation.DEVICE }.getOrDefault(false)) {
            PromptProfile.COMPACT
        } else PromptProfile.FULL
    }

    private fun goal(prompt: String) = prompt.lineSequence().firstOrNull { it.startsWith("Goal:") }
        ?.removePrefix("Goal:")?.trim()?.takeIf(String::isNotBlank)

    private fun retainedState(prompt: String): String {
        val markers = listOf("PENDING CLARIFICATION", "CONVERSATION CHECKPOINT", "ATLAS-ADMITTED MEMORY", "PHYSICAL CONTEXT")
        val lines = prompt.lineSequence().toList()
        val first = lines.indexOfFirst { line -> markers.any(line::startsWith) }
        if (first < 0) return ""
        return lines.drop(first).joinToString("\n").substringBefore("\n\nSPOKEN RESPONSE CONTRACT:").trim()
    }

    private fun compactTokenCap(mode: ResponseMode) = when (mode) {
        ResponseMode.IMMEDIATE -> 24
        ResponseMode.DEFAULT -> 72
        ResponseMode.PHYSICAL_GUIDANCE -> 96
        ResponseMode.SAFETY -> 110
        ResponseMode.EXPLANATION -> 160
    }
}
