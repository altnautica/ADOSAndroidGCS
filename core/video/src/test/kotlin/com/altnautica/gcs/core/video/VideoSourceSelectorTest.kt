package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSourceSelectorTest {

    private val drone = DeviceId("drone-1")
    private val ground = DeviceId("gs-1")
    private val urls = mapOf(drone to "http://192.168.1.50:8080/", ground to "http://192.168.1.60:8080/")
    private val selector = VideoSourceSelector(emptySet())

    private fun node(vararg reaches: Reach, id: DeviceId = drone, liveness: Liveness = Liveness.Live) =
        NodeSummary(id, "n", NodeProfile.Drone, reaches.toList(), liveness, null, FcVariant.Unknown, null, null)

    @Test
    fun `radio then lan whep then ground whep then p2p then relay`() {
        val n = node(Reach.CloudLive, Reach.ViaGround(ground), Reach.Lan, Reach.PhoneRadio)

        assertEquals(
            listOf(
                VideoSource.PhoneRadio(drone),
                VideoSource.AgentWhep(drone, urls.getValue(drone), "cam1"),
                VideoSource.AgentWhep(ground, urls.getValue(ground), "cam1"),
                VideoSource.CloudP2p(drone),
                VideoSource.CloudRelay(drone),
            ),
            selector.candidates(n, "cam1") { urls[it] },
        )
    }

    @Test
    fun `carried drone funnels through the ground station whep`() {
        val n = node(Reach.ViaGround(ground))

        assertEquals(
            listOf<VideoSource>(VideoSource.AgentWhep(ground, urls.getValue(ground), null)),
            selector.candidates(n, null) { urls[it] },
        )
    }

    @Test
    fun `lan reach without a known address is skipped`() {
        assertEquals(emptyList<VideoSource>(), selector.candidates(node(Reach.Lan), null) { null })
    }

    @Test
    fun `direct fc has no video path`() {
        assertEquals(emptyList<VideoSource>(), selector.candidates(node(Reach.DirectFc), null) { urls[it] })
    }

    @Test
    fun `a factory that claims a node supplies its sources first`() {
        val claimed = VideoSource.AgentWhep(drone, "test://claimed/", "main")
        val claiming = object : VideoSourceFactory {
            override fun supports(src: VideoSource) = src == claimed
            override fun candidates(node: NodeSummary, stream: String?) =
                if (node.id == drone) listOf(claimed) else emptyList()
            override fun create(src: VideoSource): VideoSessionHandle = error("not played here")
        }
        val withClaim = VideoSourceSelector(setOf(claiming))
        assertEquals(listOf(claimed), withClaim.candidates(node(Reach.Lan, Reach.CloudLive), "main") { urls[it] })
        val other = node(Reach.Lan, id = ground)
        assertEquals(selector.candidates(other, "main") { urls[it] }, withClaim.candidates(other, "main") { urls[it] })
    }

    @Test
    fun `unpaired node names the pairing gap`() {
        assertEquals(
            R.string.video_no_source_needs_pairing,
            selector.noSourceReason(node(Reach.Blocked(BlockReason.NotPaired))),
        )
    }

    @Test
    fun `offline node names the network gap`() {
        assertEquals(
            R.string.video_no_source_no_network,
            selector.noSourceReason(node(Reach.Lan, liveness = Liveness.Offline)),
        )
    }

    @Test
    fun `reachable node that failed names the link`() {
        assertEquals(R.string.video_no_source_all_failed, selector.noSourceReason(node(Reach.Lan)))
    }
}
