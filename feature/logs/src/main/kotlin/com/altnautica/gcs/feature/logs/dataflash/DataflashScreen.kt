package com.altnautica.gcs.feature.logs.dataflash

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.link.text.linkReasonRes
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.log.LogEntry
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.logs.R
import com.altnautica.gcs.feature.logs.share.ShareOutcome
import com.altnautica.gcs.feature.logs.share.shareLogFile
import java.io.File

/** Onboard flight-controller logs of one node: list, download with resume, share. */
@Composable
fun DataflashRoute(nodeId: DeviceId, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<DataflashViewModel, DataflashViewModel.Factory>(
        key = "dataflash-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ConsoleScaffold(
        title = stringResource(R.string.logs_df_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            val canRefresh = state !is DataflashUiState.NoVehicle && state !is DataflashUiState.Listing && state !is DataflashUiState.Armed
            IconButton(onClick = viewModel::refresh, enabled = canRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.logs_df_refresh))
            }
        },
    ) { padding ->
        DataflashScreen(
            state = state,
            onRefresh = viewModel::refresh,
            onDownload = viewModel::download,
            onCancel = viewModel::cancel,
            onShare = { if (shareLogFile(context, File(it)) == ShareOutcome.Missing) viewModel.fileMissing(it) },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
fun DataflashScreen(
    state: DataflashUiState,
    onRefresh: () -> Unit,
    onDownload: (LogEntry) -> Unit,
    onCancel: (LogEntry) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        when (state) {
            DataflashUiState.NoVehicle -> EmptyState(
                icon = Icons.Outlined.LinkOff,
                title = stringResource(R.string.logs_df_no_vehicle_title),
                message = stringResource(R.string.logs_df_no_vehicle),
            )
            DataflashUiState.Armed -> EmptyState(
                icon = Icons.Outlined.LinkOff,
                title = stringResource(R.string.logs_df_armed_title),
                message = stringResource(R.string.logs_df_armed),
            )
            DataflashUiState.Listing -> LoadingState(stringResource(R.string.logs_df_listing))
            is DataflashUiState.ListFailed -> ErrorState(
                reason = listFailedText(LocalResources.current, state.reason),
                retryLabel = stringResource(R.string.logs_retry),
                onRetry = onRefresh,
            )
            DataflashUiState.Empty -> EmptyState(
                icon = Icons.Outlined.Inventory2,
                title = stringResource(R.string.logs_df_empty_title),
                message = stringResource(R.string.logs_df_empty),
            )
            is DataflashUiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.entry.id }) { row -> DataflashItem(row, onDownload, onCancel, onShare) }
            }
        }
    }
}

@Composable
private fun DataflashItem(row: DataflashRow, onDownload: (LogEntry) -> Unit, onCancel: (LogEntry) -> Unit, onShare: (String) -> Unit) {
    val context = LocalContext.current
    val colors = AdosTheme.colors
    val e = row.entry
    Column(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.logs_df_entry, e.id), style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                Text(
                    listOfNotNull(
                        Formatter.formatShortFileSize(context, e.sizeBytes),
                        e.timeUtcS.takeIf { it > 0 }?.let {
                            DateUtils.formatDateTime(context, it * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                )
            }
            when (val d = row.download) {
                EntryDownload.None ->
                    AdosButton(stringResource(R.string.logs_df_download), onClick = { onDownload(e) }, variant = AdosButtonVariant.Secondary)
                // A retry resumes from what the failed attempt already received.
                is EntryDownload.Failed ->
                    AdosButton(stringResource(R.string.logs_retry), onClick = { onDownload(e) }, variant = AdosButtonVariant.Secondary)
                is EntryDownload.Running, EntryDownload.Queued ->
                    AdosButton(stringResource(R.string.logs_df_cancel), onClick = { onCancel(e) }, variant = AdosButtonVariant.Secondary)
                is EntryDownload.Saved -> IconButton(onClick = { onShare(d.path) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.logs_share))
                }
            }
        }
        when (val d = row.download) {
            is EntryDownload.Running -> LinearProgressIndicator(
                progress = { if (d.totalBytes > 0) d.receivedBytes / d.totalBytes.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            is EntryDownload.Failed -> Text(
                downloadFailedText(LocalResources.current, d.reason),
                color = colors.statusError,
                style = MaterialTheme.typography.bodySmall,
            )
            EntryDownload.Queued -> Text(stringResource(R.string.logs_df_queued), color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
    }
}

/** List failure in operator words; the link token never reaches the screen. */
internal fun listFailedText(res: Resources, reason: String): String =
    res.getString(R.string.logs_df_list_failed, res.getString(linkReasonRes(reason)))

internal fun downloadFailedText(res: Resources, reason: String): String =
    res.getString(R.string.logs_df_failed, res.getString(linkReasonRes(reason)))
