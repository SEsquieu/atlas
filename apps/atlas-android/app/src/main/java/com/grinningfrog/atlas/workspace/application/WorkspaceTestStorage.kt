package com.grinningfrog.atlas.workspace.application

import android.content.Context
import com.grinningfrog.atlas.data.AtlasDatabase
import org.json.JSONObject
import java.util.UUID

/** Disposable SQLite database: same validation/receipts as live data, with no connection to phone records. */
internal class WorkspaceTestStorage(context: Context, bundle: JSONObject) : java.io.Closeable {
    private val database=AtlasDatabase(context,null)
    val data=WorkspaceApplicationData(database)
    private val workspace=database.createComposableWorkspace("Isolated UI test","",bundle.toString())
    init { data.setEnabled(workspace.id,workspace.liveRevisionId,true) }
    fun principal()=WorkspacePrincipal(workspace.realmId,workspace.id,workspace.liveRevisionId,UUID.randomUUID().toString(),data.epoch(workspace.id),WorkspaceInstanceMode.TEST)
    fun version()=data.version(workspace.id)
    override fun close()=database.close()
}
