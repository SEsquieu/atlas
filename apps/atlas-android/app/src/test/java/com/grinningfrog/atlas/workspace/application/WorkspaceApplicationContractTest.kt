package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

object PackageFixture {
    fun bundle(): JSONObject = JSONObject("""{
      "manifest":{"format":"atlas.workspace.web.v1","title":"Expenses","host_api":1,"entry":"ui/index.html",
        "data_schema_version":1,"capabilities":[],"dependencies":[],
        "exports":{"total":{"module":"tools/total.js","function":"total","mode":"pure",
          "input_schema":{"type":"array","items":{"type":"integer"},"maxItems":100},"output_schema":{"type":"integer"}}},
        "tests":[{"name":"independent total fixture","export":"total","input":[120,230,-50],"expected":300}]},
      "files":{"ui/index.html":"<!doctype html><html><body>Expenses<script type=\"module\" src=\"./app.js\"></script></body></html>",
        "ui/app.js":"document.body.dataset.loaded = 'yes';", "tools/total.js":"export function total(values) { return values.reduce((a,b) => a+b,0); }"}
    }""")
}

class WorkspaceApplicationContractTest {
    @Test fun validPackageHasExplicitNonExecutableDescriptor() {
        assertEquals(emptyList<String>(), WorkspaceApplicationContract.validate(PackageFixture.bundle()))
        val descriptor = WorkspaceApplicationContract.descriptor()
        assertFalse(descriptor.getBoolean("execution_available"))
        assertFalse(descriptor.getBoolean("activation_available"))
    }
    @Test fun rejectTraversalReservedPathsCollisionAndUnboundedFiles() {
        for (name in listOf("../escape.js", "/tmp/escape.js", "ui/../escape.js", "ui\\escape.js", "atlas/sdk.js", "ui/.hidden.js", "ui/app.js?x=1", "ui/native.so")) {
            assertFalse(name, WorkspaceApplicationContract.validPath(name))
            val bundle = PackageFixture.bundle(); bundle.getJSONObject("files").put(name, "x")
            assertFalse(name, WorkspaceApplicationContract.validate(bundle).isEmpty())
        }
        val collision = PackageFixture.bundle(); collision.getJSONObject("files").put("UI/INDEX.HTML", "x")
        assertTrue(WorkspaceApplicationContract.validate(collision).single().contains("Case-colliding"))
        val huge = PackageFixture.bundle(); huge.getJSONObject("files").put("ui/app.js", "x".repeat(WorkspaceApplicationContract.MAX_FILE_BYTES + 1))
        assertFalse(WorkspaceApplicationContract.validate(huge).isEmpty())
    }
    @Test fun strictNumbersBooleansVersionAndScopesDoNotCoerce() {
        for ((key, value) in listOf("host_api" to "1", "data_schema_version" to 1.5, "host_api" to 2)) {
            val bundle = PackageFixture.bundle(); bundle.getJSONObject("manifest").put(key, value)
            assertFalse(WorkspaceApplicationContract.validate(bundle).isEmpty())
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 9_007_199_254_740_992L)) assertThrows(IllegalArgumentException::class.java) { WorkspaceApplicationContract.checkJson(value) }
        val bundle = PackageFixture.bundle()
        bundle.getJSONObject("manifest").getJSONArray("capabilities").put(JSONObject("""{"name":"ble","operations":["*"],"resources":["device-a"]}"""))
        assertFalse(WorkspaceApplicationContract.validate(bundle).isEmpty())
    }
    @Test fun unknownSchemaKeywordsAndAmbiguousNullAreRejected() {
        val schema = JSONObject("""{"type":"object","properties":{"name":{"type":"string","maxLength":10},"optional":{"type":"null"}},"required":["name"],"additionalProperties":false}""")
        WorkspaceJsonSchema.check(schema)
        WorkspaceJsonSchema.validate(schema, JSONObject().put("name", "a"))
        WorkspaceJsonSchema.validate(schema, JSONObject().put("name", "a").put("optional", JSONObject.NULL))
        for (value in listOf(JSONObject(), JSONObject().put("name", JSONObject.NULL), JSONObject().put("name", "a").put("extra", true)))
            assertThrows(IllegalArgumentException::class.java) { WorkspaceJsonSchema.validate(schema, value) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceJsonSchema.check(JSONObject().put("type", "integer").put("minimum", 0)) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceJsonSchema.check(JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceJsonSchema.validate(JSONObject().put("type", "boolean"), "true") }
    }
    @Test fun dependenciesMustMatchContentAndTestsMustMatchExportSchemas() {
        val bundle = PackageFixture.bundle()
        val dependencies = bundle.getJSONObject("manifest").getJSONArray("dependencies")
        dependencies.put(JSONObject().put("path", "tools/total.js").put("sha256", "bad").put("source", "fixture").put("license", "MIT"))
        assertFalse(WorkspaceApplicationContract.validate(bundle).isEmpty())
        dependencies.getJSONObject(0).put("sha256", WorkspaceApplicationContract.hash(bundle.getJSONObject("files").getString("tools/total.js")))
        assertTrue(WorkspaceApplicationContract.validate(bundle).isEmpty())
        bundle.getJSONObject("manifest").getJSONArray("tests").getJSONObject(0).put("expected", "300")
        assertFalse(WorkspaceApplicationContract.validate(bundle).isEmpty())
    }
    @Test fun hashesIgnoreObjectInsertionOrderButKeepArrayOrder() {
        val a = JSONObject().put("z", 1).put("a", JSONArray().put(1).put(2))
        val b = JSONObject().put("a", JSONArray().put(1).put(2)).put("z", 1)
        assertEquals(WorkspaceApplicationContract.hash(WorkspaceApplicationContract.canonical(a)), WorkspaceApplicationContract.hash(WorkspaceApplicationContract.canonical(b)))
        b.getJSONArray("a").put(0, 2).put(1, 1)
        assertNotEquals(WorkspaceApplicationContract.canonical(a), WorkspaceApplicationContract.canonical(b))
    }
    @Test fun protocolCannotSupplyItsOwnPrincipalOrIdempotencyAuthority() {
        val raw = JSONObject("""{"api_version":1,"request_id":"client-1","method":"host.describe","params":{}}""")
        assertEquals("client-1", WorkspaceHostProtocol.request(raw.toString()).getString("request_id"))
        for (field in listOf("workspace_id", "grant", "operation_id", "principal", "epoch")) {
            val forged = JSONObject(raw.toString()).put(field, "forged")
            assertThrows(IllegalArgumentException::class.java) { WorkspaceHostProtocol.request(forged.toString()) }
        }
    }
}
