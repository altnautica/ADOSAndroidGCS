package com.altnautica.gcs.core.video.whep

import org.webrtc.RTCStatsReport

/**
 * Cumulative counters of a peer's inbound video stream, from one WebRTC stats report
 * (`inbound-rtp`, kind `video`). Any counter the stack does not report is null.
 */
data class InboundVideoStats(
    val framesReceived: Long? = null,
    val framesDecoded: Long? = null,
    val framesDropped: Long? = null,
    val decoderImplementation: String? = null,
    val totalInterFrameDelayS: Double? = null,
    val jitterBufferDelayS: Double? = null,
    val jitterBufferEmittedCount: Long? = null,
) {
    companion object {
        fun from(members: Map<String, Any?>): InboundVideoStats = InboundVideoStats(
            framesReceived = members.long("framesReceived"),
            framesDecoded = members.long("framesDecoded"),
            framesDropped = members.long("framesDropped"),
            decoderImplementation = members["decoderImplementation"] as? String,
            totalInterFrameDelayS = members.double("totalInterFrameDelay"),
            jitterBufferDelayS = members.double("jitterBufferDelay"),
            jitterBufferEmittedCount = members.long("jitterBufferEmittedCount"),
        )

        /** The inbound video entry of [report]; null when the report has none yet. */
        fun from(report: RTCStatsReport): InboundVideoStats? =
            report.statsMap.values
                .firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
                ?.let { from(it.members) }

        private fun Map<String, Any?>.long(key: String) = (get(key) as? Number)?.toLong()
        private fun Map<String, Any?>.double(key: String) = (get(key) as? Number)?.toDouble()
    }
}
