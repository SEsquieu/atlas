package com.grinningfrog.atlas.runtime.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeCoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bundle() = RuntimeBundle.parse(context.assets.open("runtime/builtin.json").bufferedReader().use { it.readText() }, builtin = true)
    @Test fun warmBridgeLatencyAndConcurrentRequestIsolation() = runBlocking {
        val engine = RuntimeCoreEngine(context)
        try {
            val bundle = bundle()
            val cold = engine.decide(bundle, 0, 0)
            assertTrue(cold.cold)
            val decisions = (0 until 40).map { n -> async(Dispatchers.Default) { engine.decide(bundle, n % 8, 0) } }.awaitAll()
            assertTrue(decisions.all { it.action == "infer" && !it.cold })
            val sorted = decisions.map { it.elapsedUs }.sorted()
            val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
            android.util.Log.i("AtlasRuntimeBenchmark", "cold_us=${cold.elapsedUs} warm_p50_us=${sorted[20]} warm_p95_us=$p95 samples=${sorted.size}")
            assertTrue("Warm bridge p95 exceeds provisional 100ms gate: $p95 us", p95 < 100_000)
            assertEquals("yield", engine.decide(bundle, 8, 0).action)
        } finally { engine.close() }
    }
    @Test fun replacementServiceRetainsWarmEngineWhenOldServiceReleases() = runBlocking {
        val engine = RuntimeCoreEngine(context)
        try {
            engine.retain()
            val b = bundle()
            engine.decide(b, 0, 0)
            engine.retain() // Replacement service attaches before old teardown completes.
            engine.release()
            assertFalse(engine.decide(b, 0, 0).cold)
            engine.release()
            assertTrue(engine.decide(b, 0, 0).cold)
        } finally { engine.close() }
    }
    @Test fun isolatedProposalQualificationPreservesWarmActiveCore() = runBlocking {
        val engine=RuntimeCoreEngine(context)
        try {
            val active=bundle();engine.decide(active,0,0)
            val changed=RuntimeBundle.local("qualified-test",active.source.replace("Choose useful, bounded progress","Choose careful, bounded progress"))
            assertTrue(engine.qualify(changed).getBoolean("passed"))
            val after=engine.decide(active,0,0)
            assertFalse(after.cold);assertTrue(after.guidance.contains("Choose useful"))
            assertTrue(runCatching {engine.qualify(RuntimeBundle.local("bad-contract","globalThis.atlasRuntime={reduce:()=>({})};"))}.isFailure)
            assertEquals("infer",engine.decide(active,0,0).action)
        } finally {engine.close()}
    }
    @Test fun agentToolsStageQualifyApproveAndSelectOnlyForNewTasks() = runBlocking {
        val engine=RuntimeCoreEngine(context);val store=RuntimeBundleStore(context)
        val db=com.grinningfrog.atlas.data.AtlasDatabase(context)
        store.selectNative()
        try {
            val session=db.createSession("Runtime tools","Test runtime editing")
            val turn=db.createTurn(session.id,"text")
            val tasks=com.grinningfrog.atlas.runtime.ExecutionTaskStore(db)
            val task=tasks.create(session,"Test runtime editing",turn.id,com.grinningfrog.atlas.runtime.ExecutionBudget())
            tasks.pinRuntime(task.id,"native")
            val tools=RuntimeCoreTools.create(db,store,engine).associateBy {it.definition.name}
            fun call(name:String,args:org.json.JSONObject=org.json.JSONObject())=com.grinningfrog.atlas.model.AtlasToolCall(java.util.UUID.randomUUID().toString(),session.id,turn.id,name,args.toString(),com.grinningfrog.atlas.model.ToolCallStatus.PROPOSED,com.grinningfrog.atlas.model.ToolRisk.SESSION_WRITE,false,java.util.UUID.randomUUID().toString(),createdAtMs=0,updatedAtMs=0)
            val source=org.json.JSONObject(tools.getValue("atlas_runtime_read").execute(call("atlas_runtime_read")).resultJson)
            val staged=org.json.JSONObject(tools.getValue("atlas_runtime_stage").execute(call("atlas_runtime_stage",org.json.JSONObject()
                .put("parent_runtime_id",source.getString("parent_runtime_id")).put("expected_sha256",source.getString("source_sha256"))
                .put("version","device-local-test").put("find","Choose useful, bounded progress").put("replace","Choose careful, bounded progress").put("reason","Verify local runtime edits without a rebuild"))).resultJson)
            assertEquals("native",store.selected())
            val activate=call("atlas_runtime_activate",org.json.JSONObject().put("proposal_id",staged.getString("proposal_id")).put("expected_selected_runtime_id","native"))
            val policy=tools.getValue("atlas_runtime_activate").evaluate(session,com.grinningfrog.atlas.model.ToolCallProposal(activate.id,activate.name,activate.argumentsJson))
            assertTrue(policy.allowed);assertTrue(policy.requiresConfirmation)
            assertTrue(runCatching {tools.getValue("atlas_runtime_activate").execute(activate)}.isFailure)
            tools.getValue("atlas_runtime_activate").execute(activate.copy(status=com.grinningfrog.atlas.model.ToolCallStatus.APPROVED,requiresConfirmation=true))
            assertEquals("native",tasks.get(task.id)!!.document.getString("runtime_core_id"))
            assertTrue(engine.decide(store.resolve(store.selected()),0,0).guidance.contains("Choose careful"))
        } finally {store.selectNative();engine.close();db.close()}
    }
    @Test fun cancellationTerminatesRunawayAndNextRequestRecovers() = runBlocking {
        val engine = RuntimeCoreEngine(context)
        try {
            val good = bundle()
            engine.decide(good, 0, 0)
            val malicious = good.copy(version = "runaway", source = "while(true) {}")
            val result = withTimeoutOrNull(200) { engine.decide(malicious, 0, 0) }
            assertNull(result)
            assertEquals("infer", engine.decide(good, 0, 0).action)
        } finally { engine.close() }
    }
}
