package com.altnautica.gcs.feature.configure.params

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Parameters destination for one node: browse, search, edit, save to and load from files. */
@Composable
fun ParametersRoute(nodeId: DeviceId, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ParametersViewModel, ParametersViewModel.Factory>(
        key = "params-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingWrite.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val review by viewModel.importReview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportFormat by remember { mutableStateOf(ParamFileFormat.Qgc) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = viewModel.exportText(exportFormat)
        if (uri != null && text != null) scope.launch { writeUri(context, uri, text) }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { readUri(context, uri)?.let(viewModel::importText) }
    }

    ParametersScreen(
        state = state,
        message = message,
        onFilter = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onWrite = viewModel::requestWrite,
        onDismissMessage = viewModel::consumeMessage,
        onSave = { format ->
            exportFormat = format
            saveLauncher.launch(if (format == ParamFileFormat.Qgc) "vehicle.params" else "vehicle.param")
        },
        onLoad = { openLauncher.launch(arrayOf("*/*")) },
        modifier = modifier,
    )
    pending?.let { p ->
        ConfirmSheet(
            title = stringResource(R.string.cfg_param_armed_title),
            message = stringResource(R.string.cfg_param_armed_message, p.name, ParamFile.formatValue(p.value)),
            onDismiss = viewModel::dismissPendingWrite,
            danger = true,
        ) {
            HoldToConfirmButton(
                label = stringResource(R.string.cfg_param_armed_confirm),
                holdMs = 800,
                danger = true,
                onConfirmed = viewModel::confirmPendingWrite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    review?.let { ImportReviewSheet(it, onApply = viewModel::applyImport, onDismiss = viewModel::dismissImport) }
}

private suspend fun writeUri(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
}

private suspend fun readUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
}

@Composable
fun ParametersScreen(
    state: ParametersUiState,
    message: ParamMessage?,
    onFilter: ((ParamFilter) -> ParamFilter) -> Unit,
    onRefresh: () -> Unit,
    onWrite: (String, String) -> Unit,
    onDismissMessage: () -> Unit,
    onSave: (ParamFileFormat) -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    var editing by remember { mutableStateOf<ParamRow?>(null) }
    Column(modifier.fillMaxSize()) {
        when (state) {
            ParametersUiState.NoVehicle -> EmptyState(
                icon = Icons.Outlined.LinkOff,
                title = stringResource(R.string.cfg_no_vehicle_title),
                message = stringResource(R.string.cfg_block_no_vehicle),
            )
            is ParametersUiState.Loading -> Column(Modifier.padding(AdosSpacing.S4)) {
                LoadingState(stringResource(R.string.cfg_param_loading, state.received, state.count))
                if (state.count > 0) {
                    LinearProgressIndicator(progress = { state.received / state.count.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
            is ParametersUiState.LoadFailed -> ErrorState(
                reason = stringResource(R.string.cfg_param_load_failed, state.reason),
                retryLabel = stringResource(R.string.cfg_retry),
                onRetry = onRefresh,
            )
            is ParametersUiState.Content -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.filter.query,
                        onValueChange = { q -> onFilter { it.copy(query = q) } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.cfg_param_search)) },
                    )
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cfg_param_refresh))
                    }
                    IconButton(onClick = { onSave(ParamFileFormat.Qgc) }) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.cfg_param_save_file))
                    }
                    IconButton(onClick = onLoad) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = stringResource(R.string.cfg_param_load_file))
                    }
                }
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = AdosSpacing.S2),
                    horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
                ) {
                    item {
                        FilterChip(
                            selected = state.filter.modifiedOnly,
                            onClick = { onFilter { it.copy(modifiedOnly = !it.modifiedOnly) } },
                            label = { Text(stringResource(R.string.cfg_param_modified_only)) },
                            modifier = Modifier.padding(start = AdosSpacing.S4),
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.filter.showAdvanced,
                            onClick = { onFilter { it.copy(showAdvanced = !it.showAdvanced) } },
                            label = { Text(stringResource(R.string.cfg_param_advanced)) },
                        )
                    }
                    items(state.groups) { g ->
                        FilterChip(
                            selected = state.filter.group == g,
                            onClick = { onFilter { it.copy(group = if (it.group == g) null else g) } },
                            label = { Text(g) },
                        )
                    }
                }
                if (!state.hasMetadata) {
                    Text(
                        stringResource(R.string.cfg_param_no_docs),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgTertiary,
                        modifier = Modifier.padding(horizontal = AdosSpacing.S4),
                    )
                }
                message?.let { MessageLine(it, state.byName, onDismissMessage) }
                Text(
                    stringResource(R.string.cfg_param_count, state.rows.size, state.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgTertiary,
                    modifier = Modifier.padding(horizontal = AdosSpacing.S4),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.name }) { row -> ParamRowItem(row, onClick = { editing = row }) }
                }
            }
        }
    }
    editing?.let { row ->
        ParamEditorSheet(
            row = row,
            onSubmit = { input ->
                onWrite(row.name, input)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
fun ParamRowItem(row: ParamRow, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = AdosTheme.touchMin)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            row.meta?.humanName?.takeIf { it != row.name }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
        }
        if (row.modified) {
            StatusChip(label = stringResource(R.string.cfg_param_modified), level = StatusLevel.Info)
        }
        Text(row.displayValue(), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, modifier = Modifier.padding(start = AdosSpacing.S2))
    }
}

