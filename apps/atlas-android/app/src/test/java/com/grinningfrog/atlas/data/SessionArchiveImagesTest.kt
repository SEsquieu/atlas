package com.grinningfrog.atlas.data

import android.app.Application
import android.graphics.Bitmap
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.VisualObservation
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class SessionArchiveImagesTest {
    @Test fun exportedWorkspacePixelsMatchMetadataAndMissingPixelsAreExplicit() = runTest {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase("atlas.db")
        val db=AtlasDatabase(context);val media=MediaRepository(context)
        try {
            val session=db.createSession("Pixels", "Evidence")
            val ref=media.ingestWorkspaceBitmap(Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888),session.id)
            db.saveObservation(VisualObservation(sessionId=session.id,media=ref,observedAtMs=1,availableAtMs=1,timing=com.grinningfrog.atlas.model.ObservationTiming(0,0,0),summary="Workspace revision test"))
            val archive=SessionArchive(context,db,media)
            ZipFile(archive.writeExport(session.id)).use { zip ->
                val index=JSONObject(zip.getInputStream(zip.getEntry("workspace-images/index.json")).bufferedReader().readText())
                val image=index.getJSONArray("entries").getJSONObject(0)
                assertTrue(image.getBoolean("included"))
                val bytes=zip.getInputStream(zip.getEntry(image.getString("path"))).readBytes()
                assertArrayEquals(media.resolve(ref).readBytes(),bytes)
            }
            media.resolve(ref).delete()
            ZipFile(archive.writeExport(session.id)).use { zip ->
                val index=JSONObject(zip.getInputStream(zip.getEntry("workspace-images/index.json")).bufferedReader().readText())
                assertFalse(index.getJSONArray("entries").getJSONObject(0).getBoolean("included"))
            }
        } finally { db.close() }
    }
}
