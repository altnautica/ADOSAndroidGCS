package com.altnautica.gcs.core.skills.binding

import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.skills.controls.Gesture
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.Ds4Spec
import com.altnautica.gcs.core.skills.spec.LayoutSpec
import com.altnautica.gcs.core.skills.spec.LockedActionSpec

/**
 * Two or more rows on the same chord, gesture and overlapping condition. [unbound] is a
 * skill's suggested row left out of the table because the chord was already taken.
 */
data class BindingConflict(val rows: List<ControlBindingSpec>, val unbound: ControlBindingSpec?) {
    /** Every control involved, for outlining hotspots. */
    val controls: Set<String> get() = rows.flatMapTo(LinkedHashSet()) { listOf(it.control) + it.with }
}

/** The effective controller table and the conflicts it contains or caused. */
data class ResolvedBindings(val rows: List<ControlBindingSpec>, val conflicts: List<BindingConflict>)

/**
 * Controller binding rules shared by every surface. A row's chord is its control plus
 * the controls held with it; two rows conflict when their chords and gestures are equal
 * and their conditions overlap (a null condition overlaps every condition; two different
 * conditions never overlap).
 */
object Ds4Bindings {
    private const val SKILL_PREFIX = "skill:"

    /** Binding action that activates [skill]: `skill:<plugin_id>:<skill_id>`. */
    fun skillAction(skill: Skill): String = SKILL_PREFIX + skill.id

    /** The registry skill id a `skill:` action names, or null for any other action. */
    fun parseSkillAction(action: String): String? =
        action.removePrefix(SKILL_PREFIX).takeIf { action.startsWith(SKILL_PREFIX) && ':' in it }

    fun conflicts(a: ControlBindingSpec, b: ControlBindingSpec): Boolean =
        chord(a) == chord(b) && a.gesture == b.gesture && whenOverlaps(a.whenCondition, b.whenCondition)

    private fun chord(row: ControlBindingSpec): List<String> = (row.with + row.control).sorted()

    private fun whenOverlaps(a: String?, b: String?): Boolean = a == null || b == null || a == b

    /**
     * The effective table: [overrides] (the operator's complete list) or the spec
     * defaults, plus a tap row for every extension skill whose suggested gamepad button
     * is free. A suggested button already taken leaves the skill unbound and is listed.
     */
    fun resolve(spec: Ds4Spec, overrides: List<ControlBindingSpec>?, skills: List<Skill>): ResolvedBindings {
        val rows = (overrides ?: spec.bindings).toMutableList()
        val conflicts = ArrayList<BindingConflict>()
        for (i in rows.indices) {
            for (j in i + 1 until rows.size) {
                if (conflicts(rows[i], rows[j])) conflicts += BindingConflict(listOf(rows[i], rows[j]), unbound = null)
            }
        }
        val controlByIndex = spec.stream.buttonsBitfield.entries.associate { (control, index) -> index to control }
        val bound = rows.mapTo(HashSet()) { it.action }
        for (skill in skills) {
            if (skill.source !is SkillSource.Extension) continue
            val action = skillAction(skill)
            if (action in bound) continue
            val control = skill.defaultBinding?.gamepadButton?.let(controlByIndex::get) ?: continue
            val row = ControlBindingSpec(
                control = control, with = emptyList(), gesture = GESTURE_TAP, holdMs = null,
                whenCondition = null, action = action, rumble = null, locked = false,
            )
            val taken = rows.filter { conflicts(it, row) }
            if (taken.isEmpty()) {
                rows += row
                bound += action
            } else {
                conflicts += BindingConflict(taken + row, unbound = row)
            }
        }
        return ResolvedBindings(rows, conflicts)
    }

    /**
     * Locked entries no row satisfies. An entry is satisfied by a row marked locked with
     * the same action and gesture, held with another control when the entry is a combo.
     */
    fun missingLocked(spec: Ds4Spec, rows: List<ControlBindingSpec>): List<LockedActionSpec> =
        spec.lockedActions.filter { entry ->
            rows.none { it.locked && it.action == entry.action && it.gesture == entry.gesture && (!entry.combo || it.with.isNotEmpty()) }
        }

    const val GESTURE_TAP = "tap"

    /** The `ds4.json` action of the emergency stop. */
    const val STOP_ACTION = "kill"

    /** The `layout.json` `holds_ms` key of the emergency stop's hold. */
    const val STOP_HOLD_KEY = "stop"

    private const val RUMBLE_RAMP = "ramp"

    /**
     * [row] as it runs: a row bound to the emergency stop is always a hold of at least
     * [stopHoldMs]. A press, tap or double-tap row becomes a hold of [stopHoldMs] (with the
     * ramp rumble unless it names one), and a shorter hold is raised to it. Every other row
     * is returned unchanged (the same object).
     */
    fun stopHoldRow(row: ControlBindingSpec, stopHoldMs: Int): ControlBindingSpec {
        if (row.action != STOP_ACTION || row.gesture == Gesture.ANALOG) return row
        if (row.gesture == Gesture.HOLD && (row.holdMs ?: 0) >= stopHoldMs) return row
        return row.copy(gesture = Gesture.HOLD, holdMs = stopHoldMs, rumble = row.rumble ?: RUMBLE_RAMP)
    }

    /** The emergency stop's hold from `layout.json` `holds_ms`. */
    fun stopHoldMs(layout: LayoutSpec): Int = layout.holdsMs.getValue(STOP_HOLD_KEY)

    /** Bitmask (standard index) of every control any of [rows] uses, held controls included. */
    fun boundButtonMask(spec: Ds4Spec, rows: List<ControlBindingSpec>): Int {
        val index = spec.stream.buttonsBitfield
        return rows.fold(0) { acc, row ->
            (row.with + row.control).fold(acc) { m, control -> index[control]?.let { m or (1 shl it) } ?: m }
        }
    }

    /**
     * `MANUAL_CONTROL` button bitmask: the first 16 pressed buttons minus every button the
     * cockpit table binds, so a cockpit action never also reaches the flight controller.
     */
    fun manualControlButtonMask(pressed: Int, boundMask: Int): Int = pressed and 0xFFFF and boundMask.inv()
}
