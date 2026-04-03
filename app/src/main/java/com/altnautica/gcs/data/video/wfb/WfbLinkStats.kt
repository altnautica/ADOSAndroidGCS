package com.altnautica.gcs.data.video.wfb

/**
 * Kotlin-friendly representation of WFB-ng link statistics.
 * Mapped from the native WfbNGStats Java shim.
 */
data class WfbLinkStats(
    val totalPackets: Int = 0,
    val decryptErrors: Int = 0,
    val decryptOk: Int = 0,
    val fecRecovered: Int = 0,
    val packetsLost: Int = 0,
    val packetsBad: Int = 0,
    val packetsOverride: Int = 0,
    val packetsOutgoing: Int = 0,
    val avgRssi: Int = 0,
) {
    val packetLossPercent: Float
        get() = if (totalPackets > 0) (packetsLost.toFloat() / totalPackets) * 100f else 0f

    val fecRecoveryPercent: Float
        get() = if (totalPackets > 0) (fecRecovered.toFloat() / totalPackets) * 100f else 0f
}
