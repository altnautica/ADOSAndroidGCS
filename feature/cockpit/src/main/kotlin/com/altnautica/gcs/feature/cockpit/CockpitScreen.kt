package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.linkLossLine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import com.altnautica.gcs.feature.cockpit.camera.FrameGrabEffect
import com.altnautica.gcs.feature.cockpit.camera.LocalShownVideo
import com.altnautica.gcs.feature.cockpit.camera.ShownVideo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.overlay.OverlayTopBar
import com.altnautica.gcs.core.designsystem.component.overlay.overlayTopBar
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.extensions.ui.ExtensionMarksLayer
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotHost
import com.altnautica.gcs.core.map.AdosMinimap
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.video.ui.VideoSurface
import com.altnautica.gcs.core.video.ui.VideoSurfaceRole
import com.altnautica.gcs.feature.cockpit.alerts.AlertStack
import com.altnautica.gcs.feature.cockpit.autonomy.MinimapFrame
import com.altnautica.gcs.feature.cockpit.chrome.BackdropSampler
import com.altnautica.gcs.feature.cockpit.chrome.BottomRow
import com.altnautica.gcs.feature.cockpit.chrome.CardPage
import com.altnautica.gcs.feature.cockpit.chrome.AttitudeRadar
import com.altnautica.gcs.feature.cockpit.chrome.Chrome
import com.altnautica.gcs.feature.cockpit.chrome.ChromeBackdrop
import com.altnautica.gcs.feature.cockpit.chrome.FlightGrid
import com.altnautica.gcs.feature.cockpit.chrome.FlightZones
import com.altnautica.gcs.feature.cockpit.chrome.clamp
import com.altnautica.gcs.feature.cockpit.chrome.splitDivider
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.feature.cockpit.tips.LocalTipAnchors
import com.altnautica.gcs.feature.cockpit.tips.TipAnchorRegistry
import com.altnautica.gcs.feature.cockpit.tips.TipLayer
import com.altnautica.gcs.feature.cockpit.tips.rememberTipsHandle
import com.altnautica.gcs.feature.cockpit.tips.tipAnchor
import com.altnautica.gcs.feature.cockpit.tips.tipsRepository
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.chrome.LocalChromeBackdrop
import com.altnautica.gcs.feature.cockpit.chrome.RoundButton
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.feature.cockpit.chrome.StatusPill
import com.altnautica.gcs.feature.cockpit.chrome.SwitchCard
import com.altnautica.gcs.feature.cockpit.chrome.TopBarOverflow
import com.altnautica.gcs.feature.cockpit.chrome.ZoneRect
import com.altnautica.gcs.feature.cockpit.chrome.flightSizes
import com.altnautica.gcs.feature.cockpit.flow.GateRing
import com.altnautica.gcs.feature.cockpit.flow.HandoverBanner
import com.altnautica.gcs.feature.cockpit.flow.LinkLossBanner
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.flow.LowBatteryBanner
import com.altnautica.gcs.feature.cockpit.flow.LaunchProgressBanner
import com.altnautica.gcs.feature.cockpit.flow.SticksWithBanner
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.hud.HudCanvas
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerLevel
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerSpec
import com.altnautica.gcs.feature.cockpit.hud.layers.VisionSidePanel
import com.altnautica.gcs.feature.cockpit.pilot.ThumbPad
import com.altnautica.gcs.feature.cockpit.pilot.ON_SCREEN_PAD
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerChip
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** Stacking order inside the cockpit. */
internal object Z {
    const val VIDEO = 0f
    const val EXT_OVERLAY = 10f
    const val MARKS = 12f
    const val HUD = 15f
    const val ZONES = 20f
    const val PIP = 25f
    const val ALERTS = 45f
    const val SHEETS = 50f
}

private val WIDGET_ZONES = listOf("top-left", "top-right", "bottom-left", "bottom-right")

/**
 * The flight screen (landscape only; the route locks the orientation). The video fills the
 * window; over it, on translucent blurred panels: the top bar (flight-state chip, status
 * pill, battery, link, navigation source, controller, layers, Menu, STOP), the left column
 * (take off or land, return home, pause), the right column (shutter, photo/video, zoom,
 * gimbal pad, camera settings), the bottom-left card (map, attitude, obstacles), the bottom
 * row (D, H, H.S, V.S, AGL) and the skills chip. The centre stays clean: reticle, heading
 * and horizon marks, data layers; the flight instruments only at the Instruments level.
 * No status band, telemetry strip, navigation rail or hint bar is drawn here.
 */
