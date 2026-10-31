package com.altnautica.gcs.core.skills.cockpit

import com.altnautica.gcs.core.model.DeviceId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Cockpit-only state shown outside the cockpit (the Quick menu): preflight, alerts, stick control. */
data class CockpitSummary(
    val nodeId: DeviceId,
    val preflightDone: Int,
    val preflightTotal: Int,
    val alertCount: Int,
    val topAlert: String?,
    val stickOn: Boolean,
)

/**
 * The open cockpit publishes its summary here while started and clears it on stop,
 * so other surfaces can read it without depending on the cockpit feature.
 */
@Singleton
class CockpitSummaryRegistry @Inject constructor() {
    private val _summary = MutableStateFlow<CockpitSummary?>(null)

    /** The open cockpit's summary, or null when no cockpit is started. */
    val summary: StateFlow<CockpitSummary?> = _summary.asStateFlow()

    fun publish(summary: CockpitSummary) {
        _summary.value = summary
    }

    /** Clears the summary if it still belongs to [nodeId], so a newer cockpit's summary survives. */
    fun clear(nodeId: DeviceId) {
        _summary.update { if (it?.nodeId == nodeId) null else it }
    }
}
