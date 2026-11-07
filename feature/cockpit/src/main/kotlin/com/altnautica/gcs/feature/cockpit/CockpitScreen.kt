package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.extensions.ui.ExtensionMarksLayer
import com.altnautica.gcs.core.extensions.ui.ExtensionParametersPanel
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotHost
import com.altnautica.gcs.core.map.AdosMinimap
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.video.ui.VideoSurface
import com.altnautica.gcs.core.video.ui.VideoSurfaceRole
import com.altnautica.gcs.feature.cockpit.alerts.AlertStack
import com.altnautica.gcs.feature.cockpit.autonomy.MinimapFrame
import com.altnautica.gcs.feature.cockpit.band.SafetyBand
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChip
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmHost
import com.altnautica.gcs.feature.cockpit.flow.GateRing
import com.altnautica.gcs.feature.cockpit.flow.HandoverBanner
import com.altnautica.gcs.feature.cockpit.flow.LinkLossBanner
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.flow.LowBatteryBanner
import com.altnautica.gcs.feature.cockpit.flow.SticksWithBanner
import com.altnautica.gcs.feature.cockpit.hud.HudCanvas
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerLevelButton
import com.altnautica.gcs.feature.cockpit.hud.layers.VisionSidePanel
import com.altnautica.gcs.feature.cockpit.launch.LaunchSheet
import com.altnautica.gcs.feature.cockpit.modes.ModeSheet
import com.altnautica.gcs.feature.cockpit.panels.QuickSettingsPanel
import com.altnautica.gcs.feature.cockpit.panels.StreamTabs
import com.altnautica.gcs.feature.cockpit.panels.TelemetryStrip
import com.altnautica.gcs.feature.cockpit.pilot.ON_SCREEN_PAD
import com.altnautica.gcs.feature.cockpit.pilot.ThumbPad
import com.altnautica.gcs.feature.cockpit.preflight.PreflightSheet
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerChip
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerSheet
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** Stacking order inside the cockpit. */
private object Z {
    const val VIDEO = 0f
    const val EXT_OVERLAY = 10f
    const val MARKS = 12f
    const val HUD = 15f
    const val ZONES = 20f
    const val PIP = 25f
    const val CONTROLS = 30f
    const val BAND = 40f
    const val ALERTS = 45f
    const val SHEETS = 50f
}

private val WIDGET_ZONES = listOf("top-left", "top-right", "bottom-left", "bottom-right")

/**
 * The cockpit grid. Landscape: the top bar (flight-state chip, status, battery, link, nav
 * source, controller, layers, settings), the left column (take off or land, return home,
 * pause), the right column (shutter, record, zoom, gimbal recentre, camera settings), the
 * bottom-left card (map or radar), the bottom row (distance, height and speeds), the skill
 * picker chip at the bottom right, and the HUD and data layers in the centre. The main view
 * is the video, the map, both side by side, or the video with the vision panel. Portrait:
 * the status band, then the chip and layers row, the 16:9 video, the left-column actions as a row, the skill
 * picker, telemetry and the map. Each region collects only the flows it draws.
 */
@Composable
internal fun CockpitScreen(
    vm: CockpitViewModel,
    width: WidthClass,
    connecting: Boolean,
    onExit: () -> Unit,
    snackbar: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCockpitStrings provides vm.specStrings) {
        Box(Modifier.fillMaxSize()) {
            if (!isLandscape()) PortraitLayout(vm, width, onExit) else LandscapeGrid(vm, width, onExit)
            if (connecting) {
                Box(Modifier.align(Alignment.Center).zIndex(Z.ALERTS)) {
                    LoadingState(stringResource(R.string.cockpit_connecting))
                }
            }
            Overlays(vm, width)
            // Above the sheets, so the result of an action taken in a sheet stays readable.
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).zIndex(Z.SHEETS + 1)) { snackbar() }
        }
    }
}