@Composable
internal fun CockpitScreen(
    vm: CockpitViewModel,
    width: WidthClass,
    connecting: Boolean,
    topBar: OverlayTopBar,
    onExit: () -> Unit,
    snackbar: @Composable () -> Unit,
    onToast: (String) -> Unit = {},
) {
    // The flight screen is locked to landscape, so it always takes the width's landscape class.
    val layoutClass = vm.cockpitSpec.layout.classes[layoutClassId(width, landscape = true)]
    val chrome = remember(vm) { Chrome.from(vm.cockpitSpec.tokens.chrome) }
    val backdrop = remember { ChromeBackdrop() }
    val status = remember { StatusListState() }
    val anchors = remember { TipAnchorRegistry() }
    val tips = rememberTipsHandle()
    val tipsEngine = remember(vm) { TipsEngine(vm.cockpitSpec.tips) }
    val padButtons by vm.padButtons.collectAsStateWithLifecycle()
    LaunchedEffect(padButtons) { if (padButtons != 0) tips.dismissFromControllerButton() }
    val shownVideo = remember { ShownVideo() }
    CompositionLocalProvider(
        LocalCockpitStrings provides vm.specStrings,
        LocalLayoutClass provides layoutClass,
        LocalChrome provides chrome,
        LocalChromeBackdrop provides backdrop,
        LocalTipAnchors provides anchors,
        LocalShownVideo provides shownVideo,
    ) {
        BackdropSampler(backdrop)
        // Inside the providers: it reads the spec words for its toasts.
        FrameGrabEffect(vm, shownVideo, onToast)
        Box(Modifier.fillMaxSize().background(Color.Black).focusRings(layoutClass.focusRingsAlways, AdosTheme.colors.focusRing)) {
            FlightScreen(vm, width, status, topBar, onExit, snackbar)
            if (connecting) {
                Box(Modifier.align(Alignment.Center).zIndex(Z.ALERTS)) {
                    LoadingState(stringResource(R.string.cockpit_connecting))
                }
            }
            Overlays(vm, width, status)
            // Last, so a tip draws above the chrome; the layer itself takes no touch.
            TipLayer(tipFacts(vm, status), tipsRepository(LocalContext.current), tipsEngine, Modifier.zIndex(Z.SHEETS), handle = tips)
        }
    }
}

/** Where the status pill's alert list hangs (the pill's left edge) and whether the Menu is open. */
internal class StatusListState {
    var pillX by mutableFloatStateOf(0f)
    var menuOpen by mutableStateOf(false)
}

