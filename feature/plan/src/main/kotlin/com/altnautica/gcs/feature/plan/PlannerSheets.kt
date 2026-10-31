package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.feature.plan.model.PlanCommand
import java.text.DateFormat
import java.util.Date

// Planner sheets: vehicle picker, plan library and per-item actions.

@Composable
internal fun VehiclePickerSheet(state: PlannerUiState, onPick: (DeviceId?) -> Unit, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    AdosSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Text(stringResource(R.string.plan_vehicle_picker_title), style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
            if (state.nodes.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.AddLocationAlt,
                    title = stringResource(R.string.plan_vehicle_picker_empty_title),
                    message = stringResource(R.string.plan_vehicle_picker_empty_message),
                )
            }
            state.nodes.forEach { node ->
                val (level, livenessLabel) = when (node.liveness) {
                    Liveness.Live -> StatusLevel.Success to stringResource(R.string.plan_liveness_live)
                    Liveness.Stale -> StatusLevel.Warning to stringResource(R.string.plan_liveness_stale)
                    Liveness.Offline -> StatusLevel.Neutral to stringResource(R.string.plan_liveness_offline)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(node.id) }.padding(vertical = AdosSpacing.S2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(node.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
                    StatusChip(level = level, label = livenessLabel)
                }
            }
            if (state.nodeId != null) {
                AdosButton(stringResource(R.string.plan_vehicle_picker_detach), onClick = { onPick(null) }, variant = AdosButtonVariant.Secondary)
            }
            Spacer(Modifier.padding(AdosSpacing.S2))
        }
    }
}

@Composable
internal fun LibrarySheet(state: PlannerUiState, actions: PlannerActions, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AdosSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4)) {
            Text(stringResource(R.string.plan_library_title), style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
            if (state.library.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.plan_library_empty_title),
                    message = stringResource(R.string.plan_library_empty_message),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(state.library, key = { it.id }) { plan ->
                        val openLabel = stringResource(R.string.plan_library_open, plan.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = openLabel) {
                                    actions.onLoad(plan.id)
                                    onDismiss()
                                }
                                .padding(vertical = AdosSpacing.S2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(plan.name, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
                                Text(
                                    pluralStringResource(R.plurals.plan_library_item_summary, plan.itemCount, plan.itemCount, dateFormat.format(Date(plan.updatedAtMs))),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.fgSecondary,
                                )
                            }
                            IconButton(onClick = { actions.onDeleteSaved(plan.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.plan_library_delete, plan.name), tint = colors.fgSecondary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.padding(AdosSpacing.S3))
        }
    }
}

@Composable
internal fun ItemActionsSheet(state: PlannerUiState, id: Long, actions: PlannerActions, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val index = state.plan.items.indexOfFirst { it.id == id }
    if (index < 0) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    AdosSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4)) {
            Text(stringResource(R.string.plan_item_title, index + 1), style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
            SheetAction(stringResource(R.string.plan_item_insert_before)) { actions.onInsertNear(id, false); onDismiss() }
            SheetAction(stringResource(R.string.plan_item_insert_after)) { actions.onInsertNear(id, true); onDismiss() }
            PlanCommand.entries.filter { it != PlanCommand.Waypoint }.forEach { command ->
                SheetAction(stringResource(R.string.plan_item_insert_command, commandLabel(command))) {
                    actions.onInsertCommandAfter(id, command)
                    onDismiss()
                }
            }
            SheetAction(stringResource(R.string.plan_item_delete), danger = true) { actions.onDeleteItem(id); onDismiss() }
            Spacer(Modifier.padding(AdosSpacing.S3))
        }
    }
}

@Composable
private fun SheetAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    Text(
        label,
        modifier = Modifier.fillMaxWidth().heightIn(min = AdosTheme.touchMin).clickable(onClick = onClick).padding(vertical = AdosSpacing.S3),
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) colors.statusError else colors.fgPrimary,
    )
}
