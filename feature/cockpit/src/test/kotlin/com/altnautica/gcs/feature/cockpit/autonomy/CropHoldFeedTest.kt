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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crop-only detection batches (`crop_window` set) keep the last whole frame's boxes outside the
 * window. Fails on the base commit: each batch replaced the last, so other people's boxes
 * blinked off on every crop-only batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CropHoldFeedTest {
    private val crop = """{"x":200,"y":100,"width":200,"height":200}"""

    private fun det(track: Int, x: Int, y: Int) =
        """{"bbox":{"x":$x,"y":$y,"width":20,"height":40},"class_label":"person","confidence":0.9,"track_id":$track}"""

    private fun batch(ts: Int, dets: List<String>, window: String? = null) =
        """{"model_id":"m","camera_id":"main","frame_id":$ts,"ts_ms":$ts,"detections":[${dets.joinToString(",")}]""" +
            (window?.let { ""","crop_window":$it""" } ?: "") + "}"

    /** The tracks of the camera's batch after feeding [frames] in order. */
    private fun TestScope.tracksAfter(frames: List<String>): List<Int> {
        val api = object : AutonomyFeedApi {
            override fun detections() = flow { frames.forEach { emit(it) }; awaitCancellation() }
            override fun navStream(outbound: Flow<String>) = flow<String> { awaitCancellation() }
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
            PerceptionFeed(repo, backgroundScope), setOf(local), LayerStore(), emptyFlow(), { null },
            UnconfinedTestDispatcher(testScheduler),
        )
        feed.start()
        runCurrent()
        return feed.batches.value["main"]?.items.orEmpty().mapNotNull { it.trackId }.sorted()
    }

    @Test
    fun `a crop-only batch keeps the whole frame's boxes outside its window`() = runTest {
        // 1 is the subject inside the window; 2 and 3 are people outside it.
        val whole = batch(1000, listOf(det(1, 280, 180), det(2, 20, 20), det(3, 500, 300)))
        assertEquals(listOf(1, 2, 3), tracksAfter(listOf(whole, batch(1100, listOf(det(1, 285, 180)), crop))))
    }

    @Test
    fun `the next whole frame replaces the held boxes`() = runTest {
        val frames = listOf(
            batch(1000, listOf(det(1, 280, 180), det(2, 20, 20), det(3, 500, 300))),
            batch(1100, listOf(det(1, 285, 180)), crop),
            batch(1200, listOf(det(1, 290, 180), det(3, 505, 300))),
        )
        assertEquals(listOf(1, 3), tracksAfter(frames))
    }

    @Test
    fun `boxes inside the window and tracks the crop carries are not held`() = runTest {
        val frames = listOf(
            batch(1000, listOf(det(1, 280, 180), det(4, 250, 150), det(2, 20, 20))),
            batch(1100, listOf(det(1, 285, 180), det(2, 210, 110)), crop),
        )
        val tracks = tracksAfter(frames)
        assertEquals(listOf(1, 2), tracks)
    }

    @Test
    fun `held boxes lapse a second after their whole frame`() = runTest {
        val frames = listOf(
            batch(1000, listOf(det(1, 280, 180), det(2, 20, 20))),
            batch(1900, listOf(det(1, 285, 180)), crop),
            batch(2100, listOf(det(1, 285, 180)), crop),
        )
        assertEquals(listOf(1), tracksAfter(frames))
    }

    @Test
    fun `a batch without the window is shown as it is`() = runTest {
        val frames = listOf(
            batch(1000, listOf(det(1, 280, 180), det(2, 20, 20))),
            batch(1100, listOf(det(1, 285, 180))),
        )
        assertEquals(listOf(1), tracksAfter(frames))
    }
}
