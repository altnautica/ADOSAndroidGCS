package com.altnautica.gcs

import com.altnautica.gcs.R
import com.altnautica.gcs.data.video.VideoEnvironment
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.data.video.VideoModeSelector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The video fallback only runs once the USB radio has already failed on real
 * hardware, so it was previously unreachable from any test. These drive it with
 * a fake environment.
 */
class VideoModeSelectorTest {

    private class FakeEnvironment(
        private val local: VideoMode.GroundStation? = null,
        private val cloud: VideoMode.CloudRelay? = null,
        private val internet: Boolean = false,
        private val deviceId: String? = null,
    ) : VideoEnvironment {
        override fun localAgentMode() = local
        override fun cloudRelayMode() = cloud
        override fun hasInternet() = internet
        override fun pairedDeviceId() = deviceId
    }

    @Test
    fun `local agent is tried before the cloud relay`() {
        val local = VideoMode.GroundStation("http://192.168.1.50:8080/whep")
        val cloud = VideoMode.CloudRelay("turn:relay.example.com:3478", "node-1")
        val selector = VideoModeSelector(FakeEnvironment(local = local, cloud = cloud))

        assertEquals(listOf(local, cloud), selector.fallbacksAfterDirectUsb())
    }

    @Test
    fun `an unavailable transport is not offered`() {
        val cloud = VideoMode.CloudRelay("turn:relay.example.com:3478", "node-1")
        val selector = VideoModeSelector(FakeEnvironment(cloud = cloud))

        assertEquals(listOf(cloud), selector.fallbacksAfterDirectUsb())
    }

    @Test
    fun `nothing reachable yields no candidates`() {
        val selector = VideoModeSelector(FakeEnvironment())

        assertEquals(emptyList<VideoMode>(), selector.fallbacksAfterDirectUsb())
    }

    @Test
    fun `internet but no paired node names the pairing gap`() {
        // The common real case. Reported as a generic "no video source", the
        // operator checks the radio instead of pairing.
        val selector = VideoModeSelector(FakeEnvironment(internet = true))

        assertEquals(R.string.video_no_source_needs_pairing, selector.noSourceReason())
    }

    @Test
    fun `no network names the network gap`() {
        val selector = VideoModeSelector(FakeEnvironment(deviceId = "node-1"))

        assertEquals(R.string.video_no_source_no_network, selector.noSourceReason())
    }

    @Test
    fun `a reachable local agent that failed anyway names the link`() {
        val selector = VideoModeSelector(
            FakeEnvironment(local = VideoMode.GroundStation("http://192.168.1.50:8080/whep")),
        )

        assertEquals(R.string.video_no_source_all_failed, selector.noSourceReason())
    }
}
