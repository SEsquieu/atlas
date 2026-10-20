package com.grinningfrog.atlas.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class AuthoringDeadlineTest {
    @Test fun longAuthoringResponseRetainsOriginalRequestPastInteractionDeadline() = runTest {
        val response=async {delay(71_000);"candidate proposal"};var notifications=0;var checks=0
        assertEquals("candidate proposal",awaitModelResponse(response,true,{notifications++},{checks++}))
        assertEquals(1,notifications);assertTrue(checks>0)
    }
    @Test fun ordinaryInteractiveResponseStillDetachesAndCancellationDoesNotApplyLateTools() = runTest {
        val response=async {delay(71_000);"late"}
        assertNull(awaitModelResponse(response,false,{fail("Not durable")},{fail("Not durable")}));response.cancel()
        val pending=async {delay(71_000);"discard"}
        assertTrue(runCatching {awaitModelResponse(pending,true,{}, {throw CancellationException("User cancelled")})}.isFailure)
        pending.cancel()
    }
}
