package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.InferenceRisk
import com.grinningfrog.atlas.model.ResponseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDeliveryTest {
    @Test fun emitsOnlyCompleteSentencesUntilTheStreamFinishes() {
        val segmenter = SentenceSegmenter()

        assertTrue(segmenter.append("Turn left at Dr. ").isEmpty())
        assertEquals(listOf("Turn left at Dr. Smith's office."), segmenter.append("Smith's office. Then stop").toList())
        assertEquals(listOf("Then stop"), segmenter.finish())
    }

    @Test fun doesNotSplitDecimalsOrEllipses() {
        val segmenter = SentenceSegmenter()
        assertEquals(listOf("Use 2.5 mm... then check it."), segmenter.append("Use 2.5 mm... then check it. "))
        assertEquals(listOf("Next."), segmenter.append("Next. "))
    }

    @Test fun rendersMarkdownAsSpeechInsteadOfReadingSyntax() {
        val rendered = SpeechTextRenderer.render("## Try **this** [guide](https://example.com) and `adb`.")
        assertEquals("Try this guide and adb.", rendered)
        assertFalse(rendered.contains("http"))
    }

    @Test fun responsePolicyExpandsOnlyWhenTheUserAsksForDepth() {
        val immediate = ResponsePolicy.contract("Where is it?", spoken = true, risk = InferenceRisk.NORMAL)
        val detailed = ResponsePolicy.contract("Walk me through why this failed in detail", spoken = true, risk = InferenceRisk.NORMAL)
        val safety = ResponsePolicy.contract("Is this wire safe?", spoken = true, risk = InferenceRisk.SAFETY_CRITICAL)

        assertEquals(ResponseMode.IMMEDIATE, immediate.mode)
        assertEquals(ResponseMode.EXPLANATION, detailed.mode)
        assertTrue(detailed.hardMaxWords > immediate.hardMaxWords)
        assertTrue(safety.actionFirst)
    }
}
