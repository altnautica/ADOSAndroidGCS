package com.altnautica.gcs.feature.node.visor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.ProblemState
import com.altnautica.gcs.feature.node.groundstation.Load

/** Visor Sessions tab: recorded sessions, upload progress, reconstruction preset, guarded delete. */
@Composable
fun VisorSessionsTab(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<VisorViewModel, VisorViewModel.Factory>(key = "visor-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.show(NodeTab.Sessions)
        onDispose { vm.show(null) }
    }
    val busy = state.action is ActionState.Running
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        ActionFeedback(state.action, vm::dismissAction, Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1))
        when (val load = state.sessions) {
            Load.Loading -> LoadingState(stringResource(R.string.node_loading))
            is Load.Failed -> ProblemState(load.problem, onRetry = { vm.show(NodeTab.Sessions) })
            is Load.Ready -> if (load.value.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.visor_sessions_empty_title),
                    message = stringResource(R.string.visor_sessions_empty_message),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AdosSpacing.S4),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
                ) {
                    items(load.value, key = { it.id }) { s ->
                        SessionRow(s, busy, vm, onDelete = { deleting = s.id })
                    }
                }
            }
        }
    }
    val target = (state.sessions as? Load.Ready)?.value?.firstOrNull { it.id == deleting }
    if (target != null) {
        DeleteDialog(target, onDismiss = { deleting = null }) { force ->
            deleting = null
            vm.delete(target.id, force)
        }
    }
}

@Composable
private fun SessionRow(s: SessionUi, busy: Boolean, vm: VisorViewModel, onDelete: () -> Unit) {
    var preset by rememberSaveable(s.id) { mutableStateOf(ReconstructPreset.Tour) }
    NodeCard(s.id) {
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) { UploadChip(s) }
        KeyValueRow(stringResource(R.string.visor_started), s.startedAtUtc)
        KeyValueRow(stringResource(R.string.visor_duration), formatDuration(s.durationS))
        KeyValueRow(stringResource(R.string.visor_size), formatBytes(s.sizeBytes))
        KeyValueRow(stringResource(R.string.visor_rooms), s.rooms.joinToString(", ").ifEmpty { null })
        KeyValueRow(stringResource(R.string.visor_floors), s.floors.joinToString(", ").ifEmpty { null })
        KeyValueRow(stringResource(R.string.visor_upload_node), s.uploadNode)
        if (s.upload == UploadState.Uploading || s.upload == UploadState.Queued) {
            val p = s.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.visor_upload_progress, (p * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = AdosTheme.colors.fgSecondary,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        s.uploadError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.statusError) }
        if (s.uploaded) {
            val presetLabels = ReconstructPreset.entries.associateWith { presetLabel(it) }
            SelectSettingRow(
                title = stringResource(R.string.visor_preset),
                options = ReconstructPreset.entries.toList(),
                selected = preset,
                optionLabel = { presetLabels.getValue(it) },
                onSelect = { preset = it },
                enabled = !busy,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), modifier = Modifier.fillMaxWidth()) {
            if (s.uploaded) {
                AdosButton(
                    label = stringResource(R.string.visor_action_reconstruct),
                    onClick = { vm.reconstruct(s.id, preset) },
                    enabled = !busy,
                    icon = Icons.Outlined.ViewInAr,
                    modifier = Modifier.weight(1f),
                )
            } else {
                AdosButton(
                    label = stringResource(R.string.visor_action_upload),
                    onClick = { vm.upload(s.id) },
                    enabled = !busy && s.upload != UploadState.Uploading && s.upload != UploadState.Queued,
                    icon = Icons.Outlined.CloudUpload,
                    modifier = Modifier.weight(1f),
                )
            }
            AdosButton(
                label = stringResource(R.string.visor_action_delete),
                onClick = onDelete,
                enabled = !busy,
                variant = AdosButtonVariant.Danger,
                icon = Icons.Outlined.Delete,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UploadChip(s: SessionUi) {
    val (level, label) = when (s.upload) {
        UploadState.Committed -> StatusLevel.Success to R.string.visor_upload_committed
        UploadState.Uploading -> StatusLevel.Info to R.string.visor_upload_uploading
        UploadState.Queued -> StatusLevel.Info to R.string.visor_upload_queued
        UploadState.Failed -> StatusLevel.Error to R.string.visor_upload_failed
        UploadState.Idle -> if (s.uploaded) StatusLevel.Success to R.string.visor_upload_committed else StatusLevel.Warning to R.string.visor_upload_not_uploaded
    }
    StatusChip(level, stringResource(label))
}

/**
 * Delete is guarded: an uploaded session asks once; one that is not uploaded
 * yet says so and needs its own explicit button, which sends the override.
 */
@Composable
private fun DeleteDialog(s: SessionUi, onDismiss: () -> Unit, onConfirm: (force: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.visor_delete_title)) },
        text = {
            Text(
                if (s.uploaded) {
                    stringResource(R.string.visor_delete_message, s.id)
                } else {
                    stringResource(R.string.visor_delete_not_uploaded_message, s.id)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(!s.uploaded) }) {
                Text(stringResource(if (s.uploaded) R.string.visor_action_delete else R.string.visor_delete_anyway))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.node_cancel)) } },
    )
}

@Composable
private fun presetLabel(p: ReconstructPreset): String = stringResource(
    when (p) {
        ReconstructPreset.Preview -> R.string.visor_preset_preview
        ReconstructPreset.Hq -> R.string.visor_preset_hq
        ReconstructPreset.Tour -> R.string.visor_preset_tour
        ReconstructPreset.Warehouse -> R.string.visor_preset_warehouse
    },
)
