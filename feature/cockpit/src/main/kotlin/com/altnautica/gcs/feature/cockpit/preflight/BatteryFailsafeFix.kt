package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/**
 * The one-tap fix on the pilot's `battery_failsafe` readiness item: it opens Aircraft setup
 * and runs the ground wizard's failsafe profile step, which lists the profile and writes
 * nothing until the operator applies it there.
 */
object BatteryFailsafeFix {
    const val ITEM_ID = "battery_failsafe"
    const val LABEL_KEY = "readiness.battery_failsafe.fix"
    const val WIZARD = "ground"
    const val STEP = "ados_profile"

    /**
     * The states the profile step fixes: a failsafe action left at nothing, or an RTL that climbs
     * without GNSS. Unset levels, no monitor and unknowns have no fix here (the levels and the
     * monitor belong to the initial tune).
     */
    private val FIXABLE_KEYS = listOf(
        "none", "none_confined", "low_none", "low_none_confined", "low_none_plane", "critical_none", "confined_rtl",
        "differs", "differs_confined", "differs_low",
    ).map { "readiness.battery_failsafe.$it" }

    /** The fix shows on a failing [item] whose message is a fixable state, only while the vehicle is known disarmed. */
    fun offered(item: ReadinessItem, armed: Boolean?, strings: SetupStringsSpec): Boolean =
        item.id == ITEM_ID && !item.ok && armed == false && FIXABLE_KEYS.any { strings.format(it) == item.msg }
}
