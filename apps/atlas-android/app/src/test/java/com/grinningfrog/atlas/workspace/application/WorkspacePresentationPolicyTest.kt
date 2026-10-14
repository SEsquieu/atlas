package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspacePresentationPolicyTest {
    private val principal = WorkspacePrincipal("realm", "workspace", "revision", "instance", 1, WorkspaceInstanceMode.PREVIEW)
    private val policy = WorkspacePresentationPolicy(principal, "ui/index.html")
    @Test fun onlyExactPackagedOriginsAndCanonicalGetPathsAreServed() {
        assertEquals("ui/index.html", policy.resource(policy.entryUrl, "GET"))
        assertEquals("atlas/host.js", policy.resource("${policy.origin}/atlas/host.js", "GET"))
        for (url in listOf("https://example.com/ui/index.html", "file:///ui/index.html", "content://files/ui/index.html", "data:text/html,hello", "javascript:alert(1)",
            "${policy.origin}:443/ui/index.html", "${policy.origin}/ui/../escape.js", "${policy.origin}/%2e%2e/escape.js", "${policy.origin}/ui/index.html?redirect=https://example.com",
            policy.origin.replace("https://", "https://attacker@") + "/ui/index.html", "${policy.origin}.attacker.com/ui/index.html")) {
            assertNull(url, policy.resource(url, "GET"))
        }
        assertNull(policy.resource(policy.entryUrl, "POST"))
        assertFalse(policy.navigation("${policy.origin}/ui/another.html"))
        assertTrue(policy.navigation(policy.entryUrl + "#section"))
    }
    @Test fun workspacePreviewAndInstanceOriginsAreDistinctAndMessagesAreFenced() {
        for (other in listOf(principal.copy(workspaceId = "another"), principal.copy(realmId = "other"), principal.copy(revisionId = "new"),
            principal.copy(instanceId = "new"), principal.copy(mode = WorkspaceInstanceMode.FOREGROUND))) {
            assertNotEquals(policy.origin, WorkspacePresentationPolicy(other, "ui/index.html").origin)
        }
        assertTrue(policy.acceptsMessage(policy.origin, true, true))
        assertFalse(policy.acceptsMessage(policy.origin, false, true))
        assertFalse(policy.acceptsMessage(policy.origin, true, false))
        assertFalse(policy.acceptsMessage("https://example.com", true, true))
    }
    @Test fun authoritativeRegistryRejectsGuessedMethodsAndUnknownParams() {
        val descriptor = WorkspaceHostRegistry.describe(principal)
        assertEquals(setOf("host.describe", "host.ready"), descriptor.getJSONObject("methods").keys().asSequence().toSet())
        assertFalse(descriptor.getBoolean("authorization_granted"))
        assertTrue(WorkspaceHostRegistry.validate("host.describe", JSONObject()))
        assertFalse(WorkspaceHostRegistry.validate("ble.connect", JSONObject()))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceHostRegistry.validate("host.ready", JSONObject().put("workspace_id", "forged")) }
    }
}