/** The top bar: the flight-state chip, then the status band and the layer level button. */
@Composable
private fun TopBar(vm: CockpitViewModel, width: WidthClass, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val chip by vm.chip.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        FlightStateChip(chip, onOpen = vm::openModeSheet, onButton = vm::onChipButton, modifier = Modifier.padding(start = AdosSpacing.S2))
        Band(vm, width, onExit, Modifier.weight(1f))
        LayerLevelButton(vm.autonomy.layers, Modifier.padding(end = AdosSpacing.S2))
    }
}

/**
 * The portrait top bar, above the video: the status band across the full width (so the exit
 * arrow and vehicle name keep their room), then the flight-state chip and the layer level
 * button on their own row.
 */
@Composable
private fun PortraitTopBar(vm: CockpitViewModel, width: WidthClass, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val chip by vm.chip.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        Band(vm, width, onExit, Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            FlightStateChip(chip, onOpen = vm::openModeSheet, onButton = vm::onChipButton)
            Box(Modifier.weight(1f))
            LayerLevelButton(vm.autonomy.layers)
        }
    }
}

@Composable
private fun Band(vm: CockpitViewModel, width: WidthClass, onExit: () -> Unit, modifier: Modifier) {
    val band by vm.band.collectAsStateWithLifecycle()
    SafetyBand(
        state = band,
        onExit = onExit,
        onToggleStick = vm::toggleStickControl,
        onOpenPreflight = { vm.setPreflight(true) },
        onOpenQuickSettings = { vm.setQuickSettings(true) },
        onOpenModes = vm::openModeSheet,
        onKill = { vm.activate(BuiltinIds.KILL) },
        navChip = { NavChipSlot(vm, width) },
        modifier = modifier,
    )
}

@Composable
private fun LandscapeGrid(vm: CockpitViewModel, width: WidthClass, onExit: () -> Unit) {
    val view by vm.view.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val onScreen by vm.onScreenSticksEnabled.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val sizes = gridSizes(width)
    val sticksShown = onScreen && (width == WidthClass.Compact || width == WidthClass.Expanded)
    val inward = if (sticksShown) ON_SCREEN_PAD + AdosSpacing.S4 else 0.dp
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, width, onExit, Modifier.zIndex(Z.BAND))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val fit = gridFit(maxHeight, sizes, canRecord)
            // A card beside the left column starts after it.
            val cardShift = if (fit.cardBesideColumn) sizes.leftButton + AdosSpacing.S2 else 0.dp
            // The HUD is the centre zone: between the columns, so their buttons never cover its tapes.
            val hudInset = PaddingValues(start = GridEdge * 2 + sizes.leftButton, end = GridEdge * 2 + sizes.rightWidth)
            MainView(vm, view, settings.splitRatio, hudInset, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopCenter).padding(top = AdosSpacing.S2).zIndex(Z.ALERTS), horizontalAlignment = Alignment.CenterHorizontally) {
                Tabs(vm)
                Alerts(vm, Modifier.padding(top = AdosSpacing.S2))
                FlowBanners(vm, Modifier.padding(top = AdosSpacing.S2).width(560.dp))
            }
            CentreRings(vm, Modifier.align(Alignment.Center).zIndex(Z.ALERTS))
            WidgetZones(vm, Modifier.fillMaxSize().padding(horizontal = sizes.column + AdosSpacing.S4, vertical = 72.dp).zIndex(Z.ZONES))
            FollowChrome(vm, width, topInset = 0.dp, modifier = Modifier.zIndex(Z.ZONES))
            Box(Modifier.fillMaxHeight().padding(start = GridEdge, top = GridEdge, bottom = fit.leftBottom).zIndex(Z.CONTROLS)) {
                LeftColumn(vm, sizes, Modifier.align(Alignment.CenterStart))
            }
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(end = GridEdge, top = GridEdge, bottom = fit.rightBottom).zIndex(Z.CONTROLS)) {
                RightColumn(vm, sizes.shutter, fit.rightButton, fit.cameraSettingsInColumn, Modifier.align(Alignment.CenterEnd))
            }
            // Bottom row: the card, the telemetry row between it and the picker chip, the chip.
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = GridEdge + inward + cardShift, end = GridEdge + inward, bottom = GridEdge).zIndex(Z.CONTROLS),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            ) {
                BottomLeftCard(vm, width, fit)
                Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) { Strip(vm) }
                PickerChip(vm)
            }
            if (sticksShown) OnScreenSticksRow(vm, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(AdosSpacing.S2).zIndex(Z.CONTROLS))
        }
    }
}

