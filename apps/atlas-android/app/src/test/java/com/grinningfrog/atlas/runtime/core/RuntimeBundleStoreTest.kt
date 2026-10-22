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
