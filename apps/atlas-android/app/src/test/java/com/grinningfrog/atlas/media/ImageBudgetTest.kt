package com.grinningfrog.atlas.media

import com.grinningfrog.atlas.model.MediaPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBudgetTest {
    @Test fun `each media purpose has an explicit bounded budget`() {
        val heartbeat = ImageBudget.forPurpose(MediaPurpose.HEARTBEAT)
        val standard = ImageBudget.forPurpose(MediaPurpose.STANDARD_VISION)
        val detail = ImageBudget.forPurpose(MediaPurpose.DETAIL_VISION)

        assertEquals(150_000, heartbeat.maxBytes)
        assertEquals(350_000, standard.maxBytes)
        assertEquals(750_000, detail.maxBytes)
        assertTrue(heartbeat.longestEdge < standard.longestEdge)
        assertTrue(standard.longestEdge < detail.longestEdge)
    }
}