/** The main view: video, map with video picture-in-picture, the two side by side, or video with the vision panel. */
@Composable
private fun MainView(vm: CockpitViewModel, view: CockpitView, savedRatio: Float, hudInset: PaddingValues, modifier: Modifier) {
    val startOnly = PaddingValues(start = hudInset.calculateStartPadding(LayoutDirection.Ltr))
    when (view) {
        CockpitView.Pilot -> VideoStage(vm, modifier, hudInset = hudInset)
        CockpitView.Map -> Box(modifier) {
            Minimap(vm, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 72.dp)
                    .size(width = 240.dp, height = 135.dp)
                    .clip(RoundedCornerShape(AdosRadius.Lg))
                    .zIndex(Z.PIP),
            ) {
                VideoStage(vm, Modifier.fillMaxSize(), showHud = false, showLayers = false)
            }
        }
        CockpitView.Split -> SplitView(vm, savedRatio, startOnly, modifier)
        CockpitView.Vision -> Row(modifier) {
            VideoStage(vm, Modifier.weight(1f).fillMaxHeight(), hudInset = startOnly)
            val video by vm.video.collectAsStateWithLifecycle()
            VisionSidePanel(vm, vm.autonomy.mainCamera(video), Modifier.width(320.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun SplitView(vm: CockpitViewModel, savedRatio: Float, hudInset: PaddingValues, modifier: Modifier) {
    var ratio by remember(savedRatio) { mutableFloatStateOf(savedRatio) }
    val splitLabel = stringResource(R.string.cockpit_split_divider)
    BoxWithConstraints(modifier) {
        val total = constraints.maxWidth.toFloat()
        Row(Modifier.fillMaxSize()) {
            VideoStage(vm, Modifier.weight(ratio).fillMaxHeight(), hudInset = hudInset)
            Box(
                Modifier
                    .width(12.dp)
                    .fillMaxHeight()
                    .background(AdosTheme.colors.bgSurface)
                    .semantics { contentDescription = splitLabel }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            ratio = (ratio + delta / total).coerceIn(CockpitSettingsRepository.MIN_SPLIT, CockpitSettingsRepository.MAX_SPLIT)
                        },
                        onDragStopped = { vm.setSplitRatio(ratio) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 4.dp, height = 40.dp).background(AdosTheme.colors.borderStrong, RoundedCornerShape(AdosRadius.Sm)))
            }
            Minimap(vm, Modifier.weight(1f - ratio).fillMaxHeight())
        }
    }
}

@Composable
private fun PortraitLayout(vm: CockpitViewModel, width: WidthClass, onExit: () -> Unit) {
    val sizes = gridSizes(width)
    Column(Modifier.fillMaxSize().background(AdosTheme.colors.bgCanvas)) {
        PortraitTopBar(vm, width, onExit, Modifier.zIndex(Z.BAND))
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            VideoStage(vm, Modifier.fillMaxSize())
            CentreRings(vm, Modifier.align(Alignment.Center).zIndex(Z.ALERTS))
            FollowChrome(vm, width, topInset = 0.dp, modifier = Modifier.zIndex(Z.ZONES))
        }
        Column(Modifier.padding(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                FlightActions(vm, sizes.shutter)
                Box(Modifier.weight(1f))
                PickerChip(vm)
            }
            FlowBanners(vm, Modifier.fillMaxWidth())
            Tabs(vm)
            Alerts(vm, Modifier.fillMaxWidth())
            Strip(vm)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S2)) {
            Minimap(vm, Modifier.fillMaxSize().clip(RoundedCornerShape(AdosRadius.Xl)))
            Radar(vm, width, Modifier.align(Alignment.BottomEnd).padding(AdosSpacing.S2))
        }
    }
}