@Composable
private fun FlightScreen(
    vm: CockpitViewModel,
    width: WidthClass,
    status: StatusListState,
    topBar: OverlayTopBar,
    onExit: () -> Unit,
    snackbar: @Composable () -> Unit,
) {
    val view by vm.view.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gimbal by vm.gimbal.available.collectAsStateWithLifecycle()
    val statusItems by vm.statusItems.collectAsStateWithLifecycle()
    var acknowledged by remember { mutableStateOf(emptySet<String>()) }
    val overflow = remember { TopBarOverflow() }
    val sizes = remember(width) { flightSizes(width) }
    val onScreen by vm.onScreenSticksEnabled.collectAsStateWithLifecycle()
    val sticks = sticksShown(onScreen, width, vm.cockpitSpec.layout.onScreenSticks.classes)
    Box(Modifier.fillMaxSize()) {
        FlightGrid(
            width = width,
            gimbal = gimbal,
            background = { zones ->
                CompositionLocalProvider(LocalLayerChipInset provides layerChipInset(zones)) {
                    MainView(vm, view, settings.splitRatio, zones, if (sticks) ON_SCREEN_PAD else 0.dp, Modifier.fillMaxSize())
                }
            },
            topBar = {
                // A tip never covers the top bar (`never_cover_zones`); overlays start below its measured bottom.
                Box(Modifier.fillMaxSize().tipAnchor("top_bar").overlayTopBar(topBar)) {
                    FlightTopBar(
                        vm = vm,
                        width = width,
                        overflow = overflow,
                        onExit = onExit,
                        onMenuOpen = { status.menuOpen = it },
                        pill = {
                            StatusPill(
                                StatusModel.pill(statusItems, acknowledged),
                                onOpen = {
                                    // Opening the list acknowledges what it shows.
                                    acknowledged = statusItems.mapTo(HashSet()) { it.key }
                                    vm.setStatusList(true)
                                },
                                modifier = Modifier.tipAnchor("status_pill").onGloballyPositioned { status.pillX = it.boundsInRoot().left },
                            )
                        },
                    )
                }
            },
            leftColumn = { Box(Modifier.fillMaxSize().tipAnchor("left_column")) { LeftColumn(vm, sizes.leftButton, sizes.leftGap) } },
            rightColumn = { z -> RightColumn(vm, sizes, z.gimbalInColumn, z.zoomInColumn, onCameraSettings = { vm.setCameraSettings(true) }) },
            gimbalPad = { Pad(vm, sizes.gimbalPad) },
            card = { Box(Modifier.fillMaxSize().tipAnchor("bottom_left_card")) { if (view == CockpitView.Map) VideoCard(vm) else Card(vm) } },
            bottomRow = {
                val values by vm.flightValues.collectAsStateWithLifecycle()
                BottomRow(values)
            },
            skills = { PickerChip(vm, Modifier.align(Alignment.BottomEnd).tipAnchor("skill_picker_chip")) },
            centre = { zones -> Centre(vm, width, zones, snackbar) },
        )
    }
}

/** The HUD's inset: the centre zone's sides and top, so no column or the top bar ever sits on a tape or the heading scale. */
/** The video's corner left free by the chrome: the layer chips stack up from the centre zone's bottom-left. */
private fun layerChipInset(zones: FlightZones): PaddingValues {
    val windowHeight = zones.card.bottom + zones.topBar.y
    return PaddingValues(start = zones.centre.x, top = zones.centre.y, bottom = windowHeight - zones.centre.bottom)
}

/**
 * The HUD's inset: the centre zone's sides and top, so no column or the top bar ever sits on
 * a tape or the heading scale. With on-screen sticks shown ([stickPad] wide) the tapes move
 * inward past the pads, which sit in the centre's bottom corners.
 */
internal fun centreInset(zones: FlightZones, stickPad: Dp = 0.dp): PaddingValues {
    val windowWidth = zones.topBar.right + zones.topBar.x
    return PaddingValues(start = zones.centre.x + stickPad, top = zones.centre.y, end = windowWidth - zones.centre.right + stickPad)
}

/** Whether the on-screen sticks show at this class (`on_screen_sticks.classes`) once the setting is on. */
internal fun sticksShown(setting: Boolean, width: WidthClass, classes: List<String>): Boolean =
    setting && layoutClassId(width, landscape = true) in classes

/** Take off (opens the Launch sheet) or land, return home, pause: the built-ins' own gates and confirms apply. */
@Composable
private fun LeftColumn(vm: CockpitViewModel, size: Dp, gap: Dp) {
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val airborne = pilot?.let { it.armed && !it.landed } ?: (band.armed == true)
    val labels = vm.cockpitSpec.builtins.builtins.associate { it.id to it.label }
    // Icon and a short word under it (`builtin.short.<id>`), so no action is icon-only.
    @Composable fun short(id: String) = specText("builtin.short.$id")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
        if (airborne) {
            RoundButton(Icons.Outlined.FlightLand, labels[BuiltinIds.LAND].orEmpty(), size, "grid-land", { vm.activate(BuiltinIds.LAND) }, shortLabel = short(BuiltinIds.LAND))
        } else {
            RoundButton(Icons.Outlined.FlightTakeoff, labels[BuiltinIds.TAKEOFF].orEmpty(), size, "grid-takeoff", { vm.openLaunch(true) }, shortLabel = short(BuiltinIds.TAKEOFF))
        }
        RoundButton(Icons.Outlined.Home, labels[BuiltinIds.RTH].orEmpty(), size, "grid-rth", { vm.activate(BuiltinIds.RTH) }, shortLabel = short(BuiltinIds.RTH))
        RoundButton(Icons.Outlined.PauseCircle, labels[BuiltinIds.PAUSE].orEmpty(), size, "grid-pause", { vm.activate(BuiltinIds.PAUSE) }, shortLabel = short(BuiltinIds.PAUSE))
    }
}

