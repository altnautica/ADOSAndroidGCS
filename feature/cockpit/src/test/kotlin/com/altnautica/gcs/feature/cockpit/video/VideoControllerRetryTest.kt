package com.altnautica.gcs.feature.cockpit.video

import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoSessionHandle
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import com.altnautica.gcs.feature.cockpit.testDroneNode
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Retry restarts the failed session of the source on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoControllerRetryTest {
    @Test
    fun `retry stops and starts the failed session again`() = runTest(UnconfinedTestDispatcher()) {
        val source = VideoSource.AgentWhep(DeviceId("drone-1"), "http://agent.local", null)
        val handle = mockk<VideoSessionHandle>(relaxed = true) { every { this@mockk.source } returns source }
        val sessions = mockk<VideoSessionManager> {
            every { canPlay(any()) } returns true
            every { state(any()) } returns flowOf(VideoState.Failed("ice"))
            every { running(source) } returns handle
        }
        val selector = mockk<VideoSourceSelector> { every { candidates(any(), any(), any()) } returns listOf(source) }
        val ctl = CockpitVideoController(
            backgroundScope,
            MutableStateFlow<NodeSummary?>(testDroneNode()),
            selector,
            sessions,
            mockk(relaxed = true),
            mockk<AgentEndpoints> { coEvery { endpointFor(any()) } returns null },
            mockk(relaxed = true),
            MutableSharedFlow<CockpitEvent>(),
        )
        runCurrent()
        assertEquals(source, ctl.video.value.main)
        ctl.retry()
        runCurrent()
        coVerifyOrder {
            handle.stop()
            handle.start()
        }
    }
}
