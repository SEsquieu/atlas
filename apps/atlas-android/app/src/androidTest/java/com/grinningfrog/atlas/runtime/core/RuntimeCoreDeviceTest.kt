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
