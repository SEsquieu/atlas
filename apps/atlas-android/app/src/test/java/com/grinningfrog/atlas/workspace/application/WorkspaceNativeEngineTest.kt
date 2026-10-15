package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Executes upstream native engine bytes on desktop JNI. Android isolation remains a device gate. */
class WorkspaceNativeEngineTest {
    private fun run(code: String, input: Any? = JSONArray()): JSONObject {
        val bundle = PackageFixture.bundle()
        bundle.getJSONObject("files").put("tools/total.js", code)
        return WorkspaceNativeEngine.run(bundle, "total", input)
    }
    @Test fun realEsModuleComputesIndependentFixtureAndHasNoBrowserOrJavaBindings() {
        val result = WorkspaceNativeEngine.run(PackageFixture.bundle(), "total", JSONArray().put(120).put(230).put(-50))
        assertTrue(result.toString(), result.getBoolean("ok")); assertEquals(300, result.getInt("value"))
        val noAmbientAccess = run("export function total(v) { if (typeof fetch !== 'undefined' || typeof Java !== 'undefined' || typeof window !== 'undefined' || typeof std !== 'undefined' || typeof os !== 'undefined') throw Error('ambient authority'); return 42; }")
        assertEquals(42, noAmbientAccess.getInt("value"))
    }
    @Test fun fullPackageAllowanceAlsoAppliesAtTheNativeBoundary() {
        val bundle = PackageFixture.bundle()
        bundle.getJSONObject("files").put("assets/one.txt", "x".repeat(200_000)).put("assets/two.txt", "y".repeat(200_000))
        val result = WorkspaceNativeEngine.run(bundle, "total", JSONArray().put(120).put(230).put(-50))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(300, result.getInt("value"))
    }
    @Test fun importsResolveOnlyPackagedSourceIncludingDynamicImports() {
        val bundle = PackageFixture.bundle()
        bundle.getJSONObject("files").put("tools/helpers.js", "export const value=37;")
        bundle.getJSONObject("files").put("tools/total.js", "export async function total(v) { const m=await import('./helpers.js'); return m.value; }")
        assertEquals(37, WorkspaceNativeEngine.run(bundle, "total", JSONArray()).getInt("value"))
        for (name in listOf("std", "os", "https://example.com/remote.js", "../../host.js", "file:///host.js")) {
            val result = run("import * as m from '${name}'; export function total(v) { return 1; }")
            assertFalse(name, result.getBoolean("ok"))
        }
    }
    @Test(timeout = 5000) fun infiniteLoopHasAnEngineDeadlineAndNextRunUsesAFreshHeap() {
        val result = run("export function total(v) { while(true) {} }")
        assertFalse(result.toString(), result.getBoolean("ok")); assertEquals("TIMEOUT", result.getString("code"))
        assertEquals(7, run("export function total(v) { return 7; }").getInt("value"))
    }
    @Test(timeout = 5000) fun memoryAndUnsettledPromisesFailWithinBudgets() {
        assertFalse(run("export function total(v) { const a=[]; while(true) a.push(new Array(100000).fill(1)); }").getBoolean("ok"))
        assertFalse(run("export async function total(v) { return await new Promise(()=>{}); }").getBoolean("ok"))
    }
    @Test fun strictOutputDoesNotCoerceNaNInfinityUndefinedOrCycles() {
        for (value in listOf("NaN", "Infinity", "undefined", "(()=>{const v={};v.self=v;return v})()")) {
            assertFalse(value, run("export function total(v) { return $value; }").getBoolean("ok"))
        }
        assertThrows(IllegalArgumentException::class.java) { run("export function total(v) { return '7'; }") }
    }
    @Test fun utf8EmojiSurvivesJniAndJsonRoundTrip() {
        val bundle = PackageFixture.bundle()
        val export = bundle.getJSONObject("manifest").getJSONObject("exports").getJSONObject("total")
        export.put("input_schema", JSONObject().put("type", "string").put("maxLength", 100))
        export.put("output_schema", JSONObject().put("type", "string").put("maxLength", 100))
        bundle.getJSONObject("manifest").put("tests", JSONArray())
        bundle.getJSONObject("files").put("tools/total.js", "export function total(v) { return v+' 🦆'; }")
        assertEquals("Atlas 😀 🦆", WorkspaceNativeEngine.run(bundle, "total", "Atlas 😀").getString("value"))
    }
}
