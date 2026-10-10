@file:OptIn(ExperimentalMaterial3Api::class)

package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.window.core.layout.WindowSizeClass
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.GlassSurface
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.map.AdosMap
import com.altnautica.gcs.core.map.DragPhase
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.MapVehicle
import com.altnautica.gcs.core.map.rememberAdosMapState
import com.altnautica.gcs.core.map.titleRes
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.model.PlanCommand
import java.text.DateFormat
import java.util.Date

/** Stateless mission planner. */
@Composable
fun PlannerScreen(state: PlannerUiState, actions: PlannerActions, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }

    var showVehiclePicker by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var itemActionsFor by rememberSaveable { mutableStateOf<Long?>(null) }

    val widthClass = currentWindowAdaptiveInfo().windowSizeClass
    val expanded = widthClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                PlannerMap(state, actions, onItemLongPress = { itemActionsFor = it }, Modifier.weight(1f).fillMaxHeight(), onVehicle = { showVehiclePicker = true }, onLibrary = { showLibrary = true })
                Column(
                    Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(AdosSpacing.S3),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
                ) {
                    PlannerPanel(state, actions)
                }
            }
        } else {
            PlannerMap(state, actions, onItemLongPress = { itemActionsFor = it }, Modifier.fillMaxSize(), onVehicle = { showVehiclePicker = true }, onLibrary = { showLibrary = true })
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .padding(AdosSpacing.S2),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
                ) {
                    PlannerPanel(state, actions)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
    }

    if (showVehiclePicker) {
        VehiclePickerSheet(state, onPick = { actions.onSelectVehicle(it); showVehiclePicker = false }, onDismiss = { showVehiclePicker = false })
    }
    if (showLibrary) {
        LibrarySheet(state, actions, onDismiss = { showLibrary = false })
    }
    itemActionsFor?.let { id ->
        ItemActionsSheet(state, id, actions, onDismiss = { itemActionsFor = null })
    }
    state.startSheet?.let { StartMissionSheetView(it, actions) }
    if (state.confirmReplaceWithDownload) {
        AlertDialog(
            onDismissRequest = actions.onCancelDownload,
            title = { Text(stringResource(R.string.plan_download_replace_title)) },
            text = { Text(stringResource(R.string.plan_download_replace_message)) },
            confirmButton = { TextButton(onClick = actions.onConfirmDownload) { Text(stringResource(R.string.plan_download_replace_confirm)) } },
            dismissButton = { TextButton(onClick = actions.onCancelDownload) { Text(stringResource(R.string.plan_cancel)) } },
        )
    }
}

@Composable
private fun PlannerMap(
    state: PlannerUiState,
    actions: PlannerActions,
    onItemLongPress: (Long) -> Unit,
    modifier: Modifier,
    onVehicle: () -> Unit,
    onLibrary: () -> Unit,
) {
    val mapState = rememberAdosMapState()
    val link = state.link as? VehicleLink.Connected
    val vehicle = link?.position?.let { MapVehicle(it, link.headingDeg, stale = !link.linkFresh) }
    val overlays = remember(state.plan, state.selection, state.survey, state.surveyPreview, state.fenceDraft, state.link) { state.toOverlays() }

    // Frame the plan once it has content, else the vehicle.
    val framePoints = remember(state.savedId, state.name, state.plan.items.isEmpty()) {
        state.plan.items.mapNotNull { it.position } + state.plan.rally.map { it.position }
    }
    LaunchedEffect(framePoints) { if (framePoints.isNotEmpty()) mapState.fitTo(framePoints) }
    val hasVehicleFix = vehicle != null
    LaunchedEffect(hasVehicleFix) {
        if (hasVehicleFix && framePoints.isEmpty()) vehicle?.let { mapState.centerOn(it.position, 16.0) }
    }

    val description = stringResource(R.string.plan_map_description)
    Box(modifier) {
        AdosMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            state = mapState,
            basemap = state.basemap,
            vehicle = vehicle,
            home = state.homeMarker(),
            overlays = overlays,
            onMapClick = actions.onMapTap,
            onFeatureClick = actions.onFeatureClick,
            onFeatureLongClick = { ref -> if (ref.type == MapFeatureType.Waypoint) ref.id.toLongOrNull()?.let(onItemLongPress) },
            onFeatureDrag = { ref, point, phase -> if (phase != DragPhase.Start) actions.onFeatureMoved(ref, point) },
        )
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            PlannerTopBar(
                state = state,
                actions = actions,
                onVehicle = onVehicle,
                onLibrary = onLibrary,
                onFit = {
                    val all = state.plan.items.mapNotNull { it.position } + state.plan.rally.map { it.position } + state.survey
                    if (all.isNotEmpty()) mapState.fitTo(all)
                },
            )
            ToolRow(state.tool, actions.onTool)
        }
    }
}

