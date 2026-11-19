package com.altnautica.gcs.core.link.fleet

/**
 * What MAVLink system id an aircraft should carry, and whether it may change
 * now. Pure: the decision is separate from the parameter write and autopilot
 * reboot that act on it, so every rule is testable without an aircraft.
 *
 * An armed aircraft is never re-identified (the change needs an autopilot
 * reboot); an aircraft with no slot is left alone (nobody has authority to
 * assert its identity); one already on the right id is left alone.
 */
object FleetIdentityPolicy {

    /** Why an aircraft's identity is or is not being changed. */
    sealed interface Decision {
        data object AlreadyCorrect : Decision
        data class Adopt(val target: Int) : Decision
        data class DeferredArmed(val target: Int) : Decision
        data object NoSlot : Decision
        data class UnusableSlot(val slot: Int) : Decision
    }

    /**
     * The system id an aircraft on [slot] should carry: the slot itself. Null
     * for 0 (MAVLink broadcast) and 255 (by convention the ground station).
     */
    fun systemIdForSlot(slot: Int): Int? = when (slot) {
        0, 255 -> null
        in 1..254 -> slot
        else -> null
    }

    fun decide(slot: Int?, current: Int, armed: Boolean): Decision {
        if (slot == null) return Decision.NoSlot
        val target = systemIdForSlot(slot) ?: return Decision.UnusableSlot(slot)
        if (current == target) return Decision.AlreadyCorrect
        if (armed) return Decision.DeferredArmed(target)
        return Decision.Adopt(target)
    }

    /**
     * The system ids carried by more than one aircraft, ascending; empty when
     * the fleet is unambiguous. [observed] is (slot, system id) as the
     * aircraft present themselves on the air.
     */
    fun collidingIdentities(observed: List<Pair<Int, Int>>): List<Int> =
        observed.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys.sorted()
}
