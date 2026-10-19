package com.altnautica.gcs.feature.node.visor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val retry = { vm.show(NodeTab.Overview) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        when (val load = state.overview) {
            Load.Loading -> LoadingState(stringResource(R.string.node_loading))
            is Load.Failed -> ProblemState(load.problem, onRetry = retry)
            is Load.Ready -> {
                SectionCard(stringResource(R.string.visor_clock), load.value.clock, retry) { ClockRows(it) }
                SectionCard(stringResource(R.string.visor_storage), load.value.storage, retry) { StorageRows(it) }
                SectionCard(stringResource(R.string.visor_sensors), load.value.sensors, retry) { SensorsRows(it) }
            }
        }
    }
}

/** One block: rows when ready (with a stale chip after a failed read), "not fitted", or the read problem. */
@Composable
private fun <T> SectionCard(title: String, section: Section<T>, onRetry: () -> Unit, rows: @Composable ColumnScope.(T) -> Unit) {
    NodeCard(title) {
        when (section) {
            Section.NotFitted -> KeyValueRow(stringResource(R.string.visor_not_fitted), null)
            is Section.Failed -> ProblemState(section.problem, onRetry)
            is Section.Ready -> {
                if (section.stale) StatusChip(StatusLevel.Warning, stringResource(R.string.visor_stale))
                rows(section.value)
            }
        }
    }
}

@Composable
private fun ColumnScope.ClockRows(clock: ClockUi) {
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

@Composable
private fun ColumnScope.StorageRows(s: StorageUi) {
    val (level, label) = when (s.state) {
        StorageState.Full -> StatusLevel.Error to R.string.visor_storage_full
        StorageState.NearFull -> StatusLevel.Warning to R.string.visor_storage_near_full
        StorageState.Ok -> StatusLevel.Success to R.string.visor_storage_ok
        StorageState.Unknown -> StatusLevel.Neutral to R.string.visor_storage_unknown
    }
    StatusChip(level, stringResource(label))
    KeyValueRow(stringResource(R.string.visor_storage_root), s.root)
    KeyValueRow(stringResource(R.string.visor_storage_used), formatBytes(s.usedBytes))
    KeyValueRow(stringResource(R.string.visor_storage_budget), formatBytes(s.budgetBytes))
    KeyValueRow(stringResource(R.string.visor_storage_free), formatBytes(s.freeBytes))
    KeyValueRow(stringResource(R.string.visor_storage_sessions), s.sessions?.let { "${s.uploaded ?: 0} / $it" })
    KeyValueRow(stringResource(R.string.visor_storage_write), s.writeMbps.fmt(0, " MB/s"))
}

@Composable
private fun ColumnScope.SensorsRows(s: SensorsUi) {
    KeyValueRow(stringResource(R.string.visor_sensors_rig), listOfNotNull(s.rigId, s.calibVersion).joinToString(" · ").ifEmpty { null })
    KeyValueRow(stringResource(R.string.visor_sensors_hub), s.hub?.let { linkText(it) })
    KeyValueRow(stringResource(R.string.visor_sensors_fc), s.fc?.let { linkText(it) })
    s.sensors.forEach { r ->
        KeyValueRow(
            listOfNotNull(r.id, r.role).joinToString(" · "),
            r.rateHz?.let { rate -> r.expectedHz?.let { e -> "%.0f / %.0f Hz".format(rate, e) } ?: "%.0f Hz".format(rate) },
        )
    }
    if (s.slowCount > 0) StatusChip(StatusLevel.Warning, stringResource(R.string.visor_sensors_slow, s.slowCount))
    s.gnss?.let { g ->
        val sats = g.sats?.let { pluralStringResource(R.plurals.visor_sensors_sats, it, it) }
        KeyValueRow(stringResource(R.string.visor_sensors_gnss), listOfNotNull(g.fix, sats, g.rtk).joinToString(" · "))
    }
}

@Composable
private fun linkText(l: LinkUi): String =
    if (l.connected) l.label ?: stringResource(R.string.visor_connected) else stringResource(R.string.visor_disconnected)

private fun sigmaMs(ns: Long): String? = if (ns < 0) null else "%.2f ms".format(ns / 1_000_000.0)
