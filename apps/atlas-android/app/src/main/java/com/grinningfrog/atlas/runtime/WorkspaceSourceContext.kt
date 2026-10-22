package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract
import com.grinningfrog.atlas.workspace.application.WorkspacePackageRepository
import org.json.JSONObject

/** Recover read bytes from durable operations, independently of lossy transcript summaries. */
internal object WorkspaceSourceContext {
    fun load(database: AtlasDatabase, tasks: ExecutionTaskStore, taskId: String): List<JSONObject> { return runCatching {
        val task=tasks.get(taskId) ?: return emptyList()
        val workspaceId=task.document.optJSONObject("workspace_target")?.optString("workspace_id")?.takeIf(String::isNotBlank) ?: return emptyList()
        val workspace=database.loadComposableWorkspace(workspaceId) ?: return emptyList()
        if(workspace.realmId!=task.document.getJSONObject("scope").getString("realm_id"))return emptyList()
        val calls=tasks.calls(taskId)
        val stagedId=calls.lastOrNull {it.name=="workspace_package_stage" && it.status==ToolCallStatus.COMPLETED &&
            runCatching {JSONObject(it.resultJson.orEmpty()).optString("workspace_id")==workspaceId}.getOrDefault(false)}
            ?.let {JSONObject(it.resultJson!!).optString("candidate_id").takeIf(String::isNotBlank)}
        val staged=stagedId?.let {WorkspacePackageRepository(database).inspect(workspaceId,workspace.realmId,it)}
        val candidate=stagedId?.takeIf {staged?.optBoolean("testable_against_current_base")==true && staged?.optBoolean("activated")!=true}
        val files=if(candidate!=null)staged!!.getJSONObject("bundle").getJSONObject("files")
            else JSONObject(database.loadLiveWorkspaceRevision(workspaceId)!!.definitionJson).getJSONObject("files")
        retain(calls,workspaceId,workspace.liveRevisionId,candidate,files)
    }.getOrDefault(emptyList()) }

    internal fun retain(calls: List<AtlasToolCall>, workspaceId: String, revision: String, candidate: String?, files: JSONObject): List<JSONObject> {
        val seen=mutableSetOf<String>();val retained=mutableListOf<JSONObject>()
        for(call in calls.asReversed()) {
            if(call.name!="workspace_package_read_file" || call.status!=ToolCallStatus.COMPLETED)continue
            val receipt=runCatching {JSONObject(call.resultJson.orEmpty())}.getOrNull() ?: continue
            val path=receipt.optString("path");val source=receipt.opt("source") as? String ?: continue
            val current=files.opt(path) as? String ?: continue
            val start=receipt.optInt("start_char",0)
            if(receipt.optString("workspace_id")!=workspaceId || receipt.optString("source_sha256")!=WorkspaceApplicationContract.hash(current) ||
                start<0 || start>current.length || source.length>current.length-start || !current.regionMatches(start,source,0,source.length))continue
            if(!seen.add("$path:$start:${source.length}"))continue
            if(retained.any {it.getString("path")==path && it.getInt("start_char")<=start && it.getInt("start_char")+it.getString("source").length>=start+source.length})continue
            retained.removeAll {it.getString("path")==path && start<=it.getInt("start_char") && start+source.length>=it.getInt("start_char")+it.getString("source").length}
            retained+=JSONObject().put("workspace_id",workspaceId).put("revision_id",if(candidate==null)revision else JSONObject.NULL)
                .put("candidate_id",candidate).put("base_revision_id",revision).put("stage_parent_candidate_id",candidate).put("path",path).put("source_sha256",receipt.getString("source_sha256"))
                .put("start_char",start).put("source",source).put("total_chars",current.length).put("read_operation_id",call.id)
            if(retained.size==16)break
        }
        return retained
    }
}
