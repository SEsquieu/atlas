package com.grinningfrog.atlas.workspace.visual

import org.json.JSONArray
import org.json.JSONObject

/** Monotonic timings; a failed phase never replaces the last successfully completed phase. */
internal class WorkspaceVisualStartup(private val clock: () -> Long) {
    private val started = clock()
    private var phase = "not_started"
    private var lastCompleted = "none"
    private var phaseStarted = started
    private val timings = JSONArray()
    fun begin(name: String) { phase = name; phaseStarted = clock() }
    fun complete() {
        timings.put(JSONObject().put("phase", phase).put("elapsed_ms", clock() - phaseStarted))
        lastCompleted = phase
    }
    fun snapshot(): JSONObject = JSONObject().put("phase", phase).put("last_completed_phase", lastCompleted)
        .put("elapsed_ms", clock() - started).put("phase_elapsed_ms", clock() - phaseStarted)
        .put("completed_phases", JSONArray(timings.toString()))
}
