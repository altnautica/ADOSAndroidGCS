package com.altnautica.gcs.feature.node.visor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.component.TextSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.ui.VideoSurface
import com.altnautica.gcs.core.video.ui.rememberVideoState
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt
import com.altnautica.gcs.feature.node.groundstation.Load

/** Visor Capture tab: live preview, session state, health chips, REC/MARK and exposure lock. */
@Composable
fun VisorCaptureTab(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<VisorViewModel, VisorViewModel.Factory>(key = "visor-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.show(NodeTab.Capture)
        onDispose { vm.show(null) }
    }
    Column(modifier.fillMaxSize()) {
        ActionFeedback(state.action, vm::dismissAction, Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AdosSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            item { PreviewCard(state.preview, state.cameras, state.previewCamera, vm::selectCamera) }
            when (val load = state.capture) {
                Load.Loading -> item { LoadingState(stringResource(R.string.node_loading)) }
                is Load.Failed -> item { ProblemState(load.problem, onRetry = { vm.show(NodeTab.Capture) }) }
                is Load.Ready -> {
                    val c = load.value
                    val busy = state.action is ActionState.Running
                    item { SessionCard(c, state.lastSegment) }
                    if (c.chips.isNotEmpty()) item { HealthRow(c.chips) }
                    if (c.mode != CaptureMode.Idle) item { CoverageCard(c) }
                    item { ControlsCard(c, busy, vm) }
                    item { ExposureCard(c.exposure, busy, vm::setExposure) }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(source: VideoSource?, cameras: List<String>, selected: String?, onSelect: (String) -> Unit) {
    val video by rememberVideoState(source)
    NodeCard(stringResource(R.string.visor_preview)) {
        if (cameras.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                cameras.forEach { cam ->
                    FilterChip(selected = cam == selected, onClick = { onSelect(cam) }, label = { Text(cam) })
                }
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            VideoSurface(source, Modifier.fillMaxSize())
            val note = when (video) {
                is VideoState.Live -> null
                VideoState.Connecting, VideoState.Idle -> R.string.visor_preview_connecting
                is VideoState.Frozen -> R.string.visor_preview_frozen
                is VideoState.Failed, VideoState.NoSource -> R.string.visor_preview_none
            }
            note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary) }
        }
    }
}

@Composable
private fun SessionCard(c: CaptureUi, lastSegment: Int?) {
    NodeCard(stringResource(R.string.visor_session)) {
        val (level, label) = when (c.mode) {
            CaptureMode.Recording -> StatusLevel.Error to R.string.visor_state_recording
            CaptureMode.Paused -> StatusLevel.Warning to R.string.visor_state_paused
            CaptureMode.Closing -> StatusLevel.Info to R.string.visor_state_closing
            CaptureMode.Idle -> StatusLevel.Neutral to R.string.visor_state_idle
        }
        StatusChip(level, stringResource(label), icon = if (c.mode == CaptureMode.Recording) Icons.Outlined.FiberManualRecord else Icons.Outlined.Stop)
        if (c.mode != CaptureMode.Idle) {
            KeyValueRow(stringResource(R.string.visor_session_id), c.sessionId)
            KeyValueRow(stringResource(R.string.visor_duration), formatDuration(c.durationS))
            KeyValueRow(stringResource(R.string.visor_site), c.site)
            KeyValueRow(stringResource(R.string.visor_floor), c.floor.fmtInt())
            KeyValueRow(stringResource(R.string.visor_room), c.room)
            KeyValueRow(stringResource(R.string.visor_segment), (c.segment ?: lastSegment).fmtInt())
            KeyValueRow(stringResource(R.string.visor_frames), c.framesTotal.fmtInt())
            KeyValueRow(stringResource(R.string.visor_dropped), c.droppedPct.fmt(1, " %"))
            KeyValueRow(stringResource(R.string.visor_pose), listOfNotNull(c.poseSource, c.poseHealth).joinToString(" · ").ifEmpty { null })
            KeyValueRow(stringResource(R.string.visor_features), c.trackedFeatures.fmtInt())
        }
        KeyValueRow(stringResource(R.string.visor_storage_free), formatBytes(c.freeBytes))
        KeyValueRow(stringResource(R.string.visor_storage_remaining), c.estRemainingS?.let { formatDuration(it.toDouble()) })
    }
}

@Composable
private fun HealthRow(chips: List<HealthChip>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        chips.forEach { chip ->
            val text = chip.value?.let { stringResource(R.string.visor_health_value, healthLabel(chip.id), "%.1f".format(it)) } ?: healthLabel(chip.id)
            StatusChip(chip.level.status(), text)
        }
    }
}

@Composable
private fun CoverageCard(c: CaptureUi) {
    NodeCard(stringResource(R.string.visor_coverage)) {
        KeyValueRow(stringResource(R.string.visor_coverage_room), c.coveragePct.fmt(0, " %"))
        KeyValueRow(stringResource(R.string.visor_coverage_voxels), c.voxels.fmtInt())
        KeyValueRow(stringResource(R.string.visor_coverage_from_start), c.distanceFromStartM.fmt(1, " m"))
        KeyValueRow(
            stringResource(R.string.visor_coverage_loop),
            c.loopClosed?.let { stringResource(if (it) R.string.visor_yes else R.string.visor_no) },
        )
    }
}

@Composable
private fun ControlsCard(c: CaptureUi, busy: Boolean, vm: VisorViewModel) {
    var site by rememberSaveable { mutableStateOf("") }
    var room by rememberSaveable { mutableStateOf("") }
    var floor by rememberSaveable { mutableIntStateOf(0) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    NodeCard(stringResource(R.string.visor_controls)) {
        if (c.mode == CaptureMode.Idle) {
            TextSettingRow(stringResource(R.string.visor_site), site, { site = it })
        }
        TextSettingRow(stringResource(R.string.visor_room), room, { room = it })
        NumberSettingRow(
            title = stringResource(R.string.visor_floor),
            value = floor,
            onValueChange = { floor = it },
            range = -5..99,
            decrementDescription = stringResource(R.string.visor_floor_down),
            incrementDescription = stringResource(R.string.visor_floor_up),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), modifier = Modifier.fillMaxWidth()) {
            when (c.mode) {
                CaptureMode.Idle -> AdosButton(
                    label = stringResource(R.string.visor_action_start),
                    onClick = { vm.start(site, floor, room) },
                    enabled = !busy,
                    icon = Icons.Outlined.FiberManualRecord,
                    modifier = Modifier.weight(1f),
                )
                CaptureMode.Recording, CaptureMode.Paused -> {
                    AdosButton(
                        label = stringResource(R.string.visor_action_mark),
                        onClick = { vm.mark(room, floor) },
                        enabled = !busy && room.isNotBlank(),
                        icon = Icons.Outlined.Bookmark,
                        modifier = Modifier.weight(1f),
                    )
                    AdosButton(
                        label = stringResource(if (c.mode == CaptureMode.Paused) R.string.visor_action_resume else R.string.visor_action_pause),
                        onClick = { if (c.mode == CaptureMode.Paused) vm.resume() else vm.pause() },
                        enabled = !busy,
                        variant = AdosButtonVariant.Secondary,
                        icon = if (c.mode == CaptureMode.Paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        modifier = Modifier.weight(1f),
                    )
                    AdosButton(
                        label = stringResource(R.string.visor_action_stop),
                        onClick = { confirmStop = true },
                        enabled = !busy,
                        variant = AdosButtonVariant.Danger,
                        icon = Icons.Outlined.Stop,
                        modifier = Modifier.weight(1f),
                    )
                }
                CaptureMode.Closing -> Text(stringResource(R.string.visor_closing_note), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (c.mode != CaptureMode.Idle && room.isBlank()) {
            Text(stringResource(R.string.visor_mark_hint), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.visor_stop_title)) },
            text = { Text(stringResource(R.string.visor_stop_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    vm.stop()
                }) { Text(stringResource(R.string.visor_action_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}

@Composable
private fun ExposureCard(exposure: ExposureUi?, busy: Boolean, onChange: (ExposureUi) -> Unit) {
    NodeCard(stringResource(R.string.visor_exposure)) {
        if (exposure == null) {
            KeyValueRow(stringResource(R.string.visor_exposure), null)
            return@NodeCard
        }
        SwitchSettingRow(
            title = stringResource(R.string.visor_exposure_lock),
            checked = exposure.locked,
            onCheckedChange = { onChange(exposure.copy(locked = it)) },
            enabled = !busy,
            summary = stringResource(if (exposure.pendingApply) R.string.visor_exposure_not_applied else R.string.visor_exposure_lock_summary),
        )
        val modeLabels = EXPOSURE_MODES.associateWith { exposureModeLabel(it) }
        SelectSettingRow(
            title = stringResource(R.string.visor_exposure_mode),
            options = EXPOSURE_MODES,
            selected = exposure.mode ?: EXPOSURE_MODES.first(),
            optionLabel = { modeLabels.getValue(it) },
            onSelect = { onChange(exposure.copy(mode = it)) },
            enabled = !busy,
        )
        KeyValueRow(stringResource(R.string.visor_exposure_time), exposure.exposureUs?.let { "%.1f ms".format(it / 1000.0) })
        KeyValueRow(stringResource(R.string.visor_exposure_gain), exposure.gain.fmt(1, "×"))
    }
}

private val EXPOSURE_MODES = listOf("walking", "slow", "short")

@Composable
private fun exposureModeLabel(mode: String): String = stringResource(
    when (mode) {
        "slow" -> R.string.visor_exposure_mode_slow
        "short" -> R.string.visor_exposure_mode_short
        else -> R.string.visor_exposure_mode_walking
    },
)