/** The bottom-left card in the Map view: the video, without layers or targets (a picture-in-picture never draws them). */
@Composable
private fun VideoCard(vm: CockpitViewModel) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(AdosRadius.Lg)).testTag("card-video")) {
        VideoStage(vm, Modifier.fillMaxSize(), showHud = false, showLayers = false)
    }
}

/** The bottom-left card: map, attitude radar or obstacle radar; a swipe or its dots switch it; a tap on the map or L3 swaps video and map. */
@Composable
private fun Card(vm: CockpitViewModel) {
    val page by vm.cardPage.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val label = stringResource(
        when (page) {
            CardPage.Map -> R.string.grid_card_page_map
            CardPage.Attitude -> R.string.grid_card_attitude
            CardPage.Obstacles -> R.string.grid_card_obstacles
        },
    )
    SwitchCard(page, label, vm::stepCard) { p ->
        when (p) {
            CardPage.Map -> Minimap(vm, Modifier.fillMaxSize(), onClick = vm::toggleMinimapSwap)
            CardPage.Attitude -> AttitudeRadar(telemetry?.attitude)
            CardPage.Obstacles -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AutonomyRadar(vm, Modifier, minOf(maxWidth, maxHeight) - 8.dp)
            }
        }
    }
}

/**
 * Centre overlays, all inside the centre zone: banners and the critical alert at its top,
 * rings at the screen centre, and toasts at its bottom, so they sit above the bottom row
 * and clear of the card, both columns and the skills chip.
 */
