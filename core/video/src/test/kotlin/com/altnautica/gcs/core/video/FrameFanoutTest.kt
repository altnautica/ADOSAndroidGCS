package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.model.VideoState
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class FrameFanoutTest {

    private var now = 0L
    private val fanout = FrameFanout(VideoClock { now })
    private val output = fanout.output { mockk() }

    @Test
    fun `decoded frames with no renderer never count as shown`() {
        fanout.onFrame(mockk<VideoFrame>(relaxed = true))
        assertEquals(FrameWatchdog.Verdict.Flowing, fanout.decoded.check())
        assertNull(fanout.shown.check())
        assertEquals(VideoState.Connecting, fanout.flowingState(12))
    }

    @Test
    fun `frames handed to a renderer are live and reach it`() {
        val got = mutableListOf<VideoFrame>()
        output.addSink(VideoSink { got += it })
        val frame = mockk<VideoFrame>(relaxed = true)
        fanout.onFrame(frame)
        assertEquals(listOf(frame), got)
        assertEquals(VideoState.Live(12), fanout.flowingState(12))
    }

    @Test
    fun `a detached renderer stops live after the freeze window`() {
        val sink = VideoSink { }
        output.addSink(sink)
        fanout.onFrame(mockk(relaxed = true))
        output.removeSink(sink)
        now = FrameWatchdog.FREEZE_AFTER_MS
        fanout.onFrame(mockk(relaxed = true))
        assertEquals(FrameWatchdog.Verdict.Flowing, fanout.decoded.check())
        assertEquals(VideoState.Connecting, fanout.flowingState(null))
    }
}
