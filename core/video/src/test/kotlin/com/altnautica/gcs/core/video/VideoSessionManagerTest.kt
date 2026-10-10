package com.altnautica.gcs.core.video

import app.cash.turbine.test
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VideoSessionManagerTest {

    private val src = VideoSource.CloudRelay(DeviceId("drone-1"))

    private class FakeHandle(override val source: VideoSource) : VideoSessionHandle {
        override val state = MutableStateFlow<VideoState>(VideoState.Idle)
        override val output: VideoOutput = mockk<VideoOutput.SurfaceTarget>(relaxed = true)
        override val recording: VideoRecording? = null
        var starts = 0
        var stops = 0
        override fun start() {
            starts++
            state.value = VideoState.Connecting
        }
        override suspend fun stop() {
            stops++
            state.value = VideoState.Idle
        }
    }

    private class FakeFactory : VideoSourceFactory {
        val created = mutableListOf<FakeHandle>()
        override fun supports(src: VideoSource) = src is VideoSource.CloudRelay
        override fun create(src: VideoSource) = FakeHandle(src).also { created += it }
    }

    @Test
    fun `unsupported source yields no lease`() = runTest {
        val m = VideoSessionManager(setOf(FakeFactory()), backgroundScope)
        assertNull(m.acquire(VideoSource.PhoneRadio(DeviceId("x"))))
    }

    @Test
    fun `second lease reuses the running session`() = runTest {
        val f = FakeFactory()
        val m = VideoSessionManager(setOf(f), backgroundScope)
        val a = m.acquire(src)!!
        val b = m.acquire(src)!!
        assertSame(a.handle, b.handle)
        assertEquals(1, f.created.size)
    }

    @Test
    fun `session survives a release followed by a quick re-acquire`() = runTest {
        val f = FakeFactory()
        val m = VideoSessionManager(setOf(f), backgroundScope)
        m.acquire(src)!!.release()
        advanceTimeBy(9_000)
        m.acquire(src)!!
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, f.created.size)
        assertEquals(0, f.created[0].stops)
    }

    @Test
    fun `session stops 10 s after its last lease`() = runTest {
        val f = FakeFactory()
        val m = VideoSessionManager(setOf(f), backgroundScope)
        val lease = m.acquire(src)!!
        lease.release()
        lease.release() // double release is ignored
        advanceTimeBy(VideoSessionManager.IDLE_STOP_MS + 1)
        runCurrent()
        assertEquals(1, f.created[0].stops)
        assertNull(m.running(src))
    }

    @Test
    fun `state follows the running session`() = runTest {
        val f = FakeFactory()
        val m = VideoSessionManager(setOf(f), backgroundScope)
        m.state(src).test {
            assertEquals(VideoState.Idle, awaitItem())
            val lease = m.acquire(src)!!
            assertEquals(VideoState.Connecting, awaitItem())
            f.created[0].state.value = VideoState.Live(80)
            assertEquals(VideoState.Live(80), awaitItem())
            lease.release()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