/** A round grid button: icon, label for accessibility, 48 dp minimum. */
@Composable
private fun GridButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    tag: String,
    onClick: () -> Unit,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val colors = AdosTheme.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) colors.accentPrimary else colors.hudGlass)
            .combinedClickable(onClickLabel = label, role = Role.Button, onLongClickLabel = longClickLabel, onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) colors.accentForeground else colors.hudInk)
    }
}

/** Take off (opens the Launch sheet) or land, return home, pause: the built-ins' own gates and confirms apply. */
@Composable
private fun FlightActions(vm: CockpitViewModel, size: Dp) {
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val airborne = pilot?.let { it.armed && !it.landed } ?: (band.armed == true)
    val labels = vm.cockpitSpec.builtins.builtins.associate { it.id to it.label }
    if (airborne) {
        GridButton(Icons.Outlined.FlightLand, labels[BuiltinIds.LAND].orEmpty(), size, "grid-land", { vm.activate(BuiltinIds.LAND) })
    } else {
        GridButton(Icons.Outlined.FlightTakeoff, labels[BuiltinIds.TAKEOFF].orEmpty(), size, "grid-takeoff", { vm.openLaunch(true) })
    }
    GridButton(Icons.Outlined.Home, labels[BuiltinIds.RTH].orEmpty(), size, "grid-rth", { vm.activate(BuiltinIds.RTH) })
    GridButton(Icons.Outlined.PauseCircle, labels[BuiltinIds.PAUSE].orEmpty(), size, "grid-pause", { vm.activate(BuiltinIds.PAUSE) })
}

@Composable
private fun LeftColumn(vm: CockpitViewModel, sizes: GridSizes, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3), horizontalAlignment = Alignment.CenterHorizontally) {
        FlightActions(vm, sizes.leftButton)
    }
}

/** The right column; without room for every button, camera settings move to zoom-in's long-press. */
@Composable
private fun RightColumn(vm: CockpitViewModel, shutter: Dp, button: Dp, cameraSettings: Boolean, modifier: Modifier) {
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val openSettings = { vm.setQuickSettings(true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2), horizontalAlignment = Alignment.CenterHorizontally) {
        GridButton(
            Icons.Outlined.ZoomIn,
            stringResource(R.string.grid_zoom_in),
            button,
            "grid-zoom-in",
            vm::zoomIn,
            onLongClick = if (cameraSettings) null else openSettings,
            longClickLabel = if (cameraSettings) null else stringResource(R.string.grid_camera_settings),
        )
        GridButton(Icons.Outlined.PhotoCamera, stringResource(R.string.grid_shutter), shutter, "grid-shutter", vm::capturePhoto)
        if (canRecord) {
            GridButton(Icons.Outlined.FiberManualRecord, stringResource(R.string.grid_record), button, "grid-record", vm::toggleRecording, active = recording)
        }
        GridButton(Icons.Outlined.ZoomOut, stringResource(R.string.grid_zoom_out), button, "grid-zoom-out", vm::zoomOut)
        GridButton(Icons.Outlined.CenterFocusStrong, stringResource(R.string.grid_gimbal_recenter), button, "grid-gimbal", vm::recenterGimbal)
        if (cameraSettings) {
            GridButton(Icons.Outlined.Tune, stringResource(R.string.grid_camera_settings), button, "grid-camera-settings", openSettings)
        }
    }
}

/**
 * The bottom-left card: the map or the radar, switched by tapping its toggle (or L3 swaps video
 * and map). Where it is too short for a card ([GridFit.card] null) it is the square map chip.
 * A long-press on the map picks the Launch sheet's map point.
 */
