package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.ContinuationInterceptor

/** The navigation stream is parsed in the feed's parse context and applied in arrival order. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutonomyFeedParseTest {
    // Fails on the base commit: the feed took no parse context and parsed every frame in its
    // collector, on the main thread.
    @Test
    fun `nav frames are parsed in the parse context and applied in order`() = runTest {
        val parse = StandardTestDispatcher(testScheduler, name = "parse")
        var streamedIn: ContinuationInterceptor? = null
        val api = object : AutonomyFeedApi {
            override fun detections() = emptyFlow<String>()
            override fun navStream(outbound: Flow<String>) = flow {
                streamedIn = currentCoroutineContext()[ContinuationInterceptor]
                emit("""{"kind":"pose","ts_ms":1,"data":{"pose":{"R":[1,0,0,0,1,0,0,0,1],"t":[1,2,0]},"health":"good","frame":"map_ned"}}""")
                emit("""{"kind":"pose","ts_ms":2,"data":{"pose":{"R":[1,0,0,0,1,0,0,0,1],"t":[5,6,0]},"health":"good","frame":"map_ned"}}""")
                awaitCancellation()
            }
            override fun pilotStream(outgoing: Flow<String>) = flow<String> { awaitCancellation() }
            private val absent = AgentResult.Err(AgentError.FeatureAbsent)
            override suspend fun visionDemand(body: kotlinx.serialization.json.JsonObject) = absent
            override suspend fun visionStatus() = absent
            override suspend fun setDetector(modelId: String) = absent
            override suspend fun mapLive(since: Long) = absent
        }
        val local = object : LocalNodeApis {
            override fun handles(id: DeviceId) = false
            override fun capture(id: DeviceId) = null
            override fun sensors(id: DeviceId) = null
            override fun capturePreview(id: DeviceId, camera: String?) = null
            override fun autonomy(id: DeviceId) = api
        }
        val repo = mockk<ExtensionRepository> { coEvery { api(any()) } returns null }
        val feed = AutonomyFeed(
            backgroundScope, MonotonicClock { testScheduler.currentTime }, MutableStateFlow(DeviceId("drone-1")), repo,
            PerceptionFeed(repo, backgroundScope), setOf(local), LayerStore(), emptyFlow(), { null }, parse,
        )
        feed.start()
        runCurrent()
        assertSame("the stream and its parse run in the parse context", parse, streamedIn)
        val pose = feed.pose.value
        assertNotNull(pose)
        assertEquals("the later frame wins", 5f, pose!!.northM, 1e-3f)
        assertEquals(2, feed.trail.value.size)
    }
}
