package com.altnautica.gcs.feature.configure.params

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.param.MavlinkParamService
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.common.commandResultText
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.link.text.linkReasonRes

/** Rows split into their groups, in list order: the group name and its rows. */
internal fun List<ParamRow>.grouped(): List<Pair<String, List<ParamRow>>> =
    groupBy { it.group }.toList()

/**
 * Lazy-list index of each group header for [groups]: every group takes one header item
 * plus one item per row.
 */
internal fun headerIndices(groups: List<Pair<String, List<ParamRow>>>): List<Int> {
    var index = 0
    return groups.map { (_, rows) -> index.also { index += 1 + rows.size } }
}

/**
 * The header index to jump to from [firstVisible]: the previous or next group header.
 * Null when there is none in that direction.
 */
internal fun groupJumpTarget(headers: List<Int>, firstVisible: Int, forward: Boolean): Int? =
    if (forward) headers.firstOrNull { it > firstVisible } else headers.lastOrNull { it < firstVisible }

/** The list pane: search, filters, status lines and the grouped parameter list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ParametersListPane(
    state: ParametersUiState,
    message: ParamMessage?,
    listState: LazyListState,
    selectedName: String?,
    onSelect: (ParamRow) -> Unit,
    onFilter: ((ParamFilter) -> ParamFilter) -> Unit,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    writing: String? = null,
) {
    val colors = AdosTheme.colors
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
                reason = stringResource(R.string.cfg_param_load_failed, paramLoadReason(state.reason)),
                retryLabel = stringResource(R.string.cfg_retry),
                onRetry = onRefresh,
            )
            is ParametersUiState.Content -> {
                OutlinedTextField(
                    value = state.filter.query,
                    onValueChange = { q -> onFilter { it.copy(query = q) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.cfg_param_search)) },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
                    horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
                ) {
                    FilterChip(
                        selected = state.filter.modifiedOnly,
                        onClick = { onFilter { it.copy(modifiedOnly = !it.modifiedOnly) } },
                        label = { Text(stringResource(R.string.cfg_param_modified_only)) },
                    )
                    FilterChip(
                        selected = state.filter.showAdvanced,
                        onClick = { onFilter { it.copy(showAdvanced = !it.showAdvanced) } },
                        label = { Text(stringResource(R.string.cfg_param_advanced)) },
                    )
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
                writing?.let {
                    StatusChip(
                        label = stringResource(R.string.cfg_param_writing, it),
                        level = StatusLevel.Info,
                        modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1),
                    )
                }
                Text(
                    stringResource(R.string.cfg_param_count, state.rows.size, state.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgTertiary,
                    modifier = Modifier.padding(horizontal = AdosSpacing.S4),
                )
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    state.rows.grouped().forEach { (group, rows) ->
                        stickyHeader(key = "group-$group") { GroupHeader(group) }
                        items(rows, key = { it.name }) { row ->
                            ParamRowItem(row, onClick = { onSelect(row) }, selected = row.name == selectedName)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: String) {
    val colors = AdosTheme.colors
    Text(
        group,
        style = MaterialTheme.typography.labelLarge,
        color = colors.fgSecondary,
        modifier = Modifier.fillMaxWidth().background(colors.bgSurface)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
    )
}

@Composable
fun ParamRowItem(row: ParamRow, onClick: () -> Unit, selected: Boolean = false) {
    val colors = AdosTheme.colors
    AdosListItem(
        headline = row.name,
        onClick = onClick,
        supporting = row.meta?.humanName?.takeIf { it != row.name },
        selected = selected,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.modified) StatusChip(label = stringResource(R.string.cfg_param_modified), level = StatusLevel.Info)
                Text(
                    row.displayValue(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.fgPrimary,
                    modifier = Modifier.padding(start = AdosSpacing.S2),
                )
            }
        },
    )
}

/**
 * Pending-reboot banner: changes wait for a flight-controller reboot. The hold button is
 * live only while disarmed on a fresh link.
 */
