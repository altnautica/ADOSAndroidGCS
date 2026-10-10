package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The video fallback only runs once earlier sources have failed on real
 * hardware. These drive it with a fake environment.
 */
class VideoSourceSelectorTest {

    private val node = DeviceId("node-1")

    private class FakeEnvironment(
        private val radio: VideoSource.PhoneRadio? = null,
        private val whep: VideoSource.AgentWhep? = null,
        private val p2p: VideoSource.CloudP2p? = null,
        private val relay: VideoSource.CloudRelay? = null,
        private val internet: Boolean = false,
        private val paired: DeviceId? = null,
    ) : VideoSourceEnvironment {
        override fun phoneRadio() = radio
        override fun agentWhep() = whep
        override fun cloudP2p() = p2p
        override fun cloudRelay() = relay
        override fun hasInternet() = internet
        override fun pairedNode() = paired
    }

    @Test
    fun `sources are tried radio then agent then p2p then relay`() {
        val radio = VideoSource.PhoneRadio(node)
        val whep = VideoSource.AgentWhep(node, "http://192.168.1.50:8080", null)
        val p2p = VideoSource.CloudP2p(node)
        val relay = VideoSource.CloudRelay(node)
        val selector = VideoSourceSelector(
            FakeEnvironment(radio = radio, whep = whep, p2p = p2p, relay = relay),
        )

        assertEquals(listOf(radio, whep, p2p, relay), selector.candidates())
    }

    @Test
    fun `an unavailable source is not offered`() {
        val relay = VideoSource.CloudRelay(node)
        val selector = VideoSourceSelector(FakeEnvironment(relay = relay))

        assertEquals(listOf<VideoSource>(relay), selector.candidates())
    }

    @Test
    fun `nothing reachable yields no candidates`() {
        val selector = VideoSourceSelector(FakeEnvironment())

        assertEquals(emptyList<VideoSource>(), selector.candidates())
    }

    @Test
    fun `internet but no paired node names the pairing gap`() {
        // The common real case. Reported as a generic "no video source", the
        // operator checks the radio instead of pairing.
        val selector = VideoSourceSelector(FakeEnvironment(internet = true))

        assertEquals(R.string.video_no_source_needs_pairing, selector.noSourceReason())
    }

    @Test
    fun `no network names the network gap`() {
        val selector = VideoSourceSelector(FakeEnvironment(paired = node))

        assertEquals(R.string.video_no_source_no_network, selector.noSourceReason())
    }

    @Test
    fun `a reachable local agent that failed anyway names the link`() {
        val selector = VideoSourceSelector(
            FakeEnvironment(whep = VideoSource.AgentWhep(node, "http://192.168.1.50:8080", null)),
        )

        assertEquals(R.string.video_no_source_all_failed, selector.noSourceReason())
    }

    @Test
    fun `an attached radio that failed anyway names the link`() {
        val selector = VideoSourceSelector(FakeEnvironment(radio = VideoSource.PhoneRadio(node)))

        assertEquals(R.string.video_no_source_all_failed, selector.noSourceReason())
    }
}
