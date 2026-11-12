package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.GlassSurface
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuState
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.titleRes
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import kotlin.math.roundToInt

private val ChipPadding = PaddingValues(horizontal = AdosSpacing.S1, vertical = AdosSpacing.S1)
private val DirtyDot = 8.dp

/** Top-left glass chip: back, the vehicle (tap to choose) and the plan title (tap to rename). */
@Composable
internal fun PlanTitleChip(
    state: PlannerUiState,
    onBack: () -> Unit,
    onVehicle: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(AdosRadius.Lg)
    GlassSurface(modifier = modifier, contentPadding = ChipPadding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.plan_back), tint = colors.hudInk)
            }
            Box(
                Modifier
                    .consoleFocus(shape)
                    .clickable(onClickLabel = stringResource(R.string.plan_vehicle_action), onClick = onVehicle),
            ) { VehicleChip(state) }
            PlanTitle(
                name = state.name.ifBlank { stringResource(R.string.plan_untitled) },
                dirty = state.dirty,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .heightIn(min = AdosTheme.touchMin)
                    .consoleFocus(shape)
                    .clickable(onClickLabel = stringResource(R.string.plan_rename_action), onClick = onRename)
                    .padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S2),
            )
        }
    }
}

/** Plan name and unsaved dot; one line however narrow the chip gets. */
@Composable
internal fun PlanTitle(name: String, dirty: Boolean, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Text(
            name,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall,
            color = colors.hudInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (dirty) {
            val unsaved = stringResource(R.string.plan_unsaved)
            Box(
                Modifier
                    .size(DirtyDot)
                    .background(colors.accentPrimary, CircleShape)
                    .semantics { contentDescription = unsaved },
            )
        }
    }
}

@Composable
private fun VehicleChip(state: PlannerUiState) {
    val (level, label) = when (val link = state.link) {
        VehicleLink.None -> StatusLevel.Neutral to stringResource(R.string.plan_vehicle_none)
        VehicleLink.Connecting -> StatusLevel.Info to stringResource(R.string.plan_vehicle_connecting)
        VehicleLink.Unsupported -> StatusLevel.Warning to stringResource(R.string.plan_vehicle_unsupported)
        is VehicleLink.Connected ->
            if (link.linkFresh) StatusLevel.Success to stringResource(R.string.plan_vehicle_live) else StatusLevel.Warning to stringResource(R.string.plan_vehicle_stale)
    }
    val name = state.nodeName ?: stringResource(R.string.plan_vehicle_choose)
    StatusChip(level = level, label = if (state.link == VehicleLink.None) name else stringResource(R.string.plan_pair_label, name, label))
}

/** Top-right glass cluster: basemap, overflow and (landscape) the inspector toggle. */
@Composable
internal fun MapActionsChip(
    state: PlannerUiState,
    actions: PlannerActions,
    overflow: AdosMenuState,
    onLibrary: () -> Unit,
    onToggleInspector: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    GlassSurface(modifier = modifier, contentPadding = ChipPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasemapMenu(state, actions)
            IconButton(onClick = { overflow.expanded = true }, modifier = Modifier.adosMenuAnchor(overflow)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.plan_action_more), tint = colors.hudInk)
            }
            if (onToggleInspector != null) {
                IconButton(onClick = onToggleInspector) {
                    Icon(Icons.AutoMirrored.Outlined.ViewSidebar, contentDescription = stringResource(R.string.plan_action_inspector), tint = colors.hudInk)
                }
            }
        }
    }
    OverflowMenu(state, actions, overflow, onLibrary)
}

