package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/** The fix buttons on the pilot's readiness rows in the pre-flight checklist. */
object ReadinessFixes {
    const val AIRFRAME_ITEM_ID = "airframe_balance"
    const val AIRFRAME_LABEL_KEY = "readiness.airframe_balance.reset"

    /**
     * "I rebalanced it": clears the connected flight controller's learned airframe record, so the
     * next lift-off probes the rebalanced airframe afresh.
     */
    val AIRFRAME_RESET = PilotAction(PilotAction.AIRFRAME_RESET)

    /** "I rebalanced it" shows on a failing `airframe_balance` [item] only while the vehicle is known disarmed. */
    fun resetsAirframe(item: ReadinessItem, armed: Boolean?): Boolean = resetsAirframe(item) && !item.ok && armed == false

    /** [item] is the row whose fix is "I rebalanced it". */
    fun resetsAirframe(item: ReadinessItem): Boolean = item.id == AIRFRAME_ITEM_ID

    /** The label of [item]'s fix at arm state [armed], or null when it has none. */
    fun labelKey(item: ReadinessItem, armed: Boolean?, strings: SetupStringsSpec): String? = when {
        BatteryFailsafeFix.offered(item, armed, strings) -> BatteryFailsafeFix.LABEL_KEY
        resetsAirframe(item, armed) -> AIRFRAME_LABEL_KEY
        else -> null
    }
}