@Composable
private fun BottomLeftCard(vm: CockpitViewModel, width: WidthClass, fit: GridFit) {
    var radar by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(AdosRadius.Xl)
    val card = fit.card
    if (card == null) {
        // A chip; tapping it swaps video and map.
        Box(Modifier.size(fit.chip).clip(shape).testTag("grid-card")) {
            Minimap(vm, Modifier.fillMaxSize(), onClick = vm::toggleMinimapSwap)
        }
        return
    }
    val (w, h) = card
    Box(Modifier.size(width = w, height = h).clip(shape).background(AdosTheme.colors.hudGlass).testTag("grid-card")) {
        if (radar) {
            Radar(vm, width, Modifier.align(Alignment.Center))
        } else {
            Minimap(vm, Modifier.fillMaxSize(), onClick = vm::toggleMinimapSwap)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(AdosSpacing.S1)) {
            GridButton(
                if (radar) Icons.Outlined.Map else Icons.Outlined.Radar,
                stringResource(if (radar) R.string.grid_card_map else R.string.grid_card_radar),
                48.dp,
                "grid-card-toggle",
                { radar = !radar },
            )
        }
    }
}

@Composable
private fun PickerChip(vm: CockpitViewModel, modifier: Modifier = Modifier) {
    val chip by vm.pickerChip.collectAsStateWithLifecycle()
    SkillPickerChip(chip, onOpen = { vm.setPicker(true) }, modifier = modifier)
}

@Composable
private fun OnScreenSticksRow(vm: CockpitViewModel, modifier: Modifier) {
    val sticks = vm.onScreenSticks
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        ThumbPad(stringResource(R.string.on_screen_stick_left), sticks::setLeft, sticks::touch)
        ThumbPad(stringResource(R.string.on_screen_stick_right), sticks::setRight, sticks::touch)
    }
}

/** Handover banner, another surface holding the sticks, link-loss and low-battery countdowns. */
@Composable
private fun FlowBanners(vm: CockpitViewModel, modifier: Modifier) {
    val flow by vm.flow.collectAsStateWithLifecycle()
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        flow.banner?.let { HandoverBanner(it) }
        flow.sticksWith?.let { SticksWithBanner(it, vm::takeSticks) }
        flow.linkLossInS?.let { LinkLossBanner(it, pilot?.banner?.args?.get("action") ?: LINK_LOSS_DEFAULT_ACTION) }
        flow.rthCountdownS?.let { LowBatteryBanner(it, vm::cancelRthCountdown) }
    }
}

/** The pilot's link-loss action when its banner does not name one (`pilot.link_loss_action` default). */
private const val LINK_LOSS_DEFAULT_ACTION = "rth"

/** The centre-sticks gate ring and the controller hold ring. */
@Composable
private fun CentreRings(vm: CockpitViewModel, modifier: Modifier) {
    val flow by vm.flow.collectAsStateWithLifecycle()
    val hold by vm.controllerHold.collectAsStateWithLifecycle()
    Box(modifier) {
        flow.gateProgress?.let { GateRing(it) }
        hold?.let { h ->
            val label = vm.cockpitSpec.ds4.actions.firstOrNull { it.id == h.action }?.label ?: h.action
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GateRingProgress(h.progress)
                Text(label, color = AdosTheme.colors.hudInk)
            }
        }
    }
}

@Composable
private fun GateRingProgress(progress: Float) {
    val colors = AdosTheme.colors
    androidx.compose.foundation.Canvas(Modifier.size(64.dp).testTag("hold-ring")) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx())
        drawArc(colors.borderStrong, 0f, 360f, useCenter = false, style = stroke)
        drawArc(colors.accentPrimary, -90f, 360f * progress, useCenter = false, style = stroke)
    }
}

