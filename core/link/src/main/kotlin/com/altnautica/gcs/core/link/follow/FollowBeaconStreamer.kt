package com.altnautica.gcs.core.link.follow

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Streams the operator's position as `FOLLOW_TARGET` while started. */
interface FollowBeaconStreamer {
    /** The newest fix the link took since the last start; null before one went out. */
    val lastSent: StateFlow<BeaconFix?>

    /** Starts (or retargets) the 5 Hz stream reading the newest value of [fixes]. */
    fun start(fixes: StateFlow<BeaconFix?>)

    fun stop()
}

/**
 * Every [FollowBeaconPolicy.PERIOD_MS] sends the newest fix when it is newer than the last one
 * sent and no older than [FollowBeaconPolicy.MAX_FIX_AGE_MS], and only once the vehicle is
 * identified. A location stack that reports slower than 5 Hz therefore sends at its own rate,
 * and a stale fix is never repeated.
 */
class MavlinkFollowBeaconStreamer(
    private val scope: CoroutineScope,
    private val link: MavlinkLink,
    private val clock: ElapsedClock,
) : FollowBeaconStreamer {

    private val _lastSent = MutableStateFlow<BeaconFix?>(null)
    override val lastSent: StateFlow<BeaconFix?> = _lastSent.asStateFlow()

    private var job: Job? = null

    @Synchronized
    override fun start(fixes: StateFlow<BeaconFix?>) {
        job?.cancel()
        _lastSent.value = null
        job = scope.launch {
            var sentAt = Long.MIN_VALUE
            while (isActive) {
                val fix = fixes.value
                if (fix != null && fix.fixElapsedMs > sentAt && FollowBeaconPolicy.fresh(fix, clock.nowMs()) && link.target.value != null) {
                    sentAt = fix.fixElapsedMs
                    if (link.send(FollowBeaconCodec.encode(fix)) == WriteResult.Sent) _lastSent.value = fix
                }
                delay(FollowBeaconPolicy.PERIOD_MS)
            }
        }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        _lastSent.value = null
    }
}
