package com.altnautica.gcs.feature.node.visor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.agent.sensors.ClockStatus
import com.altnautica.gcs.core.agent.sensors.SensorsStatus
import com.altnautica.gcs.core.agent.sensors.StorageStatus
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt
import com.altnautica.gcs.feature.node.groundstation.Load

/** Visor rows on the Overview tab: clock lock, capture storage, rig sensors. */
@Composable
fun VisorOverviewCards(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<VisorViewModel, VisorViewModel.Factory>(key = "visor-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.show(NodeTab.Overview)
        onDispose { vm.show(null) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        when (val load = state.overview) {
            Load.Loading -> LoadingState(stringResource(R.string.node_loading))
            is Load.Failed -> ProblemState(load.problem, onRetry = { vm.show(NodeTab.Overview) })
            is Load.Ready -> {
                ClockCard(load.value.clock)
                StorageCard(load.value.storage)
                SensorsCard(load.value.sensors, load.value.slowSensors)
            }
        }
    }
}

@Composable
private fun ClockCard(clock: ClockStatus?) {
    NodeCard(stringResource(R.string.visor_clock)) {
        if (clock == null) return@NodeCard NotFitted()
        StatusChip(
            if (clock.locked) StatusLevel.Success else StatusLevel.Warning,
            stringResource(if (clock.locked) R.string.visor_clock_locked else R.string.visor_clock_unlocked),
        )
        KeyValueRow(stringResource(R.string.visor_clock_timebase), clock.timebase)
        KeyValueRow(stringResource(R.string.visor_clock_sigma), sigmaMs(clock.sigmaNs))
        clock.sources.forEach { s ->
            KeyValueRow(
                stringResource(R.string.visor_clock_source, s.id),
                if (s.locked) {
                    stringResource(R.string.visor_clock_source_locked, sigmaMs(s.sigmaNs) ?: stringResource(R.string.visor_unmeasured))
                } else {
                    stringResource(R.string.visor_clock_unlocked)
                },
            )
        }
    }
}

@Composable
private fun StorageCard(storage: StorageStatus?) {
    NodeCard(stringResource(R.string.visor_storage)) {
        if (storage == null) return@NodeCard NotFitted()
        val cap = storage.capture
        val (level, label) = when (cap?.state) {
            "full" -> StatusLevel.Error to R.string.visor_storage_full
            "near_full" -> StatusLevel.Warning to R.string.visor_storage_near_full
            "ok" -> StatusLevel.Success to R.string.visor_storage_ok
            else -> StatusLevel.Neutral to R.string.visor_storage_unknown
        }
        StatusChip(level, stringResource(label))
        KeyValueRow(stringResource(R.string.visor_storage_root), storage.captureRoot)
        KeyValueRow(stringResource(R.string.visor_storage_used), formatBytes(cap?.usedBytes))
        KeyValueRow(stringResource(R.string.visor_storage_budget), formatBytes(cap?.budgetBytes))
        val volume = storage.volumes.firstOrNull { it.isCapture }
        KeyValueRow(stringResource(R.string.visor_storage_free), formatBytes(volume?.freeBytes))
        KeyValueRow(stringResource(R.string.visor_storage_sessions), cap?.let { c -> c.sessions?.let { "${c.uploaded ?: 0} / $it" } })
        KeyValueRow(stringResource(R.string.visor_storage_write), storage.writeProbe?.mbps.fmt(0, " MB/s"))
    }
}

@Composable
private fun SensorsCard(sensors: SensorsStatus?, slow: Int) {
    NodeCard(stringResource(R.string.visor_sensors)) {
        if (sensors == null) return@NodeCard NotFitted()
        KeyValueRow(stringResource(R.string.visor_sensors_rig), listOfNotNull(sensors.rigId, sensors.calibVersion).joinToString(" · ").ifEmpty { null })
        KeyValueRow(
            stringResource(R.string.visor_sensors_hub),
            sensors.hub?.let { h -> if (h.connected) h.fw ?: stringResource(R.string.visor_connected) else stringResource(R.string.visor_disconnected) },
        )
        KeyValueRow(
            stringResource(R.string.visor_sensors_fc),
            sensors.fc?.let { f -> if (f.connected) f.autopilot ?: stringResource(R.string.visor_connected) else stringResource(R.string.visor_disconnected) },
        )
        sensors.sensors.forEach { s ->
            KeyValueRow(
                listOfNotNull(s.id, s.role).joinToString(" · "),
                s.rateHz?.let { r -> s.expectedHz?.let { e -> "%.0f / %.0f Hz".format(r, e) } ?: "%.0f Hz".format(r) },
            )
        }
        if (slow > 0) StatusChip(StatusLevel.Warning, stringResource(R.string.visor_sensors_slow, slow))
        sensors.gnss?.let { g ->
            KeyValueRow(stringResource(R.string.visor_sensors_gnss), listOfNotNull(g.fix, g.sats.fmtInt(" sats"), g.rtk).joinToString(" · "))
        }
    }
}

@Composable
private fun NotFitted() {
    KeyValueRow(stringResource(R.string.visor_not_fitted), null)
}

private fun sigmaMs(ns: Long): String? = if (ns < 0) null else "%.2f ms".format(ns / 1_000_000.0)
