package com.altnautica.gcs.radio.rx.hop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follows a drone's channel hops from its control plane.
 *
 * A drone repeats its announcement about every 100 ms for up to 3 s, each
 * copy carrying the time left until the flip. The first verified copy for a
 * new channel schedules the retune; later copies for the same channel are
 * ignored, and a copy for a different channel replaces the plan. Frames
 * that fail verification under the invite's hop key are ignored, so nobody
 * in range can steer the receiver off its drone.
 */
class HopFollower(
    private val pairKey: ByteArray,
    initialChannel: Int,
    private val scope: CoroutineScope,
    private val retune: suspend (channel: Int) -> Boolean,
) {
    @Volatile var currentChannel: Int = initialChannel
        private set

    @Volatile var pendingChannel: Int? = null
        private set

    /** Hops this follower carried out. */
    @Volatile var hopsFollowed: Int = 0
        private set

    private var job: Job? = null
    private val lock = Any()

    /** Feeds one control-plane datagram; returns the decoded announcement when it verified. */
    fun onControlPacket(packet: ByteArray): HopAnnounce? {
        if (packet.size != HopAnnounce.LENGTH) return null
        val announce = HopAnnounce.parse(packet, pairKey) ?: return null
        synchronized(lock) {
            val target = announce.targetChannel
            if (target == pendingChannel) return announce
            if (target == currentChannel && pendingChannel == null) return announce
            job?.cancel()
            pendingChannel = target
            job = scope.launch {
                delay(announce.delayMs)
                val ok = retune(target)
                synchronized(lock) {
                    if (pendingChannel == target) pendingChannel = null
                    if (ok) {
                        currentChannel = target
                        hopsFollowed++
                    }
                }
            }
        }
        return announce
    }

    /** Records a retune made outside an announcement (rendezvous fallback). */
    fun onRetunedElsewhere(channel: Int) {
        synchronized(lock) {
            job?.cancel()
            job = null
            pendingChannel = null
            currentChannel = channel
        }
    }

    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
            pendingChannel = null
        }
    }
}
