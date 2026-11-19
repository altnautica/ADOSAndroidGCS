package com.altnautica.gcs.core.link.fleet

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Where the relay secret the phone issued a drone stands. */
enum class RelaySecretState {
    /** Not yet confirmed (the drone is off, out of range, or not yet offered). */
    Pending,

    /** The drone holds this phone's secret. */
    Held,

    /** The drone holds another ground's secret; it must be rebound to change hands. Not retried. */
    Conflict,
}

/**
 * Tells each enrolled drone the slot and the relay secret the phone issued
 * it, over the aux uplink's Request lane, every [INTERVAL_MS] until the fleet
 * agrees, as the ground station's slot reconciler does. The secret goes first
 * (the slot write carries a ticket the drone can only check once it holds the
 * secret); a drone that holds another secret answers 409 and is reported, not
 * re-offered. Acknowledgements live in memory: a restart restates once.
 *
 * Runs while the phone is the fleet's ground and holds the uplink: the radio
 * session launches [run] in that run's scope.
 */
@Singleton
class SlotReconciler internal constructor(
    private val store: FleetGroundStore,
    private val lane: FleetRequestLane?,
    private val intervalMs: Long,
) {
    @Inject constructor(store: FleetGroundStore, lane: Optional<FleetRequestLane>) : this(store, lane.orElse(null), INTERVAL_MS)

    private val _secrets = MutableStateFlow<Map<String, RelaySecretState>>(emptyMap())

    /** Per device id, where its relay secret stands, as last learned. */
    val secrets: StateFlow<Map<String, RelaySecretState>> = _secrets.asStateFlow()

    private val _slotsConfirmed = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Per device id, the slot the drone acknowledged. */
    val slotsConfirmed: StateFlow<Map<String, Int>> = _slotsConfirmed.asStateFlow()

    /** Reconciles until cancelled; returns at once when no Request lane is bound. */
    suspend fun run() {
        val lane = lane ?: return
        while (true) {
            tick(lane)
            delay(intervalMs)
        }
    }

    /** One pass over the fleet. */
    internal suspend fun tick(lane: FleetRequestLane) {
        val slots = store.fleet.value?.slots ?: return
        val present = slots.map { it.deviceId }.toSet()
        _secrets.update { it.filterKeys(present::contains) }
        _slotsConfirmed.update { it.filterKeys(present::contains) }

        for (s in slots) {
            val secret = s.relaySecret ?: continue
            if (_secrets.value[s.deviceId] in SETTLED) continue
            val status = lane.request(s.deviceId, "POST", SECRET_PATH, JSON, """{"secret":"$secret"}""".encodeToByteArray(), null)
            val state = when (status) {
                in 200..299 -> RelaySecretState.Held
                409 -> RelaySecretState.Conflict
                else -> RelaySecretState.Pending
            }
            _secrets.update { it + (s.deviceId to state) }
        }
        for (s in slots) {
            if (_slotsConfirmed.value[s.deviceId] == s.slot) continue
            // A drone holding another ground's secret refuses every ticket this phone can mint.
            if (_secrets.value[s.deviceId] == RelaySecretState.Conflict) continue
            val secret = s.relaySecret ?: continue
            val body = """{"key":"$SLOT_KEY","value":${s.slot}}""".encodeToByteArray()
            val status = lane.request(s.deviceId, "PUT", CONFIG_PATH, JSON, body, secret)
            if (status in 200..299) _slotsConfirmed.update { it + (s.deviceId to s.slot) }
        }
    }

    companion object {
        const val INTERVAL_MS = 30_000L
        const val CONFIG_PATH = "/api/config"
        const val SECRET_PATH = "/api/relay/peer-secret"
        const val SLOT_KEY = "video.wfb.fleet_slot"
        private const val JSON = "application/json"
        private val SETTLED = setOf(RelaySecretState.Held, RelaySecretState.Conflict)
    }
}