@Composable
private fun BasemapMenu(state: PlannerUiState, actions: PlannerActions) {
    val menu = rememberAdosMenuState()
    IconButton(onClick = { menu.expanded = true }, modifier = Modifier.adosMenuAnchor(menu)) {
        Icon(Icons.Outlined.Layers, contentDescription = stringResource(R.string.plan_action_basemap), tint = AdosTheme.colors.hudInk)
    }
    val onlineItems = MapProvider.entries.map { provider ->
        AdosMenuItem(
            label = stringResource(provider.titleRes),
            icon = if ((state.basemap as? MapBasemap.Online)?.provider == provider) Icons.Outlined.Flag else null,
        ) { actions.onBasemap(MapBasemap.Online(provider)) }
    }
    val packItems = state.packs.map { pack ->
        AdosMenuItem(
            label = pack.name,
            icon = if ((state.basemap as? MapBasemap.Pack)?.pack?.id == pack.id) Icons.Outlined.Flag else null,
        ) { actions.onBasemap(MapBasemap.Pack(pack)) }
    }
    AdosMenu(menu, onlineItems + packItems)
}

/** Plan menu: file actions and undo/redo. Opened from the overflow button or OPTIONS. */
@Composable
private fun OverflowMenu(state: PlannerUiState, actions: PlannerActions, menu: AdosMenuState, onLibrary: () -> Unit) {
    val untitled = stringResource(R.string.plan_untitled)
    AdosMenu(
        menu,
        listOf(
            AdosMenuItem(stringResource(R.string.plan_action_undo), enabled = state.canUndo) { actions.onUndo() },
            AdosMenuItem(stringResource(R.string.plan_action_redo), enabled = state.canRedo) { actions.onRedo() },
            AdosMenuItem(stringResource(R.string.plan_action_save)) { actions.onSave(untitled) },
            AdosMenuItem(stringResource(R.string.plan_action_library)) { onLibrary() },
            AdosMenuItem(stringResource(R.string.plan_action_new)) { actions.onNew() },
            AdosMenuItem(stringResource(R.string.plan_action_import)) { actions.onImport() },
            AdosMenuItem(stringResource(R.string.plan_action_export_plan), enabled = !state.plan.isEmpty) {
                actions.onExport(PlanFileFormat.QgcPlan, state.name)
            },
            AdosMenuItem(stringResource(R.string.plan_action_export_waypoints), enabled = state.plan.items.isNotEmpty()) {
                actions.onExport(PlanFileFormat.Waypoints, state.name)
            },
        ),
    )
}

/** Bottom-left glass strip: path length, item count and highest relative altitude. */
@Composable
internal fun PlanStatsStrip(state: PlannerUiState, modifier: Modifier = Modifier) {
    val stats = state.stats
    if (stats.itemCount == 0) return
    val colors = AdosTheme.colors
    val distance = if (stats.distanceM >= 1000.0) {
        stringResource(R.string.plan_stats_distance_km, stats.distanceM / 1000.0)
    } else {
        stringResource(R.string.plan_stats_distance_m, stats.distanceM.roundToInt())
    }
    val parts = listOfNotNull(
        distance,
        pluralStringResource(R.plurals.plan_stats_items, stats.itemCount, stats.itemCount),
        stats.maxAltRelM?.let { stringResource(R.string.plan_stats_max_alt, it.roundToInt()) },
    )
    GlassSurface(modifier = modifier, contentPadding = PaddingValues(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S1)) {
        Text(
            parts.joinToString("  ·  "),
            style = AdosTypography.numeric.small,
            color = colors.hudInk,
            maxLines = 1,
        )
    }
}

/** Rename dialog opened from the title chip. */
@Composable
internal fun RenamePlanDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AdosDialog(
        title = stringResource(R.string.plan_rename_title),
        onDismiss = onDismiss,
        confirm = {
            AdosButton(stringResource(R.string.plan_rename_confirm), onClick = {
                onRename(text.trim())
                onDismiss()
            })
        },
        dismiss = { AdosButton(stringResource(R.string.plan_cancel), onClick = onDismiss, variant = AdosButtonVariant.Secondary) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.plan_rename_label)) },
                    singleLine = true,
                )
            }
        },
    )
}
