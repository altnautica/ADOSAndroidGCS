package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.cockpit.CockpitSummary
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publishes the cockpit's summary for [nodeId] (checklist, alerts, sticks and mission) to
 * [summaries] while the returned job runs.
 */
internal fun CoroutineScope.publishCockpitSummary(
    nodeId: DeviceId,
    pilot: Flow<PilotState?>,
    preflight: Flow<PreflightResult>,
    alerts: Flow<List<CockpitAlert>>,
    stickOn: Flow<Boolean>,
    alertText: (CockpitAlert) -> String,
    summaries: CockpitSummaryRegistry,
): Job = launch {
    // The mission without its age, so the summary changes only when what it shows does.
    val mission = pilot.map { it?.mission?.copy(ageMs = null) }.distinctUntilChanged()
    combine(preflight, alerts, stickOn, mission) { p, a, on, m ->
        CockpitSummary(nodeId, p.passed, p.total, a.size, a.firstOrNull()?.let(alertText), on, m)
    }.distinctUntilChanged().collect(summaries::publish)
}