/** Video, extension overlay, marks, data layers and HUD. */
@Composable
private fun VideoStage(
    vm: CockpitViewModel,
    modifier: Modifier,
    showHud: Boolean = true,
    showLayers: Boolean = true,
    hudInset: PaddingValues = PaddingValues(),
) {
    val video by vm.video.collectAsStateWithLifecycle()
    val videoState by vm.videoState.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val swapped by vm.minimapSwapped.collectAsStateWithLifecycle()
    Box(modifier.background(AdosTheme.colors.mediaLetterbox)) {
        if (swapped && showHud) {
            Minimap(vm, Modifier.fillMaxSize())
            return@Box
        }
        VideoSurface(video.main, Modifier.fillMaxSize().zIndex(Z.VIDEO))
        if (videoState !is VideoState.Live) {
            val text = when (videoState) {
                VideoState.Connecting -> stringResource(R.string.cockpit_video_connecting_long)
                is VideoState.Frozen -> stringResource(R.string.cockpit_video_frozen_long)
                is VideoState.Failed -> stringResource(R.string.cockpit_video_failed, (videoState as VideoState.Failed).reason)
                else -> stringResource(R.string.cockpit_video_none_long)
            }
            Text(text, color = AdosTheme.colors.mediaOn, modifier = Modifier.align(Alignment.Center).zIndex(Z.VIDEO + 1))
        }
        extNode?.let { node ->
            ExtensionSlotHost(node, "video.overlay", Modifier.fillMaxSize().zIndex(Z.EXT_OVERLAY))
            ExtensionMarksLayer(node, Modifier.fillMaxSize().zIndex(Z.MARKS))
        }
        if (showLayers) VideoLayers(vm, vm.autonomy.mainCamera(video), modifier = Modifier.fillMaxSize().zIndex(Z.MARKS))
        if (showHud) {
            HudCanvas(
                telemetry = telemetry,
                altitudeReference = settings.altitudeReference,
                density = settings.density,
                modifier = Modifier.fillMaxSize().padding(hudInset).zIndex(Z.HUD),
            )
        }
        Pip(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).zIndex(Z.PIP))
    }
}

@Composable
private fun Minimap(vm: CockpitViewModel, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val state by vm.minimap.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val frame by vm.autonomy.minimapFrame.collectAsStateWithLifecycle()
    if (frame == MinimapFrame.Local) {
        // Pan and pinch are drag gestures, so a plain tap still swaps video and map.
        AutonomyLocalMinimap(vm, onClick?.let { modifier.clickable(onClick = it) } ?: modifier)
        return
    }
    Box(modifier) {
        // A long-press anywhere on a cockpit map picks a point: the launch go-to on the ground, fly-here in the air.
        AdosMinimap(state.vehicle, state.home, state.trail, Modifier.fillMaxSize(), zoom = MINIMAP_ZOOM, onClick = onClick, onLongPress = vm::pickMapPoint)
        extNode?.let { node ->
            val center = state.vehicle?.position
            ExtensionSlotHost(
                node,
                "map.overlay",
                Modifier.fillMaxSize(),
                hostProps = mapOf("center" to center?.let { mapOf("lat" to it.latDeg, "lon" to it.lonDeg) }, "zoom" to MINIMAP_ZOOM),
            )
        }
    }
}

private const val MINIMAP_ZOOM = 16.0

@Composable
private fun Tabs(vm: CockpitViewModel) {
    val video by vm.video.collectAsStateWithLifecycle()
    StreamTabs(video.streams, video.selectedStreamId, video.switchingStreamId, vm::selectStream)
}

@Composable
private fun Strip(vm: CockpitViewModel, modifier: Modifier = Modifier) {
    val strip by vm.strip.collectAsStateWithLifecycle()
    TelemetryStrip(strip, modifier)
}

@Composable
private fun Alerts(vm: CockpitViewModel, modifier: Modifier) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    AlertStack(alerts, modifier)
}

@Composable
private fun Radar(vm: CockpitViewModel, width: WidthClass, modifier: Modifier) {
    AutonomyRadar(vm, modifier, radarDiameter(width))
}

@Composable
private fun Pip(vm: CockpitViewModel, modifier: Modifier) {
    val video by vm.video.collectAsStateWithLifecycle()
    val pip = video.pip ?: return
    // The picture-in-picture never draws data layers.
    Box(modifier.size(width = 192.dp, height = 108.dp).clip(RoundedCornerShape(AdosRadius.Lg))) {
        VideoSurface(pip, Modifier.fillMaxSize(), role = VideoSurfaceRole.Pip)
    }
}

