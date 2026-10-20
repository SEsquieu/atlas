package com.grinningfrog.atlas.workspace.application
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class WorkspaceStageArgumentsTest {
    @Test fun misplacedSourceIsRecoveredWithoutChangingOriginalOrInventingFiles() {
        val original=JSONObject().put("workspace_id","w").put("files",JSONObject().put("ui/index.html","html")).put("ui/app.js","js")
        val normalized=WorkspaceStageArguments.normalize(original)
        assertEquals("js",normalized.getJSONObject("files").getString("ui/app.js"))
        assertTrue(original.has("ui/app.js"));assertFalse(normalized.has("ui/app.js"))
        assertEquals("w",normalized.getString("workspace_id"))
    }
    @Test fun ambiguousDuplicateIsRejected() {
        try {WorkspaceStageArguments.normalize(JSONObject().put("files",JSONObject().put("ui/app.js","old")).put("ui/app.js","new"));fail()}catch(expected:IllegalArgumentException){assertTrue(expected.message!!.contains("Conflicting"))}
    }
    @Test fun unknownMetadataRemainsForStrictValidation() {
        assertTrue(WorkspaceStageArguments.normalize(JSONObject().put("capabilities",JSONObject())).has("capabilities"))
    }
}
