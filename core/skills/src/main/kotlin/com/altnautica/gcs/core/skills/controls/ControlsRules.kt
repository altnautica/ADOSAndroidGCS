package com.altnautica.gcs.core.skills.controls

import com.altnautica.gcs.core.skills.binding.BindingConflict
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.binding.ResolvedBindings
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.Ds4Spec
import com.altnautica.gcs.core.skills.spec.LockedActionSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Every control of a row's chord, [ControlBindingSpec.control] first. */
val ControlBindingSpec.chord: List<String> get() = listOf(control) + with

/** True for a `ds4.json` action or any well-formed plugin skill action; never for [ACTION_NONE]. */
fun Ds4Spec.isKnownAction(id: String): Boolean =
    id != ACTION_NONE && (actions.any { it.id == id } || Ds4Bindings.parseSkillAction(id) != null)

/** [value] inside the `ds4.json` dead zone range; the default for NaN. */
fun Ds4Spec.clampDeadzone(value: Float): Float =
    if (value.isNaN()) conditioning.radialDeadzone else value.coerceIn(conditioning.deadzoneMin, conditioning.deadzoneMax)

/** Why a binding list cannot be saved. */
sealed interface BindingProblem {
    /** A locked action has no locked row left. */
    data class MissingLocked(val locked: LockedActionSpec) : BindingProblem

    /** Rows that fire on the same press. */
    data class Conflict(val conflict: BindingConflict) : BindingProblem

    data class UnknownAction(val action: String) : BindingProblem

    data class UnknownControl(val control: String) : BindingProblem
}

/** Every reason the [resolved] list cannot be saved; empty when it can. */
fun Ds4Spec.check(resolved: ResolvedBindings): List<BindingProblem> {
    val rows = resolved.rows
    val problems = ArrayList<BindingProblem>()
    Ds4Bindings.missingLocked(this, rows).forEach { problems += BindingProblem.MissingLocked(it) }
    resolved.conflicts.forEach { problems += BindingProblem.Conflict(it) }
    rows.map { it.action }.distinct().filterNot(::isKnownAction).forEach { problems += BindingProblem.UnknownAction(it) }
    rows.flatMap { it.chord }.distinct().filterNot { it in controls }.forEach { problems += BindingProblem.UnknownControl(it) }
    return problems
}

/** Why an editor change was not applied. */
enum class EditRefusal {
    /** The row is locked: it can move to another control, but its action cannot change or be removed. */
    Locked,

    /** The move target is already part of the row's chord. */
    SameControl,

    /** The row is no longer in the list (a stale edit). */
    Missing,
}

sealed interface EditResult {
    data class Applied(val rows: List<ControlBindingSpec>) : EditResult

    data class Refused(val reason: EditRefusal) : EditResult
}

/**
 * Binds [action] to [row]; [ACTION_NONE] removes the row. A locked row refuses both a new action and removal.
 * A row bound to the emergency stop becomes a hold of at least [stopHoldMs] ([Ds4Bindings.stopHoldRow]).
 */
fun setRowAction(rows: List<ControlBindingSpec>, row: ControlBindingSpec, action: String, stopHoldMs: Int): EditResult {
    val index = rows.indexOf(row)
    if (index < 0) return EditResult.Refused(EditRefusal.Missing)
    if (row.locked && action != row.action) return EditResult.Refused(EditRefusal.Locked)
    val next = rows.toMutableList()
    if (action == ACTION_NONE) next.removeAt(index) else next[index] = Ds4Bindings.stopHoldRow(row.copy(action = action), stopHoldMs)
    return EditResult.Applied(next)
}

/**
 * Adds a plain row binding [action] to [control] with [gesture]; [ACTION_NONE] changes nothing.
 * A row bound to the emergency stop is added as a hold of at least [stopHoldMs] ([Ds4Bindings.stopHoldRow]).
 */
fun addRow(rows: List<ControlBindingSpec>, control: String, gesture: String, action: String, stopHoldMs: Int): EditResult {
    if (action == ACTION_NONE) return EditResult.Applied(rows)
    val holdMs = if (gesture == Gesture.HOLD) Gesture.DEFAULT_HOLD_MS else null
    val row = ControlBindingSpec(
        control = control,
        with = emptyList(),
        gesture = gesture,
        holdMs = holdMs,
        whenCondition = null,
        action = action,
        rumble = null,
        locked = false,
    )
    return EditResult.Applied(rows + Ds4Bindings.stopHoldRow(row, stopHoldMs))
}

/**
 * Moves [row] to [target], keeping its gesture, held controls, condition and lock. The
 * result may collide with a row already on [target]; resolving the list reports it.
 */
fun moveRow(rows: List<ControlBindingSpec>, row: ControlBindingSpec, target: String): EditResult {
    val index = rows.indexOf(row)
    if (index < 0) return EditResult.Refused(EditRefusal.Missing)
    if (target in row.chord) return EditResult.Refused(EditRefusal.SameControl)
    val next = rows.toMutableList()
    next[index] = row.copy(control = target)
    return EditResult.Applied(next)
}

/** The Settings › Controls export file; also the ground-station bindings body. */
@Serializable
data class ControlsExport(
    val v: Int = EXPORT_VERSION,
    val kind: String = EXPORT_KIND,
    val pad: String,
    @SerialName("stick_mode") val stickMode: String,
    val deadzone: Float,
    val profile: String,
    val bindings: List<ControlBindingSpec>,
) {
    companion object {
        const val EXPORT_VERSION = 1
        const val EXPORT_KIND = "ados-controls"
    }
}

/** Outcome of reading an export file. */
sealed interface ImportResult {
    data class Imported(val export: ControlsExport) : ImportResult

    /** Not JSON, not an `ados-controls` v1 file, or an unknown stick mode. */
    data object Unreadable : ImportResult

    /** Readable but refused: missing locked actions, conflicts, unknown actions or controls. */
    data class Refused(val problems: List<BindingProblem>) : ImportResult
}

/**
 * Reads [text] as an export file and validates its bindings against this map. A row bound to
 * the emergency stop is read as a hold of at least [stopHoldMs] ([Ds4Bindings.stopHoldRow]).
 */
fun Ds4Spec.readExport(text: String, stopHoldMs: Int): ImportResult {
    val file = try {
        ControlsJson.decodeFromString(ControlsExport.serializer(), text)
    } catch (_: SerializationException) {
        return ImportResult.Unreadable
    } catch (_: IllegalArgumentException) {
        return ImportResult.Unreadable
    }
    if (file.v != ControlsExport.EXPORT_VERSION || file.kind != ControlsExport.EXPORT_KIND) return ImportResult.Unreadable
    if (StickMode.fromWire(file.stickMode) == null) return ImportResult.Unreadable
    val export = file.copy(bindings = file.bindings.map { Ds4Bindings.stopHoldRow(it, stopHoldMs) })
    val problems = check(Ds4Bindings.resolve(this, export.bindings, emptyList()))
    return if (problems.isEmpty()) ImportResult.Imported(export) else ImportResult.Refused(problems)
}

/** JSON of the stored bindings and the export file: every field written, unknown ones ignored. */
internal val ControlsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** [ControlsJson] indented for a file the operator may read. */
internal val ExportJson = Json(ControlsJson) { prettyPrint = true }