@Composable
private fun Centre(vm: CockpitViewModel, width: WidthClass, zones: FlightZones, snackbar: @Composable () -> Unit) {
    val onScreen by vm.onScreenSticksEnabled.collectAsStateWithLifecycle()
    val rect = zones.centre
    Box(Modifier.fillMaxSize()) {
        CentreRings(vm, Modifier.align(Alignment.Center).zIndex(Z.ALERTS))
        InZone(rect, Z.ALERTS + 1) {
            Box(Modifier.align(Alignment.BottomCenter).testTag("cockpit-toasts")) { snackbar() }
        }
        InZone(rect, Z.ALERTS) {
            // The alert stack: tips never cover it (`never_cover_zones`) and wait while it shows.
            Column(Modifier.align(Alignment.TopCenter).padding(top = 4.dp).tipAnchor("alert_stack"), horizontalAlignment = Alignment.CenterHorizontally) {
                val alerts by vm.alerts.collectAsStateWithLifecycle()
                // Warnings live in the status pill; only a critical alert takes the centre.
                AlertStack(alerts.filter { it.level == AlertLevel.Critical }, maxVisible = 1)
                FlowBanners(vm, Modifier.padding(top = 4.dp).widthIn(max = 520.dp))
            }
        }
        InZone(rect, Z.ZONES) {
            WidgetZones(vm, Modifier.fillMaxSize())
            // At Instruments the heading scale and its readout take the centre's top; the lock chip sits below them.
            val flags by vm.autonomy.layers.flags.collectAsStateWithLifecycle()
            val instruments = flags.level == LayerLevel.Instruments && flags.isOn(LayerSpec.INSTRUMENTS)
            FollowChrome(vm, width, topInset = if (instruments) HEADING_SCALE_CLEARANCE else 0.dp, modifier = Modifier.fillMaxSize())
            if (sticksShown(onScreen, width, vm.cockpitSpec.layout.onScreenSticks.classes)) {
                OnScreenSticksRow(vm, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

/** Height the HUD's heading scale and readout take at the top of the centre at the Instruments level. */
private val HEADING_SCALE_CLEARANCE = 76.dp

/** Extra top padding that drops the video placeholder below the reticle and horizon marks at the screen centre. */
internal val VIDEO_PLACEHOLDER_DROP = 96.dp

@Composable
private fun InZone(rect: ZoneRect, z: Float, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.absoluteOffset(rect.x, rect.y).size(rect.width, rect.height).zIndex(z), content = content)
}

/**
 * The main view: video; the map (its video goes into the bottom-left card, nothing sits at
 * the screen centre); the two side by side; or video with the vision panel, which keeps to
 * the right of the centre zone, clear of the top bar, both columns, the gimbal pad, the
 * bottom row and the sticks.
 */
@Composable
private fun MainView(vm: CockpitViewModel, view: CockpitView, savedRatio: Float, zones: FlightZones, stickPad: Dp, modifier: Modifier) {
    val hudInset = centreInset(zones, stickPad)
    val startOnly = PaddingValues(start = hudInset.calculateStartPadding(LayoutDirection.Ltr), top = hudInset.calculateTopPadding())
    when (view) {
        CockpitView.Pilot -> VideoStage(vm, modifier, hudInset = hudInset, sampleBackdrop = true)
        CockpitView.Map -> Minimap(vm, modifier)
        CockpitView.Split -> SplitView(vm, savedRatio, startOnly, modifier)
        CockpitView.Vision -> Box(modifier) {
            val panel = visionPanelRect(zones, stickPad)
            val windowWidth = zones.topBar.right + zones.topBar.x
            val inset = PaddingValues(start = hudInset.calculateStartPadding(LayoutDirection.Ltr), top = hudInset.calculateTopPadding(), end = windowWidth - panel.x + zones.centre.y - zones.topBar.bottom)
            VideoStage(vm, Modifier.fillMaxSize(), hudInset = inset, sampleBackdrop = true)
            val video by vm.video.collectAsStateWithLifecycle()
            VisionSidePanel(vm, vm.autonomy.mainCamera(video), Modifier.absoluteOffset(panel.x, panel.y).size(panel.width, panel.height).testTag("vision-panel"))
        }
    }
}

/** The vision panel's rectangle: the right part of the centre zone, above the sticks when they show. */
internal fun visionPanelRect(zones: FlightZones, stickPad: Dp): ZoneRect {
    val c = zones.centre
    val w = minOf(VISION_PANEL_WIDTH, c.width * 0.45f)
    return ZoneRect(c.right - w, c.y, w, (c.height - stickPad).coerceAtLeast(0.dp))
}

private val VISION_PANEL_WIDTH = 320.dp

@Composable
private fun SplitView(vm: CockpitViewModel, savedRatio: Float, hudInset: PaddingValues, modifier: Modifier) {
    val divider = remember(vm) { splitDivider(vm.cockpitSpec.views) }
    var ratio by remember(savedRatio, divider) { mutableFloatStateOf(divider.clamp(savedRatio)) }
    val splitLabel = stringResource(R.string.cockpit_split_divider)
    BoxWithConstraints(modifier) {
        val total = constraints.maxWidth.toFloat()
        Row(Modifier.fillMaxSize()) {
            VideoStage(vm, Modifier.weight(ratio).fillMaxHeight(), hudInset = hudInset, sampleBackdrop = true)
            Box(
                Modifier
                    .width(12.dp)
                    .fillMaxHeight()
                    .background(AdosTheme.colors.bgSurface)
                    .semantics { contentDescription = splitLabel }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            ratio = divider.clamp(ratio + delta / total)
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        flow.launch?.let { LaunchProgressBanner(it, onAbort = { vm.activate(BuiltinIds.PAUSE) }) }
        flow.banner?.let { HandoverBanner(it) }
        flow.sticksWith?.let { SticksWithBanner(it, vm::takeSticks) }
        flow.linkLossInS?.let { s ->
            // The configured action in words ("Return home", "Land", "Hold"); a dash when unknown.
            SpecText(vm.cockpitSpec.setupStrings).linkLossLine(s, pilot?.linkLossAction ?: pilot?.banner?.args?.get("action"))?.let { LinkLossBanner(it) }
        }
        flow.rthCountdownS?.let { LowBatteryBanner(it, vm::cancelRthCountdown) }
    }
}

/** The spec's `video.state.*` word for a video that is not live. */
internal fun videoStateKey(state: VideoState): String = when (state) {
    VideoState.Connecting -> "video.state.connecting"
    is VideoState.Frozen -> "video.state.frozen"
    is VideoState.Failed -> "video.state.failed"
    is VideoState.Live, VideoState.Idle, VideoState.NoSource -> "video.state.no_source"
}

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

/**
 * Video, extension overlay, marks, data layers and the centre: the reticle and small
 * heading and horizon marks at every level, the flight instruments only at Instruments.
 * With [sampleBackdrop] the panels blur this video behind themselves.
 */
@Composable
private fun VideoStage(
    vm: CockpitViewModel,
    modifier: Modifier,
    showHud: Boolean = true,
    showLayers: Boolean = true,
    hudInset: PaddingValues = PaddingValues(),
    sampleBackdrop: Boolean = false,
) {
    val video by vm.video.collectAsStateWithLifecycle()
    val videoState by vm.videoState.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val swapped by vm.minimapSwapped.collectAsStateWithLifecycle()
    val flags by vm.autonomy.layers.flags.collectAsStateWithLifecycle()
    val zoom by vm.digitalZoom.collectAsStateWithLifecycle()
    val backdrop = LocalChromeBackdrop.current
    val shown = LocalShownVideo.current
    Box(modifier.background(AdosTheme.colors.mediaLetterbox)) {
        if (swapped && showHud) {
            Minimap(vm, Modifier.fillMaxSize())
            return@Box
        }
        val sampler = backdrop?.takeIf { sampleBackdrop }
        // Digital zoom: the video is laid out [zoom] times larger around its centre and clipped to its slot.
        BoxWithConstraints(
            Modifier.fillMaxSize().clipToBounds().zIndex(Z.VIDEO).onGloballyPositioned { shown?.bounds = it.boundsInRoot() },
            contentAlignment = Alignment.Center,
        ) {
            VideoSurface(
                video.main,
                Modifier
                    .requiredSize(maxWidth * zoom, maxHeight * zoom)
                    .onGloballyPositioned { coords ->
                        sampler?.videoBounds = coords.boundsInRoot()
                    },
                onSurfaceView = { view ->
                    sampler?.surfaceView = view
                    shown?.surfaceView = view
                },
            )
        }
        if (videoState !is VideoState.Live) {
            // The shared video-state words; never the raw transport error, which can carry a host address.
            val text = specText(videoStateKey(videoState))
            // Below the reticle and horizon marks, so no centre mark strikes through the words.
            Text(
                text,
                color = AdosTheme.colors.mediaOn,
                modifier = Modifier.align(Alignment.Center).padding(top = VIDEO_PLACEHOLDER_DROP).zIndex(Z.VIDEO + 1).testTag("video-placeholder"),
            )
        }
        extNode?.let { node ->
            ExtensionSlotHost(node, "video.overlay", Modifier.fillMaxSize().zIndex(Z.EXT_OVERLAY))
            ExtensionMarksLayer(node, Modifier.fillMaxSize().zIndex(Z.MARKS))
        }
        if (showLayers) VideoLayers(vm, vm.autonomy.mainCamera(video), modifier = Modifier.fillMaxSize().zIndex(Z.MARKS))
        if (showHud && flags.isOn(LayerSpec.CENTRE_MARKS)) {
            HudCanvas(
                telemetry = telemetry,
                altitudeReference = settings.altitudeReference,
                density = settings.density,
                instruments = flags.level == LayerLevel.Instruments && flags.isOn(LayerSpec.INSTRUMENTS),
                modifier = Modifier.fillMaxSize().padding(hudInset).zIndex(Z.HUD).testTag("hud"),
            )
        }
        Pip(vm, Modifier.align(Alignment.Center).zIndex(Z.PIP))
    }
}

@Composable
private fun Minimap(vm: CockpitViewModel, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val state by vm.minimap.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val route by vm.missionRoute.collectAsStateWithLifecycle()
    val frame by vm.autonomy.minimapFrame.collectAsStateWithLifecycle()
    if (frame == MinimapFrame.Local) {
        // Pan and pinch are drag gestures, so a plain tap still swaps video and map.
        AutonomyLocalMinimap(vm, onClick?.let { modifier.clickable(onClick = it) } ?: modifier)
        return
    }
    Box(modifier) {
        // A long-press anywhere on a cockpit map picks a point: the launch go-to on the ground, fly-here in the air.
        AdosMinimap(
            state.vehicle, state.home, state.trail, Modifier.fillMaxSize(),
            overlays = route, zoom = MINIMAP_ZOOM, onClick = onClick, onLongPress = vm::pickMapPoint,
        )
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
    Box(modifier.padding(8.dp)) {
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
