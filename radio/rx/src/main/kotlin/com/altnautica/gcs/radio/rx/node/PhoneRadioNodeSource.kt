package com.altnautica.gcs.radio.rx.node

import android.os.SystemClock
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.radio.rx.session.DroneLinkStatus
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The drones the phone radio hears. A drone is listed while the receiver
 * runs, with the [Reach.PhoneRadio] reach; its liveness follows the age of
 * the last datagram recovered from it.
 */
@Singleton
class PhoneRadioNodeSource @Inject constructor(
    private val session: PhoneRadioSession,
) : NodeSource {

    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(TICK_MS)
        }
    }

    override val nodes: Flow<List<NodeSummary>> =
        combine(session.links, ticks) { links, now -> links.map { summary(it, now) } }.distinctUntilChanged()

    companion object {
        const val LIVE_MS = 3_000L
        const val STALE_MS = 10_000L
        private const val TICK_MS = 1_000L

        fun liveness(lastPacketMs: Long?, nowMs: Long): Liveness {
            if (lastPacketMs == null) return Liveness.Offline
            val age = nowMs - lastPacketMs
            return when {
                age < LIVE_MS -> Liveness.Live
                age < STALE_MS -> Liveness.Stale
                else -> Liveness.Offline
            }
        }

        fun summary(link: DroneLinkStatus, nowMs: Long) = NodeSummary(
            id = link.deviceId,
            name = link.deviceId.value,
            profile = NodeProfile.Drone,
            reaches = listOf(Reach.PhoneRadio),
            liveness = liveness(link.lastPacketMs, nowMs),
            agentVersion = null,
            fcVariant = FcVariant.Unknown,
            carriedDroneId = null,
            lastSeenMs = link.lastPacketMs,
        )
    }
}
