package com.altnautica.gcs.core.skills.binding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Persists the Skill Bar loadout (slots, chords, gamepad buttons) in the app settings store. */
@Singleton
class BindingStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val loadout: Flow<Loadout> = dataStore.data
        .map { prefs -> decode(prefs[KEY]) }
        .distinctUntilChanged()

    private fun decode(raw: String?): Loadout {
        if (raw == null) return Loadout.DEFAULT
        return try {
            json.decodeFromString(Loadout.serializer(), raw).normalized()
        } catch (_: SerializationException) {
            Loadout.DEFAULT
        } catch (_: IllegalArgumentException) {
            Loadout.DEFAULT
        }
    }

    private suspend fun <R> update(transform: (Loadout) -> Pair<Loadout, R>): R {
        var result: R? = null
        dataStore.edit { prefs ->
            val (next, r) = transform(decode(prefs[KEY]))
            prefs[KEY] = json.encodeToString(Loadout.serializer(), next)
            result = r
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    suspend fun assign(slotIndex: Int, skillId: String?) = update { it.assign(slotIndex, skillId) to Unit }

    suspend fun bindChord(slotIndex: Int, chord: String?): BindResult = update { it.bindChord(slotIndex, chord) }

    suspend fun bindButton(slotIndex: Int, button: Int?): BindResult = update { it.bindButton(slotIndex, button) }

    /** Restores the factory slots; extension skills are offered their suggested bindings again. */
    suspend fun resetToDefaults() = update { Loadout.DEFAULT to Unit }

    /**
     * Offers every extension skill's suggested binding once, into empty slots.
     * Returns the outcome per skill that was newly slotted.
     */
    suspend fun seedExtensionDefaults(skills: List<Skill>): Map<String, SeedOutcome> = update { start ->
        var loadout = start
        val outcomes = LinkedHashMap<String, SeedOutcome>()
        for (skill in skills) {
            if (skill.source !is SkillSource.Extension) continue
            val (next, outcome) = loadout.seed(skill.id, skill.defaultBinding)
            loadout = next
            if (outcome != SeedOutcome.Skipped) outcomes[skill.id] = outcome
        }
        loadout to outcomes
    }

    private companion object {
        val KEY = stringPreferencesKey("skills.loadout.v1")
    }
}
