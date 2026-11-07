package com.altnautica.gcs.core.skills.controls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.binding.BindingConflict
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.Ds4Spec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Outcome of saving an edited binding list. */
sealed interface SaveResult {
    data object Saved : SaveResult

    /** Nothing was written: the list has these problems. */
    data class Refused(val problems: List<BindingProblem>) : SaveResult
}

/**
 * The operator's controller preferences, stored in the app settings under
 * `controls.ds4.*`: the binding list (absent while the shipped map applies), stick
 * mode, radial dead zone, per-pad calibration, the on-screen sticks flag and whether
 * the first-connect tour was seen. Bindings are stored and exported as the complete
 * effective list, never a diff, and a list that drops a locked action or holds a
 * conflict is never written.
 */
@Singleton
class ControlsRepository(
    private val dataStore: DataStore<Preferences>,
    private val spec: CockpitSpecRepository,
    private val skills: Flow<List<Skill>>,
) {
    @Inject
    constructor(dataStore: DataStore<Preferences>, spec: CockpitSpecRepository, registry: SkillRegistry) :
        this(dataStore, spec, registry.skills)

    private val ds4: Ds4Spec get() = spec.ds4

    private val stored: Flow<Stored> = dataStore.data.map(::decode).distinctUntilChanged()

    val state: Flow<ControlsState> = combine(stored, skills) { s, installed ->
        val resolved = Ds4Bindings.resolve(ds4, s.bindings, installed)
        ControlsState(
            bindings = resolved.rows,
            conflicts = resolved.conflicts,
            profile = if (s.bindings == null) ControlsProfile.AdosDefault else ControlsProfile.User,
            stickMode = s.stickMode,
            deadzone = s.deadzone,
            calibrations = s.calibrations,
            onScreenSticks = s.onScreenSticks,
            tourSeen = s.tourSeen,
        )
    }.distinctUntilChanged()

    /** The complete effective binding table the cockpit dispatches from. */
    val bindings: Flow<List<ControlBindingSpec>> = state.map { it.bindings }.distinctUntilChanged()

    /** Rows of [bindings] that fire on the same press. */
    val conflicts: Flow<List<BindingConflict>> = state.map { it.conflicts }.distinctUntilChanged()

    val stickMode: Flow<StickMode> = stored.map { it.stickMode }.distinctUntilChanged()

    /** Radial stick dead zone, inside the `ds4.json` range (0.04..0.20, default 0.08). */
    val deadzone: Flow<Float> = stored.map { it.deadzone }.distinctUntilChanged()

    /** Calibration per [padKey]; a pad without an entry needs none. */
    val calibrations: Flow<Map<String, PadCalibration>> = stored.map { it.calibrations }.distinctUntilChanged()

    /** Touch thumb pads in the cockpit for manual flight without a controller. */
    val onScreenSticks: Flow<Boolean> = stored.map { it.onScreenSticks }.distinctUntilChanged()

    val tourSeen: Flow<Boolean> = stored.map { it.tourSeen }.distinctUntilChanged()

    /**
     * Saves [rows] as the binding list after checking it: refused, with nothing written,
     * when a locked action is missing or two rows conflict. A list equal to the shipped
     * map clears the override so the profile reads ADOS default again.
     */
    suspend fun saveBindings(rows: List<ControlBindingSpec>): SaveResult {
        val installed = skills.first()
        val problems = ds4.check(Ds4Bindings.resolve(ds4, rows, installed))
        if (problems.isNotEmpty()) return SaveResult.Refused(problems)
        val shipped = Ds4Bindings.resolve(ds4, null, installed).rows
        dataStore.edit { p ->
            if (rows == shipped) p.remove(BINDINGS) else p[BINDINGS] = encodeRows(rows)
        }
        return SaveResult.Saved
    }

    /** Back to the shipped binding map. */
    suspend fun resetBindings() {
        dataStore.edit { it.remove(BINDINGS) }
    }

    suspend fun setStickMode(mode: StickMode) {
        dataStore.edit { it[STICK_MODE] = mode.wire }
    }

    suspend fun setDeadzone(value: Float) {
        val clamped = ds4.clampDeadzone(value)
        dataStore.edit { it[DEADZONE] = clamped }
    }

    suspend fun setCalibration(padKey: String, calibration: PadCalibration) {
        dataStore.edit { p -> p[CALIBRATION] = encodeCalibrations(decodeCalibrations(p[CALIBRATION]) + (padKey to calibration)) }
    }

    suspend fun clearCalibration(padKey: String) {
        dataStore.edit { p -> p[CALIBRATION] = encodeCalibrations(decodeCalibrations(p[CALIBRATION]) - padKey) }
    }

    suspend fun setOnScreenSticks(on: Boolean) {
        dataStore.edit { it[ON_SCREEN_STICKS] = on }
    }

    suspend fun setTourSeen(seen: Boolean) {
        dataStore.edit { it[TOUR_SEEN] = seen }
    }

    /** The current bindings, stick mode and dead zone as an export file for [pad]. */
    suspend fun exportFile(pad: PadKind): String {
        val s = state.first()
        val file = ControlsExport(
            pad = pad.wire,
            stickMode = s.stickMode.wire,
            deadzone = s.deadzone,
            profile = s.profile.wire,
            bindings = s.bindings,
        )
        return ExportJson.encodeToString(ControlsExport.serializer(), file)
    }

    /** Reads an export file and, when it validates, applies its bindings, stick mode and dead zone. */
    suspend fun importFile(text: String): ImportResult {
        val result = ds4.readExport(text)
        if (result !is ImportResult.Imported) return result
        val file = result.export
        val shipped = Ds4Bindings.resolve(ds4, null, skills.first()).rows
        dataStore.edit { p ->
            if (file.profile == ControlsProfile.AdosDefault.wire || file.bindings == shipped) {
                p.remove(BINDINGS)
            } else {
                p[BINDINGS] = encodeRows(file.bindings)
            }
            p[STICK_MODE] = file.stickMode
            p[DEADZONE] = ds4.clampDeadzone(file.deadzone)
        }
        return result
    }

    private data class Stored(
        val bindings: List<ControlBindingSpec>?,
        val stickMode: StickMode,
        val deadzone: Float,
        val calibrations: Map<String, PadCalibration>,
        val onScreenSticks: Boolean,
        val tourSeen: Boolean,
    )

    private fun decode(p: Preferences): Stored = Stored(
        bindings = p[BINDINGS]?.let(::decodeRows),
        stickMode = StickMode.fromWire(p[STICK_MODE]) ?: StickMode.fromWire(ds4.defaultMode) ?: StickMode.Mode2,
        deadzone = p[DEADZONE]?.let(ds4::clampDeadzone) ?: ds4.conditioning.radialDeadzone,
        calibrations = decodeCalibrations(p[CALIBRATION]),
        onScreenSticks = p[ON_SCREEN_STICKS] ?: false,
        tourSeen = p[TOUR_SEEN] ?: false,
    )

    private fun encodeRows(rows: List<ControlBindingSpec>): String = ControlsJson.encodeToString(ROWS, rows)

    /** A stored list that no longer parses falls back to the shipped map. */
    private fun decodeRows(raw: String): List<ControlBindingSpec>? = try {
        ControlsJson.decodeFromString(ROWS, raw)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun encodeCalibrations(map: Map<String, PadCalibration>): String = ControlsJson.encodeToString(CALIBRATIONS, map)

    private fun decodeCalibrations(raw: String?): Map<String, PadCalibration> {
        if (raw == null) return emptyMap()
        return try {
            ControlsJson.decodeFromString(CALIBRATIONS, raw)
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

    private companion object {
        val BINDINGS = stringPreferencesKey("controls.ds4.bindings")
        val STICK_MODE = stringPreferencesKey("controls.ds4.stickMode")
        val DEADZONE = floatPreferencesKey("controls.ds4.deadzone")
        val CALIBRATION = stringPreferencesKey("controls.ds4.calibration")
        val ON_SCREEN_STICKS = booleanPreferencesKey("controls.ds4.onScreenSticks")
        val TOUR_SEEN = booleanPreferencesKey("controls.ds4.tourSeen")
        val ROWS = ListSerializer(ControlBindingSpec.serializer())
        val CALIBRATIONS = MapSerializer(String.serializer(), PadCalibration.serializer())
    }
}
