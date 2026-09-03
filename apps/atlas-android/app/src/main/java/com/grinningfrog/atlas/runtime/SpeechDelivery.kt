package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.InferenceRisk
import com.grinningfrog.atlas.model.ResponseContract
import com.grinningfrog.atlas.model.ResponseMode
import java.util.Locale

/** Core-owned response policy. Providers receive limits; they do not decide Atlas's interaction style. */
object ResponsePolicy {
    private val explicitDetail = Regex(
        "(explain|detail|walk me through|deep dive|why|compare|break down|step by step|tell me more)",
        RegexOption.IGNORE_CASE,
    )
    private val physicalGuidance = Regex(
        "(what should i do|how do i|which (one|way)|where should|next step|help me (fix|find|build|repair)|guide me)",
        RegexOption.IGNORE_CASE,
    )
    private val immediate = Regex("^(yes|no|where|which|when|who|is it|can i|did it)\\b", RegexOption.IGNORE_CASE)

    fun contract(text: String, spoken: Boolean, risk: InferenceRisk): ResponseContract {
        if (!spoken) return ResponseContract(ResponseMode.DEFAULT, 100, 220, 10, 320)
        if (risk == InferenceRisk.SAFETY_CRITICAL) return ResponseContract(ResponseMode.SAFETY, 35, 60, 4, 110, actionFirst = true)
        if (physicalGuidance.containsMatchIn(text)) return ResponseContract(ResponseMode.PHYSICAL_GUIDANCE, 30, 55, 3, 100, actionFirst = true)
        if (explicitDetail.containsMatchIn(text)) return ResponseContract(ResponseMode.EXPLANATION, 75, 120, 7, 190)
        if (immediate.containsMatchIn(text.trim())) return ResponseContract(ResponseMode.IMMEDIATE, 18, 35, 2, 70)
        return ResponseContract(ResponseMode.DEFAULT, 35, 65, 3, 115)
    }

    fun instructions(contract: ResponseContract): String = buildString {
        append("SPOKEN RESPONSE CONTRACT: mode=${contract.mode.name.lowercase(Locale.US)}; target about ${contract.targetWords} words; ")
        append("hard maximum ${contract.hardMaxWords} words and ${contract.maxSentences} sentences. ")
        if (contract.actionFirst) append("State the action or answer first. ")
        append("Use natural contractions and plain spoken sentences. Do not restate the question, use markdown, announce that you are an AI, ")
        append("or begin with canned filler such as 'Certainly', 'Absolutely', or 'Great question'. ")
        append("Give only what this moment requires. For physical guidance, give one safe actionable step and let the user continue the conversation.")
    }
}

/** Incremental sentence boundary detector designed for streamed spoken text. */
class SentenceSegmenter(private val maxBufferedCharacters: Int = 180) {
    private val buffer = StringBuilder()
    private val abbreviations = setOf("mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "e.g.", "i.e.", "etc.", "vs.", "no.")

    fun append(delta: String): List<String> {
        buffer.append(delta)
        return drain(final = false)
    }

    fun finish(): List<String> = drain(final = true)

    fun pendingText(): String = buffer.toString()

    private fun drain(final: Boolean): List<String> = buildList {
        while (buffer.isNotEmpty()) {
            val boundary = findBoundary(final)
            if (boundary <= 0) break
            val raw = buffer.substring(0, boundary).trim()
            buffer.delete(0, boundary)
            while (buffer.firstOrNull()?.isWhitespace() == true) buffer.deleteCharAt(0)
            SpeechTextRenderer.render(raw).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun findBoundary(final: Boolean): Int {
        for (index in buffer.indices) {
            val char = buffer[index]
            if (char == '\n' && index > 0) return index + 1
            if (char !in ".?!") continue
            val previous = buffer.getOrNull(index - 1)
            val next = buffer.getOrNull(index + 1)
            if (char == '.' && previous?.isDigit() == true && next?.isDigit() == true) continue
            if (char == '.' && (previous == '.' || next == '.')) continue
            val tokenStart = buffer.substring(0, index + 1).lastIndexOfAny(charArrayOf(' ', '\n', '\t')).let { it + 1 }
            val token = buffer.substring(tokenStart, index + 1).lowercase(Locale.US)
            if (token in abbreviations) continue
            if (next != null && next.isWhitespace()) return index + 1
        }
        if (buffer.length >= maxBufferedCharacters) {
            val searchFrom = (maxBufferedCharacters * .55).toInt()
            val split = (maxBufferedCharacters downTo searchFrom).firstOrNull { buffer.getOrNull(it)?.let(Char::isWhitespace) == true }
            if (split != null) return split
        }
        return if (final) buffer.length else -1
    }
}

object SpeechTextRenderer {
    fun render(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " code sample ")
        .replace(Regex("`([^`]+)`")) { it.groupValues[1] }
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)")) { it.groupValues[1] }
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("[*_~]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}
