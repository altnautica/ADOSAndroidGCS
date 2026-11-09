package com.altnautica.gcs.feature.cockpit.band

import com.altnautica.gcs.core.agent.model.BatteryHealth
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The return reserve the agent's battery engine reads from the flight controller: the
 * reserve percent the gauge marks and the time until the pack reaches it. Null fields are
 * unknown, and the gauge then draws no reserve mark and labels its time as time to empty.
 */
data class BatteryReserve(val reservePct: Int?, val toReserveS: Int?) {
    companion object {
        /** How often the cockpit asks the agent for its battery engine. */
        const val POLL_MS = 2_000L

        /** The reserve from a battery engine answer; null when the engine is off or stale. */
        fun from(health: BatteryHealth?): BatteryReserve? {
            if (health == null || health.enabled == false || health.stale == true) return null
            val pct = (health.thresholds?.get("reserve_percent") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..99 }
            val eta = health.packs.firstOrNull { it.stale != true }?.prediction?.etaS
                ?.takeIf { it >= 0 && pct != null }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            if (pct == null) return null
            return BatteryReserve(pct, eta)
        }
    }
}
