package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyControlApi
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy.Companion.ECHO_TIMEOUT_MS
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy.Companion.FOLLOW_PLUGIN_ID
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy.Companion.PREDICTIVE_KEY
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy.Companion.REACQUIRE_KEY
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy.Companion.UNCERTAIN_KEY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Follow-me settings (re-acquire mode, when unsure, predictive search) written to the flown node. */
internal class FollowSettings(
    private val scope: CoroutineScope,
    private val node: StateFlow<DeviceId?>,
    private val feed: AutonomyFeed,
    private val repository: ExtensionRepository,
    private val events: MutableSharedFlow<CockpitEvent>,
) {
    private val pendingSettings = MutableStateFlow<Map<String, JsonPrimitive>>(emptyMap())

    /** Written follow-me settings not yet echoed by `follow.state`, by config key; the rail outlines them dashed. */
    val pending: StateFlow<Map<String, JsonPrimitive>> = pendingSettings.asStateFlow()
    private val pendingJobs = HashMap<String, Job>()

    /**
     * Writes follow-me setting [key] = [value] (`reacquire_mode`, `uncertain_mode`,
     * `predictive_search`). It shows pending until `follow.state` echoes it; a refused write or
     * no echo within [ECHO_TIMEOUT_MS] clears it with [CockpitEvent.FollowSettingNotApplied].
     */
    fun set(key: String, value: JsonPrimitive) {
        node.value ?: return
        pendingSettings.update { it + (key to value) }
        pendingJobs.remove(key)?.cancel()
        pendingJobs[key] = scope.launch {
            val ok = write(key, value)
            if (ok) delay(ECHO_TIMEOUT_MS)
            if (pendingSettings.value[key] == value) {
                pendingSettings.update { it - key }
                events.tryEmit(CockpitEvent.FollowSettingNotApplied(key))
            }
        }
    }

    /** Writes one follow-me config key on the flown node; true once the node took it. */
    suspend fun write(key: String, value: JsonPrimitive): Boolean {
        val id = node.value ?: return false
        val local = feed.local(id)
        return if (local != null) {
            (local as? AutonomyControlApi)?.putPluginConfig(FOLLOW_PLUGIN_ID, key, value) is AgentResult.Ok
        } else {
            repository.writeConfig(id, FOLLOW_PLUGIN_ID, key, value) == WriteOutcome.Done
        }
    }

    /** Clears the pending settings [f] echoes. */
    fun settleEchoed(f: FollowState) {
        val pending = pendingSettings.value
        if (pending.isEmpty()) return
        val echoed = pending.filter { (key, want) -> echo(f, key) == want }.keys
        if (echoed.isEmpty()) return
        echoed.forEach { pendingJobs.remove(it)?.cancel() }
        pendingSettings.update { it - echoed }
    }

    /** The value `follow.state` reports for setting [key], as written. */
    private fun echo(f: FollowState, key: String): JsonPrimitive? = when (key) {
        REACQUIRE_KEY -> f.reacquireMode?.let(::JsonPrimitive)
        UNCERTAIN_KEY -> f.uncertainMode?.let(::JsonPrimitive)
        PREDICTIVE_KEY -> f.predictiveSearch?.let(::JsonPrimitive)
        else -> null
    }
}