@Composable
private fun MessageLine(message: ParamMessage, rows: Map<String, ParamRow>, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val (text, level) = when (message) {
        is ParamMessage.Saved -> stringResource(
            if (message.rebootRequired) R.string.cfg_param_saved_reboot else R.string.cfg_param_saved,
            message.name, ParamFile.formatValue(message.value),
        ) to StatusLevel.Success
        is ParamMessage.Clamped -> stringResource(
            R.string.cfg_param_clamped, message.name, ParamFile.formatValue(message.requested), ParamFile.formatValue(message.actual),
        ) to StatusLevel.Warning
        is ParamMessage.WriteFailed -> stringResource(
            when (message.reason) {
                ParamWriteFailure.Timeout -> R.string.cfg_param_write_timeout
                ParamWriteFailure.UnknownParam -> R.string.cfg_param_write_unknown
                ParamWriteFailure.NoLink -> R.string.cfg_param_write_nolink
                ParamWriteFailure.Other -> R.string.cfg_param_write_failed
            },
            message.name,
        ) to StatusLevel.Error
        is ParamMessage.Invalid -> "${message.name}: ${invalidReasonText(message.reason, rows[message.name]?.meta)}" to StatusLevel.Error
        is ParamMessage.LoadIncomplete -> stringResource(R.string.cfg_param_incomplete, message.missing) to StatusLevel.Warning
        is ParamMessage.FileUnreadable -> stringResource(R.string.cfg_param_file_unreadable) to StatusLevel.Error
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(label = text, level = level)
        Text(stringResource(R.string.cfg_dismiss), color = colors.fgTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = AdosSpacing.S2))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportReviewSheet(review: ImportReview, onApply: () -> Unit, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgSurface, contentColor = colors.fgPrimary) {
        Column(Modifier.fillMaxWidth().padding(AdosSpacing.S6), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Text(stringResource(R.string.cfg_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.cfg_import_summary, review.comparison.changed.size, review.comparison.unknownNames.size),
                color = colors.fgSecondary,
            )
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(review.comparison.changed, key = { it.name }) { d ->
                    Text(
                        "${d.name}: ${ParamFile.formatValue(d.current)} → ${ParamFile.formatValue(d.fromFile)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.name in review.failed) colors.statusError else colors.fgPrimary,
                    )
                }
            }
            if (review.running || review.applied > 0 || review.failed.isNotEmpty()) {
                Text(stringResource(R.string.cfg_import_progress, review.applied, review.comparison.changed.size, review.failed.size))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(
                    stringResource(R.string.cfg_import_apply),
                    onClick = onApply,
                    enabled = !review.running && review.comparison.changed.isNotEmpty(),
                )
                AdosButton(stringResource(R.string.cfg_close), onClick = onDismiss, variant = AdosButtonVariant.Secondary)
            }
        }
    }
}

