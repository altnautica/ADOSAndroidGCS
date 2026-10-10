package com.altnautica.gcs.feature.logs.history

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.TlogFileEntity
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.GlassSurface
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.logs.R
import com.altnautica.gcs.feature.logs.share.shareLogFile
import java.io.File

/** Flight history destination: every recorded flight plus downloaded flight-controller logs. */
@Composable
fun LogsRoute(
    onOpenFlight: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    FlightHistoryScreen(
        state = state,
        onOpenFlight = onOpenFlight,
        onDeleteFlight = viewModel::deleteFlight,
        onDeleteFile = viewModel::deleteLogFile,
        onShareFile = { shareLogFile(context, File(it.path)) },
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@Composable
fun FlightHistoryScreen(
    state: FlightHistoryUiState,
    onOpenFlight: (Long) -> Unit,
    onDeleteFlight: (FlightSessionEntity) -> Unit,
    onDeleteFile: (TlogFileEntity) -> Unit,
    onShareFile: (TlogFileEntity) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<FlightSessionEntity?>(null) }
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.logs_title),
            style = MaterialTheme.typography.headlineSmall,
            color = AdosTheme.colors.fgPrimary,
            modifier = Modifier.padding(AdosSpacing.S4),
        )
        when (state) {
            FlightHistoryUiState.Loading -> LoadingState(stringResource(R.string.logs_loading))
            FlightHistoryUiState.Empty -> EmptyState(
                icon = Icons.Outlined.FlightTakeoff,
                title = stringResource(R.string.logs_empty_title),
                message = stringResource(R.string.logs_empty_message),
            )
            is FlightHistoryUiState.Error -> ErrorState(
                reason = stringResource(R.string.logs_error, state.message),
                retryLabel = stringResource(R.string.logs_retry),
                onRetry = onRetry,
            )
            is FlightHistoryUiState.Content -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            ) {
                if (state.flights.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.logs_section_flights)) }
                    items(state.flights, key = { "f${it.session.id}" }) { row ->
                        FlightCard(row, onClick = { onOpenFlight(row.session.id) }, onDelete = { pendingDelete = row.session })
                    }
                }
                if (state.dataflashLogs.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.logs_section_dataflash)) }
                    items(state.dataflashLogs, key = { "d${it.id}" }) { file ->
                        LogFileCard(file, onShare = { onShareFile(file) }, onDelete = { onDeleteFile(file) })
                    }
                }
            }
        }
    }
    pendingDelete?.let { session ->
        ConfirmSheet(
            title = stringResource(R.string.logs_delete_title),
            message = stringResource(R.string.logs_delete_message),
            onDismiss = { pendingDelete = null },
            danger = true,
        ) {
            HoldToConfirmButton(
                label = stringResource(R.string.logs_delete_confirm),
                holdMs = 800,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
                onConfirmed = {
                    onDeleteFlight(session)
                    pendingDelete = null
                },
            )
        }
    }
}

@Composable
private fun FlightCard(row: FlightRow, onClick: () -> Unit, onDelete: () -> Unit) {
    val s = row.session
    val context = LocalContext.current
    val colors = AdosTheme.colors
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4).clickable(onClick = onClick)) {
        Row(Modifier.padding(AdosSpacing.S3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                Text(
                    DateUtils.formatDateTime(
                        context, s.startTime,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.fgPrimary,
                )
                Text(
                    listOfNotNull(
                        stringResource(R.string.logs_duration, s.durationSeconds / 60, s.durationSeconds % 60),
                        stringResource(R.string.logs_max_alt, s.maxAltitude),
                        stringResource(R.string.logs_distance, s.totalDistance),
                        if (s.batteryStart >= 0 && s.batteryEnd >= 0) {
                            stringResource(R.string.logs_battery, s.batteryStart, s.batteryEnd)
                        } else {
                            stringResource(R.string.logs_battery_unknown)
                        },
                        if (row.hasTlog) stringResource(R.string.logs_has_tlog) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.logs_delete))
            }
        }
    }
}

@Composable
private fun LogFileCard(file: TlogFileEntity, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val colors = AdosTheme.colors
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4)) {
        Row(Modifier.padding(AdosSpacing.S3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(File(file.path).name, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                Text(
                    stringResource(
                        R.string.logs_file_size,
                        Formatter.formatShortFileSize(context, file.sizeBytes),
                        DateUtils.formatDateTime(context, file.createdAtMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.logs_share))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.logs_delete))
            }
        }
    }
}
