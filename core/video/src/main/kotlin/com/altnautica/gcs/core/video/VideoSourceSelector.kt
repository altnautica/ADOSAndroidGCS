package com.altnautica.gcs.core.video

import androidx.annotation.StringRes
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.video.demo.DemoVideoFactory
import javax.inject.Inject

/**
 * The video fallback order for one node, and what to say when none of it works.
 *
 * Video prefers a different order from commands: the phone's own radio first
 * (lowest latency, no network), then the agent's WHEP endpoint (on the LAN, or
 * through the ground station that carries the drone), then the cloud
 * peer-to-peer path, then the cloud relay. Pure: the caller supplies agent
 * base URLs, so a JVM test drives the whole decision.
 */
class VideoSourceSelector @Inject constructor() {

    /**
     * Sources worth trying for [node], best first. [agentBaseUrl] answers the
     * `http://host:8080/` of an agent, or null when it is not known; for a
     * drone reached via a ground station the ground station's own `/whep` is
     * used, keyed by the ground station's id.
     */
    fun candidates(
        node: NodeSummary,
        streamId: String?,
        agentBaseUrl: (DeviceId) -> String?,
    ): List<VideoSource> {
        if (node.id == DemoVideoFactory.DEMO_NODE) return listOf(DemoVideoFactory.SOURCE)
        val reaches = node.reaches
        val out = ArrayList<VideoSource>(4)
        if (reaches.any { it is Reach.PhoneRadio }) out += VideoSource.PhoneRadio(node.id)
        if (reaches.any { it is Reach.Lan }) {
            agentBaseUrl(node.id)?.let { out += VideoSource.AgentWhep(node.id, it, streamId) }
        }
        reaches.filterIsInstance<Reach.ViaGround>().forEach { via ->
            agentBaseUrl(via.groundId)?.let { out += VideoSource.AgentWhep(via.groundId, it, streamId) }
        }
        val cloud = reaches.any { it is Reach.CloudLive || it is Reach.CloudQueue }
        if (cloud) {
            out += VideoSource.CloudP2p(node.id)
            out += VideoSource.CloudRelay(node.id)
        }
        return out.distinct()
    }

    /**
     * Why [node] has no video, specific enough to act on. A bare "no video"
     * sends operators looking for a fault that is not there: an unpaired node
     * needs pairing, an offline one needs a network, anything else had a
     * source that failed.
     */
    @StringRes
    fun noSourceReason(node: NodeSummary): Int {
        val blocked = node.reaches.filterIsInstance<Reach.Blocked>().map { it.reason }
        val usable = node.reaches.any { it !is Reach.Blocked }
        return when {
            !usable && BlockReason.NotPaired in blocked -> R.string.video_no_source_needs_pairing
            !usable || node.liveness == Liveness.Offline -> R.string.video_no_source_no_network
            else -> R.string.video_no_source_all_failed
        }
    }
}
