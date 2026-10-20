package com.grinningfrog.atlas.diagnostics

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RuntimeDiagnosticsTest {
    @Test fun boundedEvidenceSurvivesReadbackWithoutDatabase() {
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "runtime-diagnostics.json").delete()
        repeat(12) { RuntimeDiagnostics.record(context, JSONObject().put("kind", "test").put("index", it).put("stack", "x".repeat(12000))) }
        val snapshot = RuntimeDiagnostics.snapshot(context)
        val events = snapshot.getJSONArray("events")
        assertEquals(8, events.length())
        assertEquals(4, events.getJSONObject(0).getInt("index"))
        assertEquals(11, events.getJSONObject(7).getInt("index"))
        assertTrue(snapshot.toString().toByteArray().size < 128 * 1024)
        assertTrue(events.getJSONObject(7).has("at_ms"))
    }
}
