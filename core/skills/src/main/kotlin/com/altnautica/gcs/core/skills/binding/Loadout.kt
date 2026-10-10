package com.altnautica.gcs.core.skills.binding

import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.core.skills.input.ReservedInputs
import kotlinx.serialization.Serializable

@Serializable
data class LoadoutSlot(
    val index: Int,
    val skillId: String?,
    val keyChord: String?,
    val gamepadButton: Int?,
)

/** The Skill Bar: [SLOT_COUNT] slots plus the extension skills already offered a default binding. */
@Serializable
data class Loadout(
    val slots: List<LoadoutSlot>,
    val seededSkillIds: List<String> = emptyList(),
) {
    fun slotFor(skillId: String): LoadoutSlot? = slots.firstOrNull { it.skillId == skillId }

    fun slotForChord(chord: String): LoadoutSlot? = slots.firstOrNull { it.keyChord == chord && it.skillId != null }

    fun slotForButton(button: Int): LoadoutSlot? = slots.firstOrNull { it.gamepadButton == button && it.skillId != null }

    companion object {
        const val SLOT_COUNT = 12

        /**
         * Factory loadout: ten built-ins, two free slots for extension skills. Slot 0
         * "arm" also disarms when the vehicle is armed (the dispatcher flips it).
         */
        val DEFAULT = Loadout(
            slots = listOf(
                LoadoutSlot(0, "arm", "shift+a", GamepadButton.A),
                LoadoutSlot(1, "takeoff", "shift+t", null),
                LoadoutSlot(2, "land", "shift+l", GamepadButton.X),
                LoadoutSlot(3, "rth", "shift+r", GamepadButton.B),
                LoadoutSlot(4, "pause", "shift+p", GamepadButton.Y),
                LoadoutSlot(5, "abort", "shift+x", null),
                LoadoutSlot(6, "mode.loiter", "f1", null),
                LoadoutSlot(7, "mode.althold", "f2", null),
                LoadoutSlot(8, "mode.stabilize", "f3", null),
                LoadoutSlot(9, "kill", null, null),
                LoadoutSlot(10, null, null, null),
                LoadoutSlot(11, null, null, null),
            ),
        )

        val BUILTIN_CHORDS: Set<String> = DEFAULT.slots.mapNotNull { it.keyChord }.toSet()
        val BUILTIN_BUTTONS: Set<Int> = DEFAULT.slots.mapNotNull { it.gamepadButton }.toSet()
    }
}

enum class SeedOutcome {
    /** Slotted with every suggested input. */
    Bound,

    /** Slotted, but a suggested chord or button collided and was left unbound. */
    Unbound,

    /** Already offered, already placed, or no free slot yet. */
    Skipped,
}

enum class BindResult { Ok, Reserved, OutOfRange, NoSlot }

/** Pads a loadout saved with fewer slots up to [Loadout.SLOT_COUNT]. */
fun Loadout.normalized(): Loadout {
    if (slots.size >= Loadout.SLOT_COUNT) return this
    val padded = slots.toMutableList()
    for (index in slots.size until Loadout.SLOT_COUNT) padded += LoadoutSlot(index, null, null, null)
    return copy(slots = padded)
}

/**
 * Offers an extension skill's suggested binding. The skill takes the first empty
 * slot; a suggested chord or button is dropped when a built-in owns it, the cockpit
 * reserves it, or another slot holds it. Each skill is offered once.
 */
fun Loadout.seed(skillId: String, binding: Binding?): Pair<Loadout, SeedOutcome> {
    val base = normalized()
    if (skillId in base.seededSkillIds) return base to SeedOutcome.Skipped
    val seeded = base.seededSkillIds + skillId
    if (base.slots.any { it.skillId == skillId }) return base.copy(seededSkillIds = seeded) to SeedOutcome.Skipped
    val empty = base.slots.firstOrNull { it.skillId == null } ?: return base to SeedOutcome.Skipped

    val wantedKey = binding?.keyChord?.takeIf { it.isNotBlank() }
    val key = wantedKey?.takeIf { chord ->
        chord !in Loadout.BUILTIN_CHORDS && !ReservedInputs.isReservedChord(chord) && base.slots.none { it.keyChord == chord }
    }
    val wantedButton = binding?.gamepadButton
    val button = wantedButton?.takeIf { b ->
        b in 0..GamepadButton.MAX_BINDABLE && !ReservedInputs.isReservedButton(b) &&
            b !in Loadout.BUILTIN_BUTTONS && base.slots.none { it.gamepadButton == b }
    }
    val slots = base.slots.map { slot ->
        if (slot.index != empty.index) slot
        else slot.copy(skillId = skillId, keyChord = key ?: slot.keyChord, gamepadButton = button ?: slot.gamepadButton)
    }
    val outcome = if ((wantedKey != null && key == null) || (wantedButton != null && button == null)) SeedOutcome.Unbound else SeedOutcome.Bound
    return Loadout(slots, seeded) to outcome
}

/** Puts [skillId] (or nothing) into slot [index]; a skill already slotted elsewhere moves. */
fun Loadout.assign(index: Int, skillId: String?): Loadout {
    val base = normalized()
    return base.copy(
        slots = base.slots.map { slot ->
            when {
                slot.index == index -> slot.copy(skillId = skillId)
                skillId != null && slot.skillId == skillId -> slot.copy(skillId = null)
                else -> slot
            }
        },
    )
}

/** Binds [chord] (null clears) to slot [index]; the chord moves off any other slot. */
fun Loadout.bindChord(index: Int, chord: String?): Pair<Loadout, BindResult> {
    val base = normalized()
    if (base.slots.none { it.index == index }) return base to BindResult.NoSlot
    if (chord != null && ReservedInputs.isReservedChord(chord)) return base to BindResult.Reserved
    return base.copy(
        slots = base.slots.map { slot ->
            when {
                slot.index == index -> slot.copy(keyChord = chord)
                chord != null && slot.keyChord == chord -> slot.copy(keyChord = null)
                else -> slot
            }
        },
    ) to BindResult.Ok
}

/** Binds gamepad [button] (null clears) to slot [index]; the button moves off any other slot. */
fun Loadout.bindButton(index: Int, button: Int?): Pair<Loadout, BindResult> {
    val base = normalized()
    if (base.slots.none { it.index == index }) return base to BindResult.NoSlot
    if (button != null) {
        if (button !in 0..GamepadButton.MAX_BINDABLE) return base to BindResult.OutOfRange
        if (ReservedInputs.isReservedButton(button)) return base to BindResult.Reserved
    }
    return base.copy(
        slots = base.slots.map { slot ->
            when {
                slot.index == index -> slot.copy(gamepadButton = button)
                button != null && slot.gamepadButton == button -> slot.copy(gamepadButton = null)
                else -> slot
            }
        },
    ) to BindResult.Ok
}
