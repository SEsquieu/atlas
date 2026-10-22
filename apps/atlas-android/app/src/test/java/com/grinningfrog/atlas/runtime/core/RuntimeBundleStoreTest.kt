package com.grinningfrog.atlas.runtime.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RuntimeBundleStoreTest {
    @Test fun corruptDefaultSelectionCannotMakeSettingsInaccessibleOrChangePinnedArtifact() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        java.io.File(context.filesDir, "runtime-core").deleteRecursively()
        val store = RuntimeBundleStore(context)
        store.selectBuiltin()
        val pinned = store.selected()
        java.io.File(context.filesDir, "runtime-core/selection.json").writeText("corrupt")
        assertEquals("native", store.selected())
        assertEquals(pinned, store.resolve(pinned).id)
    }
    @Test fun localProposalRequiresApprovalAndSelectionCompareAndSetWithoutChangingPins() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        java.io.File(context.filesDir,"runtime-core").deleteRecursively()
        val store=RuntimeBundleStore(context);val parent=store.builtinSnapshot()
        val proposed=RuntimeBundle.local("local-test",parent.source+"\n// proposed")
        val call=com.grinningfrog.atlas.model.AtlasToolCall("proposal-op","session","turn","atlas_runtime_stage","{}",com.grinningfrog.atlas.model.ToolCallStatus.PROPOSED,com.grinningfrog.atlas.model.ToolRisk.SESSION_WRITE,false,"op",createdAtMs=0,updatedAtMs=0)
        val q=org.json.JSONObject().put("passed",true).put("runtime_id",proposed.id).put("verification_version",1)
        val id=store.saveProposal(call,parent.id,proposed,"Test local edit",q).getString("proposal_id")
        assertEquals("native",store.selected())
        assertThrows(Exception::class.java) {store.proposal(id,"foreign")}
        assertThrows(Exception::class.java) {store.activateProposal(call,id,"native")}
        val approved=call.copy(status=com.grinningfrog.atlas.model.ToolCallStatus.APPROVED,requiresConfirmation=true)
        store.selectBuiltin()
        assertThrows(Exception::class.java) {store.activateProposal(approved,id,"native")}
        store.selectNative()
        assertTrue(store.activateProposal(approved,id,"native").getBoolean("selected_for_new_tasks"))
        assertEquals(proposed.id,store.selected());assertEquals(parent.id,store.resolve(parent.id).id)
        assertEquals(proposed.source,RuntimeBundleStore(context).resolve(proposed.id).source)
        assertTrue(store.activateProposal(approved,id,"native").getBoolean("ok"))
    }
    @Test fun rollbackSelectionDoesNotRemovePinnedArtifact() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        java.io.File(context.filesDir, "runtime-core").deleteRecursively()
        val store = RuntimeBundleStore(context)
        assertEquals("native", store.selected())
        store.selectBuiltin()
        val pinned = store.selected()
        assertEquals(pinned, store.resolve(pinned).id)
        store.selectNative()
        assertEquals("native", store.selected())
        assertEquals(pinned, RuntimeBundleStore(context).resolve(pinned).id)
        assertThrows(Exception::class.java) { store.resolve("../selection") }
    }
}
