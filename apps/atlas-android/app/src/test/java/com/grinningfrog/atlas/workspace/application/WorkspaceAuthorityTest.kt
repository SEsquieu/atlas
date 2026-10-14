package com.grinningfrog.atlas.workspace.application

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAuthorityTest {
    private val principal = WorkspacePrincipal("realm", "a", "revision", "instance", 4, WorkspaceInstanceMode.FOREGROUND)
    private val grant = WorkspaceGrant("realm", "a", "ble", setOf("read"), setOf("device-a"), setOf(WorkspaceInstanceMode.FOREGROUND), 2)
    private fun state(grant: WorkspaceGrant? = this.grant) = WorkspaceAuthorityState(true, true, true, "revision", 4, true, 2, grant)
    private fun denial(p: WorkspacePrincipal = principal, s: WorkspaceAuthorityState = state(), operation: String = "read", resource: String = "device-a") = WorkspaceAuthority.denial(p, "ble", operation, resource, s)
    @Test fun platformPermissionDoesNotGrantSecondWorkspaceOrDifferentRealm() {
        assertNull(denial())
        assertEquals("WORKSPACE_GRANT_REQUIRED", denial(principal.copy(workspaceId = "b")))
        assertEquals("WORKSPACE_GRANT_REQUIRED", denial(principal.copy(realmId = "other")))
        assertEquals("WORKSPACE_GRANT_REQUIRED", denial(s = state(null)))
    }
    @Test fun everyLayerRemainsMandatory() {
        assertEquals("UNSUPPORTED_CAPABILITY", denial(s = state().copy(adapterInstalled = false)))
        assertEquals("PLATFORM_DISABLED", denial(s = state().copy(platformEnabled = false)))
        assertEquals("OS_PERMISSION_REQUIRED", denial(s = state().copy(osPermissionGranted = false)))
        assertEquals("WORKSPACE_GRANT_REQUIRED", denial(operation = "write"))
        assertEquals("WORKSPACE_GRANT_REQUIRED", denial(resource = "device-b"))
        assertEquals("WORKSPACE_GRANT_REQUIRED", denial(principal.copy(mode = WorkspaceInstanceMode.PERSISTENT)))
    }
    @Test fun dispatchApprovalCannotSurviveRevocationOrHotSwapOnDelivery() {
        assertNull(denial())
        assertEquals("GRANT_REVOKED", denial(s = state(grant.copy(revoked = true))))
        assertEquals("GRANT_REVOKED", denial(s = state().copy(grantGeneration = 3)))
        assertEquals("REVISION_CONFLICT", denial(s = state().copy(activeRevisionId = "new")))
        assertEquals("REVISION_CONFLICT", denial(s = state().copy(activeEpoch = 5)))
        assertEquals("REVISION_CONFLICT", denial(s = state().copy(active = false)))
    }
    @Test fun previewsAndTestsNeverBorrowLiveAuthority() {
        for (mode in listOf(WorkspaceInstanceMode.PREVIEW, WorkspaceInstanceMode.TEST)) {
            assertEquals("EXECUTION_MODE_DENIED", denial(principal.copy(mode = mode), state(grant.copy(modes = setOf(mode)))))
        }
    }
    @Test fun updateScopeExpansionRequiresNewApproval() {
        assertTrue(WorkspaceAuthority.covers(grant, setOf("read"), setOf("device-a"), setOf(WorkspaceInstanceMode.FOREGROUND)))
        assertFalse(WorkspaceAuthority.covers(grant, setOf("read", "write"), setOf("device-a"), grant.modes))
        assertFalse(WorkspaceAuthority.covers(grant, grant.operations, setOf("device-a", "device-b"), grant.modes))
        assertFalse(WorkspaceAuthority.covers(grant, grant.operations, grant.resources, setOf(WorkspaceInstanceMode.PERSISTENT)))
        assertFalse(WorkspaceAuthority.covers(grant.copy(revoked = true), grant.operations, grant.resources, grant.modes))
    }
}