@Composable
private fun PlannerTopBar(
    state: PlannerUiState,
    actions: PlannerActions,
    onVehicle: () -> Unit,
    onLibrary: () -> Unit,
    onFit: () -> Unit,
) {
    val colors = AdosTheme.colors
    val untitled = stringResource(R.string.plan_untitled)
    GlassSurface(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Box(Modifier.clickable(onClick = onVehicle)) { VehicleChip(state) }
            PlanTitle(name = state.name.ifBlank { untitled }, dirty = state.dirty, modifier = Modifier.weight(1f))
            IconButton(onClick = onFit) {
                Icon(Icons.Outlined.CenterFocusWeak, contentDescription = stringResource(R.string.plan_action_fit), tint = colors.hudInk)
            }
            IconButton(onClick = { actions.onSave(untitled) }) {
                Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.plan_action_save), tint = colors.hudInk)
            }
            IconButton(onClick = onLibrary) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.plan_action_library), tint = colors.hudInk)
            }
            BasemapMenu(state, actions)
            OverflowMenu(state, actions)
        }
    }
}

/** Plan name and unsaved marker; both stay on one line however narrow the bar gets. */
@Composable
internal fun PlanTitle(name: String, dirty: Boolean, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Column(modifier) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.hudInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (dirty) {
            Text(
                stringResource(R.string.plan_unsaved),
                style = MaterialTheme.typography.labelSmall,
                color = colors.hudInk2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

@Composable
private fun BasemapMenu(state: PlannerUiState, actions: PlannerActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Layers, contentDescription = stringResource(R.string.plan_action_basemap), tint = AdosTheme.colors.hudInk)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(stringResource(provider.titleRes)) },
                    onClick = {
                        actions.onBasemap(MapBasemap.Online(provider))
                        open = false
                    },
                    trailingIcon = if ((state.basemap as? MapBasemap.Online)?.provider == provider) {
                        { Icon(Icons.Outlined.Flag, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
            state.packs.forEach { pack ->
                DropdownMenuItem(
                    text = { Text(pack.name) },
                    onClick = {
                        actions.onBasemap(MapBasemap.Pack(pack))
                        open = false
                    },
                    trailingIcon = if ((state.basemap as? MapBasemap.Pack)?.pack?.id == pack.id) {
                        { Icon(Icons.Outlined.Flag, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(state: PlannerUiState, actions: PlannerActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.plan_action_more), tint = AdosTheme.colors.hudInk)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.plan_action_new)) }, onClick = { actions.onNew(); open = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.plan_action_import)) }, onClick = { actions.onImport(); open = false })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plan_action_export_plan)) },
                onClick = { actions.onExport(PlanFileFormat.QgcPlan, state.name); open = false },
                enabled = !state.plan.isEmpty,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plan_action_export_waypoints)) },
                onClick = { actions.onExport(PlanFileFormat.Waypoints, state.name); open = false },
                enabled = state.plan.items.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ToolRow(tool: EditTool, onTool: (EditTool) -> Unit) {
    val tools = listOf(
        Triple(EditTool.Waypoint, Icons.Outlined.AddLocationAlt, R.string.plan_tool_waypoint),
        Triple(EditTool.Survey, Icons.Outlined.GridOn, R.string.plan_tool_survey),
        Triple(EditTool.FencePolygon, Icons.Outlined.Pentagon, R.string.plan_tool_fence_polygon),
        Triple(EditTool.FenceCircle, Icons.Outlined.RadioButtonUnchecked, R.string.plan_tool_fence_circle),
        Triple(EditTool.Rally, Icons.Outlined.Route, R.string.plan_tool_rally),
    )
    GlassSurface(contentPadding = androidx.compose.foundation.layout.PaddingValues(AdosSpacing.S1)) {
        SingleChoiceSegmentedButtonRow {
            tools.forEachIndexed { index, (t, icon, label) ->
                SegmentedButton(
                    selected = t == tool,
                    onClick = { onTool(t) },
                    shape = SegmentedButtonDefaults.itemShape(index, tools.size),
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(stringResource(label), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun VehiclePickerSheet(state: PlannerUiState, onPick: (com.altnautica.gcs.core.model.DeviceId?) -> Unit, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgSurface) {
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
private fun LibrarySheet(state: PlannerUiState, actions: PlannerActions, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgSurface) {
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
private fun ItemActionsSheet(state: PlannerUiState, id: Long, actions: PlannerActions, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val index = state.plan.items.indexOfFirst { it.id == id }
    if (index < 0) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgSurface) {
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
