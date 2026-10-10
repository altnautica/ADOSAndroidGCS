package com.altnautica.gcs.feature.logs.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.logs.R
import com.altnautica.gcs.feature.logs.share.shareLogFile
import java.io.File

/**
 * Detail of one recorded flight: summary, altitude/speed/battery charts and the ground track.
 * [onReplay] receives the telemetry log path; the host opens a read-only cockpit over
 * [com.altnautica.gcs.feature.logs.tlog.TlogReplayTransport].
 */
@Composable
fun FlightDetailRoute(
    sessionId: Long,
    onBack: () -> Unit,
    onReplay: (tlogPath: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<FlightDetailViewModel, FlightDetailViewModel.Factory>(
        key = "flight-$sessionId",
        creationCallback = { it.create(sessionId) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    FlightDetailScreen(
        state = state,
        onBack = onBack,
        onReload = viewModel::load,
        onReplay = onReplay,
        onShare = { shareLogFile(context, File(it)) },
        modifier = modifier,
    )
}

@Composable
fun FlightDetailScreen(
    state: FlightDetailUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onReplay: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.logs_back))
            }
            Text(
                stringResource(R.string.logs_detail_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.fgPrimary,
                modifier = Modifier.weight(1f),
            )
            val ready = (state as? FlightDetailUiState.Content)?.series as? SeriesState.Ready
            if (ready != null) {
                IconButton(onClick = { onShare(ready.tlogPath) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.logs_share))
                }
            }
        }
        when (state) {
            FlightDetailUiState.Loading -> LoadingState(stringResource(R.string.logs_loading))
            FlightDetailUiState.NotFound -> EmptyState(
                icon = Icons.Outlined.Timeline,
                title = stringResource(R.string.logs_detail_not_found),
                message = "",
            )
            is FlightDetailUiState.Content -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdosSpacing.S4),
                verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4),
            ) {
                val s = state.session
                Text(
                    listOf(
                        stringResource(R.string.logs_duration, s.durationSeconds / 60, s.durationSeconds % 60),
                        stringResource(R.string.logs_max_alt, s.maxAltitude),
                        stringResource(R.string.logs_distance, s.totalDistance),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.fgSecondary,
                )
                when (val series = state.series) {
                    SeriesState.Loading -> LoadingState(stringResource(R.string.logs_detail_loading_tlog))
                    SeriesState.NoLog -> Text(
                        stringResource(R.string.logs_detail_no_tlog),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgSecondary,
                    )
                    is SeriesState.Failed -> ErrorState(
                        reason = stringResource(R.string.logs_detail_tlog_failed, series.message),
                        retryLabel = stringResource(R.string.logs_retry),
                        onRetry = onReload,
                    )
                    is SeriesState.Ready -> {
                        FlightTrack(series.series.track, Modifier.fillMaxWidth())
                        val noData = stringResource(R.string.logs_chart_no_data)
                        LineChart(stringResource(R.string.logs_chart_altitude), series.series.altitudeRelM, colors.accentPrimary, noData)
                        LineChart(stringResource(R.string.logs_chart_speed), series.series.groundspeedMs, colors.statusInfo, noData)
                        LineChart(stringResource(R.string.logs_chart_battery), series.series.batteryPct, colors.statusSuccess, noData)
                        AdosButton(
                            label = stringResource(R.string.logs_replay),
                            onClick = { onReplay(series.tlogPath) },
                            variant = AdosButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
    }
}
