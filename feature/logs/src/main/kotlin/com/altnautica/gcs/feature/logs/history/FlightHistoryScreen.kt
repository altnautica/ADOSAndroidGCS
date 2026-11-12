package com.altnautica.gcs.feature.logs.history

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.logs.R

/** The recorded-flights list pane, with a confirmed delete per flight. */
@Composable
fun FlightList(
    state: FlightHistoryUiState,
    selectedId: Long?,
    onOpenFlight: (Long) -> Unit,
    onDeleteFlight: (FlightSessionEntity) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<FlightSessionEntity?>(null) }
    val flights = (state as? FlightHistoryUiState.Content)?.flights.orEmpty()
    when {
        state == FlightHistoryUiState.Loading -> LoadingState(stringResource(R.string.logs_loading), modifier)
        state is FlightHistoryUiState.Error -> ErrorState(
            reason = stringResource(R.string.logs_error),
            retryLabel = stringResource(R.string.logs_retry),
            onRetry = onRetry,
            modifier = modifier,
        )
        flights.isEmpty() -> EmptyState(
            icon = Icons.Outlined.FlightTakeoff,
            title = stringResource(R.string.logs_empty_title),
            message = stringResource(R.string.logs_empty_message),
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
        ) {
            items(flights, key = { it.session.id }) { row ->
                FlightItem(
                    row = row,
                    selected = row.session.id == selectedId,
                    onClick = { onOpenFlight(row.session.id) },
                    onDelete = { pendingDelete = row.session },
                )
            }
        }
    }
    pendingDelete?.let { session ->
        AdosConfirmSheet(
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
private fun FlightItem(row: FlightRow, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val s = row.session
    val context = LocalContext.current
    AdosListItem(
        headline = DateUtils.formatDateTime(
            context, s.startTime,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        ),
        onClick = onClick,
        selected = selected,
        supporting = listOfNotNull(
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
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.logs_delete))
            }
        },
    )
}
