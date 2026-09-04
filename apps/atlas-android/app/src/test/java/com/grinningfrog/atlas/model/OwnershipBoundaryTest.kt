package com.grinningfrog.atlas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnershipBoundaryTest {
    @Test
    fun consumerSessionDefaultsToPersonalWorkspaceWithoutEnterprisePlacement() {
        val session = AtlasSession("session", "Atlas", "Help", SessionStatus.IDLE, 1, 1)
        assertEquals(DEFAULT_PERSONAL_WORKSPACE_ID, session.workspaceId)
        assertNull(session.siteId)
        assertNull(session.stationId)
        assertNull(session.taskRunId)
    }

    @Test
    fun inferenceCarriesWorkspaceAndTaskScopeIndependentlyFromProviderRoute() {
        val request = InferenceRequest(
            sessionId = "session",
            workspaceId = "8d74d4fd-84c2-49b6-bac4-040864663643",
            taskRunId = "75943256-a187-4840-9d73-c6fc63775f9b",
            capability = RouteCapability.VISION,
            systemPrompt = "system",
            userText = "what changed",
        )
        assertEquals("8d74d4fd-84c2-49b6-bac4-040864663643", request.workspaceId)
        assertEquals("75943256-a187-4840-9d73-c6fc63775f9b", request.taskRunId)
    }
}
