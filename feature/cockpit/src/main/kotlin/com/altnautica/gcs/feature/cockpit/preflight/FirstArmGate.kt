package com.altnautica.gcs.feature.cockpit.preflight

/**
 * Holds the first arm of a cockpit session behind the pre-flight checklist. An arm, take-off
 * or launch requested before the operator has cleared the checklist (or before the vehicle
 * has armed once this session) waits as [pending] while the checklist sheet is open.
 */
class FirstArmGate {
    /** The arming request waiting on the checklist; null when none waits. */
    var pending: ArmRequest? = null
        private set

    /** True once the checklist was cleared or the vehicle armed this session. */
    var cleared: Boolean = false
        private set

    /** Lets [request] through when cleared; otherwise parks it and returns false. */
    fun request(request: ArmRequest): Boolean {
        if (cleared) return true
        pending = request
        return false
    }

    /**
     * The operator pressed Continue on the checklist: with nothing blocking, clears the gate
     * and returns the parked request to run; with a block left, returns null and keeps it parked.
     */
    fun proceed(result: PreflightResult): ArmRequest? {
        if (!result.ready) return null
        cleared = true
        return pending.also { pending = null }
    }

    /** The checklist closed without Continue: the parked request is dropped. */
    fun dismiss() {
        pending = null
    }

    /** The vehicle reported armed: later arms skip the checklist this session. */
    fun onArmed() {
        cleared = true
        pending = null
    }
}

/** An arming request: a built-in skill (arm, takeoff) with its args, or the launch sequence. */
sealed interface ArmRequest {
    data class Skill(val skillId: String, val args: Map<String, Any?>) : ArmRequest
    data class Launch(val args: Map<String, Any?>) : ArmRequest
}
