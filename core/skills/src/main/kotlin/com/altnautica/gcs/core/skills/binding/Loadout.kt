package com.altnautica.gcs.core.skills.binding

import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.skills.builtin.ProfileSkill
import com.altnautica.gcs.core.skills.input.ReservedInputs
import com.altnautica.gcs.core.skills.spec.BuiltinsSpec
import kotlinx.serialization.Serializable

/** One keyboard hotkey slot. Gamepad bindings live in the controller table ([Ds4Bindings]). */
@Serializable
data class LoadoutSlot(
    val index: Int,
    val skillId: String?,
    val keyChord: String?,
)

/** A suggested chord left unbound because [holder] (a skill id, or a built-in chord) already had it. */
@Serializable
data class KeyConflict(val chord: String, val holder: String, val skillId: String)

/**
 * The keyboard hotkey loadout: [SLOT_COUNT] slots, the extension skills already offered
 * their suggested chord, and the suggestions that collided.
 */
@Serializable
data class Loadout(
    val slots: List<LoadoutSlot>,
    val seededSkillIds: List<String> = emptyList(),
    val conflicts: List<KeyConflict> = emptyList(),
) {
    fun slotFor(skillId: String): LoadoutSlot? = slots.firstOrNull { it.skillId == skillId }

    fun slotForChord(chord: String): LoadoutSlot? = slots.firstOrNull { it.keyChord == chord && it.skillId != null }

    companion object {
        const val SLOT_COUNT = 12

        val EMPTY = Loadout(slots = List(SLOT_COUNT) { LoadoutSlot(it, null, null) })

        /**
         * Factory loadout: every built-in with a keyboard chord in table order, then the
         * profile keys (F1–F3), then free slots for extension skills.
         */
        fun defaults(spec: BuiltinsSpec): Loadout {
            val bound = spec.builtins.mapNotNull { b -> b.keyboard?.let { b.id to it } } +
                spec.profileKeys.entries.sortedBy { it.key }.map { (key, action) -> ProfileSkill.idFor(action) to key }
            val slots = List(SLOT_COUNT) { index ->
                val (skillId, chord) = bound.getOrNull(index) ?: (null to null)
                LoadoutSlot(index, skillId, chord)
            }
            return Loadout(slots)
        }

        /** Chords the built-in table owns; an extension suggestion never takes one. */
        fun builtinChords(spec: BuiltinsSpec): Set<String> =
            (spec.builtins.mapNotNull { it.keyboard } + spec.profileKeys.keys).toSet()
    }
}

enum class SeedOutcome {
    /** Slotted with its suggested chord. */
    Bound,

    /** Slotted, but the suggested chord collided and was left unbound (listed in [Loadout.conflicts]). */
    Unbound,

    /** Already offered, already placed, or no free slot yet. */
    Skipped,
}

enum class BindResult { Ok, Reserved, NoSlot }

/** Pads a loadout saved with fewer slots up to [Loadout.SLOT_COUNT]. */
fun Loadout.normalized(): Loadout {
    if (slots.size >= Loadout.SLOT_COUNT) return this
    val padded = slots.toMutableList()
    for (index in slots.size until Loadout.SLOT_COUNT) padded += LoadoutSlot(index, null, null)
    return copy(slots = padded)
}

/**
 * Offers an extension skill's suggested chord. The skill takes the first empty slot; a
 * chord a built-in owns ([builtinChords]), the cockpit reserves, or another slot holds
 * leaves the later skill unbound and records the conflict. Each skill is offered once.
 */
fun Loadout.seed(skillId: String, binding: Binding?, builtinChords: Set<String>): Pair<Loadout, SeedOutcome> {
    val base = normalized()
    if (skillId in base.seededSkillIds) return base to SeedOutcome.Skipped
    val seeded = base.seededSkillIds + skillId
    if (base.slots.any { it.skillId == skillId }) return base.copy(seededSkillIds = seeded) to SeedOutcome.Skipped
    val empty = base.slots.firstOrNull { it.skillId == null } ?: return base to SeedOutcome.Skipped

    val wanted = binding?.keyChord?.takeIf { it.isNotBlank() }
    val holder = wanted?.let { chord ->
        when {
            ReservedInputs.isReservedChord(chord) -> RESERVED_HOLDER
            chord in builtinChords -> base.slots.firstOrNull { it.keyChord == chord }?.skillId ?: BUILTIN_HOLDER
            else -> base.slots.firstOrNull { it.keyChord == chord }?.let { it.skillId ?: BUILTIN_HOLDER }
        }
    }
    val key = wanted?.takeIf { holder == null }
    val slots = base.slots.map { slot ->
        if (slot.index != empty.index) slot else slot.copy(skillId = skillId, keyChord = key ?: slot.keyChord)
    }
    val conflicts = if (wanted != null && holder != null) base.conflicts + KeyConflict(wanted, holder, skillId) else base.conflicts
    val outcome = if (wanted != null && key == null) SeedOutcome.Unbound else SeedOutcome.Bound
    return Loadout(slots, seeded, conflicts) to outcome
}

/** Holder of a conflict on a chord the cockpit reserves. */
const val RESERVED_HOLDER = "reserved"

/** Holder of a conflict on a built-in chord no slot currently holds. */
const val BUILTIN_HOLDER = "builtin"

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

/**
 * Binds [chord] (null clears) to slot [index]; the chord moves off any other slot. An
 * explicit binding resolves any recorded conflict of the slot's skill.
 */
fun Loadout.bindChord(index: Int, chord: String?): Pair<Loadout, BindResult> {
    val base = normalized()
    val target = base.slots.firstOrNull { it.index == index } ?: return base to BindResult.NoSlot
    if (chord != null && ReservedInputs.isReservedChord(chord)) return base to BindResult.Reserved
    return base.copy(
        slots = base.slots.map { slot ->
            when {
                slot.index == index -> slot.copy(keyChord = chord)
                chord != null && slot.keyChord == chord -> slot.copy(keyChord = null)
                else -> slot
            }
        },
        conflicts = base.conflicts.filterNot { it.skillId == target.skillId },
    ) to BindResult.Ok
}