@Composable
fun RebootBanner(armed: Boolean, linkFresh: Boolean, onReboot: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Column(
        modifier.fillMaxWidth().background(colors.bgRaised).padding(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        StatusChip(label = stringResource(R.string.cfg_reboot_pending), level = StatusLevel.Warning)
        val blocked = when {
            armed -> R.string.cfg_block_armed
            !linkFresh -> R.string.cfg_block_link_stale
            else -> null
        }
        blocked?.let { Text(stringResource(it), color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall) }
        HoldToConfirmButton(
            label = stringResource(R.string.cfg_reboot_action),
            holdMs = 800,
            enabled = blocked == null,
            onConfirmed = onReboot,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The values a write confirmation shows, at the precision the list and editor read
 * ([ParamFile.displayValue]), never the FC echo's widened float: `[value]` for a save,
 * `[requested, actual]` for a clamp, empty for any other message.
 */
internal fun paramMessageValues(message: ParamMessage): List<String> = when (message) {
    is ParamMessage.Saved -> listOf(ParamFile.displayValue(message.value))
    is ParamMessage.Clamped -> listOf(ParamFile.displayValue(message.requested), ParamFile.displayValue(message.actual))
    else -> emptyList()
}

/** Operator text and status level for a [ParamMessage]; shared by the parameter and setup pages. */
@Composable
internal fun paramMessageText(message: ParamMessage, rows: Map<String, ParamRow>): Pair<String, StatusLevel> = when (message) {
    is ParamMessage.Saved -> stringResource(
        if (message.rebootRequired) R.string.cfg_param_saved_reboot else R.string.cfg_param_saved,
        message.name, *paramMessageValues(message).toTypedArray(),
    ) to StatusLevel.Success
    is ParamMessage.Clamped -> stringResource(
        R.string.cfg_param_clamped, message.name, *paramMessageValues(message).toTypedArray(),
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
    is ParamMessage.Invalid -> "${message.name}: ${invalidReasonText(message.name, message.reason, rows[message.name]?.meta)}" to StatusLevel.Error
    is ParamMessage.LoadIncomplete -> stringResource(R.string.cfg_param_incomplete, message.missing) to StatusLevel.Warning
    is ParamMessage.FileUnreadable -> stringResource(R.string.cfg_param_file_unreadable) to StatusLevel.Error
    ParamMessage.FileSaved -> stringResource(R.string.cfg_param_file_saved) to StatusLevel.Success
    ParamMessage.FileWriteFailed -> stringResource(R.string.cfg_param_file_write_failed) to StatusLevel.Error
    is ParamMessage.Reboot -> stringResource(R.string.cfg_reboot_result, commandResultText(message.result)) to
        if (message.result == CommandResult.Accepted) StatusLevel.Success else StatusLevel.Error
    is ParamMessage.ImportStopped -> when (val name = message.unconfirmed) {
        null -> stringResource(R.string.cfg_import_stopped, message.applied, message.total)
        else -> stringResource(R.string.cfg_import_stopped_unconfirmed, message.applied, message.total, name)
    } to StatusLevel.Warning
}

@Composable
private fun MessageLine(message: ParamMessage, rows: Map<String, ParamRow>, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val (text, level) = paramMessageText(message, rows)
    Row(
        Modifier.fillMaxWidth().heightIn(min = AdosTheme.touchMin).clickable(onClick = onDismiss).padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(label = text, level = level)
        Text(stringResource(R.string.cfg_dismiss), color = colors.fgTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = AdosSpacing.S2))
    }
}

/** Operator text for a [ParametersUiState.LoadFailed] reason; never the raw reason token. */
@Composable
internal fun paramLoadReason(reason: String): String = stringResource(paramLoadReasonRes(reason))

internal fun paramLoadReasonRes(reason: String): Int = when (reason) {
    MavlinkParamService.REASON_NO_LINK -> R.string.cfg_block_no_vehicle
    MavlinkParamService.REASON_TIMEOUT -> R.string.cfg_result_timeout
    else -> linkReasonRes(reason)
}

/** Title and message for the hold-to-write sheet of a [PendingWrite]; an unknown arm state reads as armed. */
internal fun pendingWriteTextRes(p: PendingWrite): Pair<Int, Int> =
    if (p.armUnknown) R.string.cfg_param_unknown_title to R.string.cfg_param_unknown_message
    else R.string.cfg_param_armed_title to R.string.cfg_param_armed_message

/** Hold-to-write confirmation for a write parked by an armed or arm-unknown vehicle. */
@Composable
fun PendingWriteSheet(p: PendingWrite, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, message) = pendingWriteTextRes(p)
    AdosConfirmSheet(
        title = stringResource(title),
        message = stringResource(message, p.name, ParamFile.displayValue(p.value)),
        onDismiss = onDismiss,
        danger = true,
    ) {
        HoldToConfirmButton(
            label = stringResource(R.string.cfg_param_armed_confirm),
            holdMs = 800,
            danger = true,
            onConfirmed = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