@Composable
private fun WidgetZones(vm: CockpitViewModel, modifier: Modifier) {
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val node = extNode ?: return
    Box(modifier.padding(AdosSpacing.S2)) {
        for (zone in WIDGET_ZONES) {
            val align = when (zone) {
                "top-left" -> Alignment.TopStart
                "top-right" -> Alignment.TopEnd
                "bottom-left" -> Alignment.BottomStart
                else -> Alignment.BottomEnd
            }
            ExtensionSlotHost(node, "cockpit.widget", Modifier.align(align).size(width = 240.dp, height = 160.dp), hostProps = mapOf("zone" to zone))
        }
    }
}

/** The skill's Tweak panel: its manifest parameters, rendered by the extension parameter panel. */
@Composable
private fun SkillTweak(vm: CockpitViewModel, skill: Skill) {
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val node = extNode ?: return
    val src = skill.source as? SkillSource.Extension ?: return
    val params by vm.autonomy.pluginParameters(src.pluginId, skill.params).collectAsStateWithLifecycle(emptyList())
    if (params.isEmpty()) return
    ExtensionParametersPanel(node, src.pluginId, params, Modifier.fillMaxWidth(), sectionKey = "tweak:${skill.id}", nudges = vm.paramNudges)
}

/** Quick settings and the sheets, all drawn in the cockpit window above the flight surface (z 50). */
@Composable
private fun Overlays(vm: CockpitViewModel, width: WidthClass) {
    val preflightOpen by vm.preflightOpen.collectAsStateWithLifecycle()
    val quickOpen by vm.quickSettingsOpen.collectAsStateWithLifecycle()
    val confirm by vm.confirm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val maxBrightness by vm.maxBrightness.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val stickAllModes by vm.stickAllModes.collectAsStateWithLifecycle()
    val wide = width == WidthClass.Expanded || width == WidthClass.Tv

    Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
        QuickSettingsPanel(
            open = quickOpen,
            settings = settings,
            maxBrightness = maxBrightness,
            showTelemetryPaneToggle = wide,
            canRecord = canRecord,
            recording = recording,
            pipAvailable = video.streams.count { it.live && it.switchKind == StreamSwitchKind.Concurrent } > 1,
            pipOn = video.pip != null,
            stickAllModes = stickAllModes,
            onDismiss = { vm.setQuickSettings(false) },
            onAltitudeReference = { vm.setAltitudeReference(it) },
            onDensity = { vm.setDensity(it) },
            onMaxBrightness = vm::setMaxBrightness,
            onTelemetryPane = { vm.setTelemetryPane(it) },
            onToggleRecording = vm::toggleRecording,
            onTogglePip = vm::togglePip,
            onStickAllModes = vm::setStickAllModes,
            panels = { extNode?.let { ExtensionSlotHost(it, "cockpit.panel", Modifier.fillMaxWidth()) } },
        )
    }
    val picker by vm.picker.collectAsStateWithLifecycle()
    picker?.let { ui ->
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            SkillPickerSheet(ui, vm.pickerActions) { skill -> SkillTweak(vm, skill) }
        }
    }
    if (preflightOpen) {
        val result by vm.preflight.collectAsStateWithLifecycle()
        val gating by vm.armGateOpen.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            PreflightSheet(result, gating = gating, onContinue = vm::continuePreflight, onDismiss = { vm.setPreflight(false) })
        }
    }
    val modeSheet by vm.modeSheet.collectAsStateWithLifecycle()
    modeSheet?.let { ui ->
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) { ModeSheet(ui, vm.cockpitSpec.flightState.modeSheet, vm.modeSheetActions) }
    }
    val launch by vm.launch.collectAsStateWithLifecycle()
    launch?.let { ui ->
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            LaunchSheet(ui, onDraft = vm::setLaunchDraft, onLaunch = vm::launchNow, onDismiss = { vm.openLaunch(false) })
        }
    }
    if (confirm != null) {
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) { ConfirmHost(confirm, vm.confirm) }
    }
}
