package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasMessage

data class CheckpointBatch(val messages: List<AtlasMessage>, val transcript: String)

/** A checkpoint may only advance through complete turns actually supplied to the model. */
object CheckpointPlanner {
    fun select(candidates: List<AtlasMessage>, maxCharacters: Int = 24_000): CheckpointBatch {
        val turns = candidates.fold(mutableListOf<MutableList<AtlasMessage>>()) { groups, message ->
            if (groups.lastOrNull()?.lastOrNull()?.turnId != message.turnId) groups += mutableListOf<AtlasMessage>()
            groups.last() += message; groups
        }
        val selected = mutableListOf<AtlasMessage>()
        val transcript = StringBuilder()
        for (turn in turns) {
            val text = turn.joinToString("\n") { message -> buildString {
                append("${message.role.name.lowercase()}: ${message.content}")
                message.toolCallsJson?.let { append(" [tool proposals: $it]") }
                message.providerContextJson?.takeIf(String::isNotBlank)?.let { append(" [recorded provider evidence: $it]") }
            } }
            if (transcript.length + text.length + (if (transcript.isEmpty()) 0 else 1) > maxCharacters) break
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append(text); selected += turn
        }
        require(candidates.isEmpty() || selected.isNotEmpty()) { "Checkpoint budget exceeded by the oldest unsummarized turn; prior checkpoint and transcript were retained" }
        return CheckpointBatch(selected, transcript.toString())
    }
    fun validateResponse(text: String, finishReason: String?) {
        require(text.isNotBlank() && text.length <= 8000 && finishReason in setOf("stop", "completed", "end_turn")) {
            "Checkpoint was empty, oversized or incomplete (finishReason=$finishReason); prior checkpoint and transcript were retained"
        }
    }
}
