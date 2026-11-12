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
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.logs.R
import com.altnautica.gcs.feature.logs.share.ShareOutcome
import com.altnautica.gcs.feature.logs.share.shareLogFile
import java.io.File

/**
 * Detail of one recorded flight as its own surface (portrait): summary, charts and ground track.
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
    ConsoleScaffold(title = stringResource(R.string.logs_detail_title), onBack = onBack, modifier = modifier) { padding ->
        FlightDetailPane(sessionId, onReplay, Modifier.padding(padding))
    }
}

/** The flight detail content without chrome; the Logs detail pane hosts it in landscape. */
@Composable
fun FlightDetailPane(
    sessionId: Long,
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
        onReload = viewModel::load,
        onReplay = onReplay,
        onShare = { if (shareLogFile(context, File(it)) == ShareOutcome.Missing) viewModel.fileMissing(it) },
        modifier = modifier,
    )
}

@Composable
fun FlightDetailScreen(
    state: FlightDetailUiState,
    onReload: () -> Unit,
    onReplay: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    Column(modifier.fillMaxSize()) {
        when (state) {
            FlightDetailUiState.Loading -> LoadingState(stringResource(R.string.logs_loading))
            FlightDetailUiState.NotFound -> EmptyState(
                icon = Icons.Outlined.Timeline,
                title = stringResource(R.string.logs_detail_not_found),
                message = stringResource(R.string.logs_flight_not_found),
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
                    is SeriesState.Failed -> {
                        ErrorState(
                            reason = stringResource(R.string.logs_detail_tlog_failed),
                            retryLabel = stringResource(R.string.logs_retry),
                            onRetry = onReload,
                        )
                        // A damaged log is the one support needs: it stays shareable.
                        AdosButton(
                            label = stringResource(R.string.logs_share),
                            onClick = { onShare(series.tlogPath) },
                            variant = AdosButtonVariant.Secondary,
                        )
                    }
                    is SeriesState.Ready -> {
                        FlightTrack(series.series.track, Modifier.fillMaxWidth())
                        val noData = stringResource(R.string.logs_chart_no_data)
                        LineChart(stringResource(R.string.logs_chart_altitude), series.series.altitudeRelM, colors.accentPrimary, noData)
                        LineChart(stringResource(R.string.logs_chart_speed), series.series.groundspeedMs, colors.statusInfo, noData)
                        LineChart(stringResource(R.string.logs_chart_battery), series.series.batteryPct, colors.statusSuccess, noData)
                        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                            AdosButton(
                                label = stringResource(R.string.logs_replay),
                                onClick = { onReplay(series.tlogPath) },
                                variant = AdosButtonVariant.Secondary,
                            )
                            AdosButton(
                                label = stringResource(R.string.logs_share),
                                onClick = { onShare(series.tlogPath) },
                                variant = AdosButtonVariant.Secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
