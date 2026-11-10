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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import com.altnautica.gcs.core.skills.spec.ZoneLinkSpec
import com.altnautica.gcs.feature.cockpit.camera.CameraSettingsSheet
import com.altnautica.gcs.feature.cockpit.chrome.linkAgeSeconds
import com.altnautica.gcs.feature.cockpit.chrome.linkIconHealth
import com.altnautica.gcs.feature.cockpit.camera.CameraSheetUi
import com.altnautica.gcs.feature.cockpit.camera.FrameGrabEffect
import com.altnautica.gcs.feature.cockpit.camera.LocalShownVideo
import com.altnautica.gcs.feature.cockpit.camera.ShownVideo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.component.overlay.LocalOverlayTopInset
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.extensions.ui.ExtensionMarksLayer
import com.altnautica.gcs.core.extensions.ui.ExtensionParametersPanel
import com.altnautica.gcs.core.extensions.ui.ExtensionSlotHost
import com.altnautica.gcs.core.map.AdosMinimap
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.video.ui.VideoSurface
import com.altnautica.gcs.core.video.ui.VideoSurfaceRole
import com.altnautica.gcs.feature.cockpit.alerts.AlertStack
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import com.altnautica.gcs.feature.cockpit.autonomy.MinimapFrame
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChip
import com.altnautica.gcs.feature.cockpit.chrome.BackdropSampler
import com.altnautica.gcs.feature.cockpit.chrome.BatteryGauge
import com.altnautica.gcs.feature.cockpit.chrome.batteryUi
import com.altnautica.gcs.feature.cockpit.chrome.BottomRow
import com.altnautica.gcs.feature.cockpit.chrome.CardPage
import com.altnautica.gcs.feature.cockpit.chrome.AttitudeRadar
import com.altnautica.gcs.feature.cockpit.chrome.Chrome
import com.altnautica.gcs.feature.cockpit.chrome.ChromeBackdrop
import com.altnautica.gcs.feature.cockpit.chrome.ChromeText
import com.altnautica.gcs.feature.cockpit.chrome.ControllerIndicator
import com.altnautica.gcs.feature.cockpit.chrome.FlightGrid
import com.altnautica.gcs.feature.cockpit.chrome.FlightZones
import com.altnautica.gcs.feature.cockpit.chrome.GimbalPad
import com.altnautica.gcs.feature.cockpit.chrome.clamp
import com.altnautica.gcs.feature.cockpit.chrome.splitDivider
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.feature.cockpit.tips.LocalTipAnchors
import com.altnautica.gcs.feature.cockpit.tips.TipAnchorRegistry
import com.altnautica.gcs.feature.cockpit.tips.TipFacts
import com.altnautica.gcs.feature.cockpit.tips.TipLayer
import com.altnautica.gcs.feature.cockpit.tips.rememberTipsHandle
import com.altnautica.gcs.feature.cockpit.tips.tipAnchor
import com.altnautica.gcs.feature.cockpit.tips.tipsRepository
import com.altnautica.gcs.feature.cockpit.chrome.LinkIcon
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.chrome.LocalChromeBackdrop
import com.altnautica.gcs.feature.cockpit.chrome.MenuButton
import com.altnautica.gcs.feature.cockpit.chrome.PhotoVideoSwitch
import com.altnautica.gcs.feature.cockpit.chrome.RoundButton
import com.altnautica.gcs.feature.cockpit.chrome.Shutter
import com.altnautica.gcs.feature.cockpit.chrome.StatusList
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.feature.cockpit.chrome.StatusPill
import com.altnautica.gcs.feature.cockpit.chrome.StatusSeverity
import com.altnautica.gcs.feature.cockpit.chrome.StopButton
import com.altnautica.gcs.feature.cockpit.chrome.SwitchCard
import com.altnautica.gcs.feature.cockpit.chrome.TopBar
import com.altnautica.gcs.feature.cockpit.chrome.TopBarItem
import com.altnautica.gcs.feature.cockpit.chrome.TopBarOverflow
import com.altnautica.gcs.feature.cockpit.chrome.ZoneRect
import com.altnautica.gcs.feature.cockpit.chrome.ZoomPill
import com.altnautica.gcs.feature.cockpit.chrome.flightSizes
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmHost
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
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerLevelButton
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerSpec
import com.altnautica.gcs.feature.cockpit.hud.layers.VisionSidePanel
import com.altnautica.gcs.feature.cockpit.launch.LaunchSheet
import com.altnautica.gcs.feature.cockpit.modes.ModeSheet
import com.altnautica.gcs.feature.cockpit.panels.QuickSettingsPanel
import com.altnautica.gcs.feature.cockpit.pilot.ThumbPad
import com.altnautica.gcs.feature.cockpit.pilot.ON_SCREEN_PAD
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
    onExit: () -> Unit,
    snackbar: @Composable () -> Unit,
    onToast: (String) -> Unit = {},
) {
    // The flight screen is locked to landscape, so it always takes the width's landscape class.
    val layoutClass = vm.cockpitSpec.layout.classes[layoutClassId(width, landscape = true)]
    val chrome = remember(vm) { Chrome.from(vm.cockpitSpec.tokens.chrome) }
    val backdrop = remember { ChromeBackdrop() }
    val sizes = remember(width) { flightSizes(width) }
    val status = remember { StatusListState() }
    val anchors = remember { TipAnchorRegistry() }
    val tips = rememberTipsHandle()
    val tipsEngine = remember(vm) { TipsEngine(vm.cockpitSpec.tips) }
    val padButtons by vm.padButtons.collectAsStateWithLifecycle()
    LaunchedEffect(padButtons) { if (padButtons != 0) tips.dismissFromControllerButton() }
    val shownVideo = remember { ShownVideo() }
    FrameGrabEffect(vm, shownVideo, onToast)
    CompositionLocalProvider(
        LocalCockpitStrings provides vm.specStrings,
        LocalLayoutClass provides layoutClass,
        LocalChrome provides chrome,
        LocalChromeBackdrop provides backdrop,
        // Sheets, dialogs and menus start below the top bar, so STOP takes the first press with any of them open.
        LocalOverlayTopInset provides sizes.edge + sizes.topBar,
        LocalTipAnchors provides anchors,
        LocalShownVideo provides shownVideo,
    ) {
        BackdropSampler(backdrop)
        Box(Modifier.fillMaxSize().background(Color.Black).focusRings(layoutClass.focusRingsAlways, AdosTheme.colors.focusRing)) {
            FlightScreen(vm, width, status, onExit, snackbar)
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

/** The status pill's alert list (open or not, and the pill's left edge it hangs under) and whether the Menu is open. */
private class StatusListState {
    var open by mutableStateOf(false)
    var pillX by mutableFloatStateOf(0f)
    var menuOpen by mutableStateOf(false)
}

@Composable
private fun FlightScreen(vm: CockpitViewModel, width: WidthClass, status: StatusListState, onExit: () -> Unit, snackbar: @Composable () -> Unit) {
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
                                status.open = true
                            },
                            modifier = Modifier.tipAnchor("status_pill").onGloballyPositioned { status.pillX = it.boundsInRoot().left },
                        )
                    },
                )
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

/** The Menu's order when the spec names none (`layout.json` `menu.order`). */
private val DEFAULT_MENU_ORDER = listOf("overflow", "view", "cameras", "layers", "preflight", "modes", "controls", "extension_panels", "setup", "settings", "exit")

@Composable
private fun FlightTopBar(
    vm: CockpitViewModel,
    width: WidthClass,
    overflow: TopBarOverflow,
    onExit: () -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    pill: @Composable () -> Unit,
) {
    val chip by vm.chip.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val reserve by vm.batteryReserve.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val menu = rememberAdosMenuState()
    LaunchedEffect(menu.expanded) { onMenuOpen(menu.expanded) }
    val topBarZone = remember(vm) { vm.cockpitSpec.layout.zones.firstOrNull { it.id == "top_bar" } }
    val linkSpec = topBarZone?.link
    val linkText = linkText(band, linkSpec)
    val controllerText = controllerText(band.stick)
    TopBar(
        overflow = overflow,
        overflowOrder = remember(vm) { TopBarItem.order(topBarZone?.overflow?.toMenuOrder.orEmpty()) },
        chipMaxFrac = topBarZone?.chip?.maxFrac ?: com.altnautica.gcs.feature.cockpit.chrome.DEFAULT_CHIP_MAX_FRAC,
        chip = { Box(Modifier.tipAnchor("flight_state_chip")) { FlightStateChip(chip, onOpen = vm::openModeSheet, onButton = vm::onChipButton) } },
        pill = pill,
        battery = {
            BatteryGauge(
                batteryUi(
                    pct = band.batteryPct,
                    reserve = reserve,
                    flightControllerTimeS = band.timeToReserveS,
                    severity = when (band.batteryLevel) {
                        BatteryBand.Critical -> StatusSeverity.Critical
                        BatteryBand.Low -> StatusSeverity.Warning
                        else -> StatusSeverity.Ok
                    },
                ),
                reserveTextKey = topBarZone?.battery?.timeToReserve?.text ?: "battery.to_reserve",
                reserveShortKey = topBarZone?.battery?.timeToReserve?.short ?: "battery.to_reserve_short",
            )
        },
        linkIcon = { LinkIcon(linkIconHealth(band.link, band.linkAgeDeciS, linkSpec), linkText) },
        linkDetail = { if (band.link != LinkHealth.None) ChromeText(linkText, dim = true) },
        navSource = { NavChipSlot(vm, width) },
        controller = {
            when (val s = band.stick) {
                is StickChip.Available -> ControllerIndicator(controllerText.orEmpty(), s.on, vm::toggleStickControl)
                is StickChip.Pilot -> ControllerIndicator(controllerText.orEmpty(), s.streaming, null)
                is StickChip.ModeBlocked, StickChip.LinkBlocked -> ControllerIndicator(controllerText.orEmpty(), false, null)
                StickChip.NoGamepad -> Unit
            }
        },
        layers = { LayerLevelButton(vm.autonomy.layers, Modifier.tipAnchor("layer_button")) },
        menu = { MenuButton(onClick = { menu.expanded = true }, modifier = Modifier.adosMenuAnchor(menu)) },
        stop = { StopButton(onClick = { vm.activate(BuiltinIds.KILL) }, modifier = Modifier.tipAnchor("stop")) },
    )
    val moved = overflow.moved
    val nav by vm.autonomy.navChip.collectAsStateWithLifecycle()
    val layerFlags by vm.autonomy.layers.flags.collectAsStateWithLifecycle()
    val navText = com.altnautica.gcs.feature.cockpit.autonomy.TargetText(androidx.compose.ui.platform.LocalResources.current)
    val menuOrder = vm.cockpitSpec.layout.menu?.order.orEmpty().ifEmpty { DEFAULT_MENU_ORDER }
    val strings = LocalCockpitStrings.current
    val items = buildList {
        band.nodeName?.let { name ->
            add(AdosMenuItem(name + (band.reach?.let { " · " + reachText(it) } ?: ""), enabled = false) {})
        }
        for (entry in menuOrder) when (entry) {
            // Only the items the top bar actually had no room for.
            "overflow" -> {
                if (TopBarItem.Controller in moved && controllerText != null) {
                    add(AdosMenuItem(stringResource(R.string.cockpit_menu_controller, controllerText), enabled = band.stick is StickChip.Available) { vm.toggleStickControl() })
                }
                if (TopBarItem.NavSource in moved) nav?.let { add(AdosMenuItem(navText.navLabel(it), Icons.Outlined.SatelliteAlt, enabled = false) {}) }
                if ((TopBarItem.LinkDetail in moved || TopBarItem.LinkIcon in moved) && band.link != LinkHealth.None) {
                    add(AdosMenuItem(stringResource(R.string.cockpit_menu_link, listOfNotNull(linkText, radioText(band)).joinToString(" · ")), enabled = false) {})
                }
                if (TopBarItem.Layers in moved) {
                    add(AdosMenuItem(com.altnautica.gcs.feature.cockpit.hud.layers.layerButtonText(layerFlags.level)) { vm.autonomy.layers.cycleLevel(1) })
                }
            }
            "view" -> add(AdosMenuItem(strings.format("cockpit.menu.view"), Icons.Outlined.GridView) { vm.nextView() })
            "cameras" -> {
                val live = video.streams.filter { it.live }
                if (live.size > 1) for (s in live) {
                    add(AdosMenuItem(stringResource(R.string.cockpit_menu_camera, s.role), Icons.Outlined.Videocam, enabled = s.id != video.selectedStreamId) { vm.selectStream(s.id) })
                }
            }
            "preflight" -> if (band.preflightTotal > 0) {
                add(AdosMenuItem(strings.format("cockpit.menu.preflight") + " · ${band.preflightPassed}/${band.preflightTotal}", Icons.Outlined.Checklist) { vm.setPreflight(true) })
            }
            "modes" -> add(AdosMenuItem(strings.format("cockpit.menu.modes"), Icons.Outlined.FlightTakeoff) { vm.openModeSheet() })
            "settings" -> add(AdosMenuItem(strings.format("cockpit.menu.settings"), Icons.Outlined.Tune) { vm.setQuickSettings(true) })
            "exit" -> add(AdosMenuItem(stringResource(R.string.cockpit_menu_home), Icons.AutoMirrored.Outlined.ArrowBack) { onExit() })
            // Layers live on the top bar's layer button; controls and extension panels sit in quick settings.
            else -> Unit
        }
    }
    AdosMenu(menu, items)
}

/** The link cell: the newest vehicle frame's age (`cockpit.link.age`), or the spec's no-link word. */
@Composable
private fun linkText(band: BandState, spec: ZoneLinkSpec?): String {
    val age = band.linkAgeDeciS
    return when {
        band.link == LinkHealth.None -> specText(spec?.never ?: "cockpit.link.never")
        age != null -> specText(spec?.text ?: "cockpit.link.age", "s" to linkAgeSeconds(age))
        else -> "—"
    }
}

/** Radio figures for the Menu's link row, beside the age: RSSI and link quality when the radio reports them. */
private fun radioText(band: BandState): String? = when {
    band.rssiDbm != null -> "${band.rssiDbm} dBm" + (band.lqPct?.let { " · $it%" } ?: "")
    band.lqPct != null -> "LQ ${band.lqPct}%"
    else -> null
}

@Composable
private fun controllerText(stick: StickChip): String? {
    val pct = com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi.current.batteryPct
    return when (stick) {
        StickChip.NoGamepad -> null
        StickChip.LinkBlocked -> stringResource(R.string.cockpit_stick_blocked)
        is StickChip.ModeBlocked -> stringResource(R.string.cockpit_stick_mode_blocked, stick.mode.uppercase())
        is StickChip.Available, is StickChip.Pilot -> pct?.let { "$it%" } ?: "—"
    }
}

@Composable
private fun reachText(badge: ReachBadge): String = stringResource(
    when (badge) {
        ReachBadge.Direct -> R.string.cockpit_reach_direct
        ReachBadge.Lan -> R.string.cockpit_reach_lan
        ReachBadge.ViaGround -> R.string.cockpit_reach_via_ground
        ReachBadge.Cloud -> R.string.cockpit_reach_cloud
        ReachBadge.PhoneRadio -> R.string.cockpit_reach_phone_radio
        ReachBadge.Blocked -> R.string.cockpit_reach_blocked
    },
)

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

/** Shutter, photo/video switch, zoom (with the digital zoom in use under it), the gimbal pad when it fits here, camera settings. */
@Composable
private fun RightColumn(
    vm: CockpitViewModel,
    sizes: com.altnautica.gcs.feature.cockpit.chrome.FlightSizes,
    gimbalInColumn: Boolean,
    zoomInColumn: Boolean,
    onCameraSettings: () -> Unit,
) {
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    var videoMode by rememberSaveable { mutableStateOf(false) }
    val video = videoMode && canRecord
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(sizes.rightGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Shutter(
            video = video,
            recording = recording,
            label = stringResource(if (video) R.string.grid_record else R.string.grid_shutter),
            size = sizes.shutter,
            onClick = { if (video) vm.toggleRecording() else vm.capturePhoto() },
        )
        PhotoVideoSwitch(
            video = video,
            videoEnabled = canRecord,
            photoLabel = stringResource(R.string.grid_mode_photo),
            videoLabel = stringResource(R.string.grid_mode_video),
            onVideo = { videoMode = it },
            width = sizes.shutter,
            height = sizes.modeSwitch,
        )
        if (zoomInColumn) {
            ZoomPill(
                inLabel = stringResource(R.string.grid_zoom_in),
                outLabel = stringResource(R.string.grid_zoom_out),
                onIn = vm::zoomIn,
                onOut = vm::zoomOut,
                onLongPress = null,
                width = sizes.rightButton,
                height = sizes.zoom,
            )
            val zoom by vm.digitalZoom.collectAsStateWithLifecycle()
            if (zoom > 1) ChromeText(specText("camera.digital_zoom", "n" to zoom.toString()), modifier = Modifier.testTag("digital-zoom"))
        }
        if (gimbalInColumn) Pad(vm, sizes.gimbalPad)
        RoundButton(Icons.Outlined.Tune, specText("cockpit.camera.settings"), sizes.rightButton, "grid-camera-settings", onCameraSettings)
    }
}

@Composable
private fun Pad(vm: CockpitViewModel, size: Dp) {
    GimbalPad(
        label = specText("cockpit.gimbal_pad"),
        hint = specText("cockpit.gimbal_pad.hint"),
        size = size,
        onRate = vm.gimbal::deflect,
        onRecenter = vm::recenterGimbal,
        modifier = Modifier.tipAnchor("gimbal_pad"),
    )
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
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().zIndex(Z.VIDEO), contentAlignment = Alignment.Center) {
            VideoSurface(
                video.main,
                Modifier
                    .requiredSize(maxWidth * zoom, maxHeight * zoom)
                    .onGloballyPositioned { coords ->
                        sampler?.videoBounds = coords.boundsInRoot()
                        shown?.bounds = coords.boundsInRoot()
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

/**
 * Quick settings, the alert list and the sheets, drawn in the cockpit window above the
 * flight surface (z 50). One at a time ([SheetStack]): opening one closes the others, and
 * only the topmost draws. Every sheet starts below the top bar ([LocalOverlayTopInset]).
 */
@Composable
private fun Overlays(vm: CockpitViewModel, width: WidthClass, status: StatusListState) {
    val confirm by vm.confirm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val maxBrightness by vm.maxBrightness.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val extNode by vm.extensionNodeId.collectAsStateWithLifecycle()
    val stickAllModes by vm.stickAllModes.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val modeSheet by vm.modeSheet.collectAsStateWithLifecycle()
    val launch by vm.launch.collectAsStateWithLifecycle()
    val tourStep by vm.tourStep.collectAsStateWithLifecycle()
    val statusItems by vm.statusItems.collectAsStateWithLifecycle()
    val wide = width == WidthClass.Expanded || width == WidthClass.Tv
    val sizes = remember(width) { flightSizes(width) }

    val open = openSheets(vm, status)
    val previous = remember { arrayOf(emptySet<CockpitSheet>()) }
    LaunchedEffect(open) {
        for (sheet in SheetStack.toClose(previous[0], open)) {
            when (sheet) {
                CockpitSheet.Status -> status.open = false
                CockpitSheet.Quick -> vm.setQuickSettings(false)
                CockpitSheet.Picker -> vm.setPicker(false)
                CockpitSheet.Mode -> vm.modeSheetActions.onDismiss()
                CockpitSheet.Launch -> vm.openLaunch(false)
                CockpitSheet.Camera -> vm.setCameraSettings(false)
                CockpitSheet.Preflight, CockpitSheet.Confirm -> Unit
            }
        }
        previous[0] = open
    }
    val top = SheetStack.visible(open)

    if (top == CockpitSheet.Status) {
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            StatusList(
                statusItems,
                onClose = { status.open = false },
                topInset = sizes.edge + sizes.topBar,
                modifier = Modifier.absoluteOffset(x = with(density) { status.pillX.toDp() }, y = 4.dp),
            )
        }
    }
    Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
        QuickSettingsPanel(
            open = top == CockpitSheet.Quick,
            settings = settings,
            maxBrightness = maxBrightness,
            showTelemetryPaneToggle = wide,
            stickAllModes = stickAllModes,
            onDismiss = { vm.setQuickSettings(false) },
            onAltitudeReference = { vm.setAltitudeReference(it) },
            onDensity = { vm.setDensity(it) },
            onMaxBrightness = vm::setMaxBrightness,
            onTelemetryPane = { vm.setTelemetryPane(it) },
            onStickAllModes = vm::setStickAllModes,
            panels = { extNode?.let { ExtensionSlotHost(it, "cockpit.panel", Modifier.fillMaxWidth()) } },
        )
    }
    when (top) {
        CockpitSheet.Picker -> picker?.let { ui ->
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
                SkillPickerSheet(ui, vm.pickerActions) { skill -> SkillTweak(vm, skill) }
            }
        }
        CockpitSheet.Preflight -> {
            val result by vm.preflight.collectAsStateWithLifecycle()
            val gating by vm.armGateOpen.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
                PreflightSheet(result, gating = gating, onContinue = vm::continuePreflight, onDismiss = { vm.setPreflight(false) })
            }
        }
        CockpitSheet.Mode -> modeSheet?.let { ui ->
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) { ModeSheet(ui, vm.cockpitSpec.flightState.modeSheet, vm.modeSheetActions, vm.cockpitSpec.layout.holdsMs) }
        }
        CockpitSheet.Launch -> launch?.let { ui ->
            Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
                val launchSpec = vm.cockpitSpec.layout.launch
                LaunchSheet(
                    ui, onDraft = vm::setLaunchDraft, onLaunch = vm::launchNow, onDismiss = { vm.openLaunch(false) },
                    altRange = launchSpec?.let { it.altMinM..it.altMaxM } ?: com.altnautica.gcs.feature.cockpit.launch.LAUNCH_ALT_RANGE,
                )
            }
        }
        CockpitSheet.Confirm -> Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) { ConfirmHost(confirm, vm.confirm) }
        CockpitSheet.Camera -> Box(Modifier.fillMaxSize().zIndex(Z.SHEETS)) {
            val videoState by vm.videoState.collectAsStateWithLifecycle()
            val zoom by vm.digitalZoom.collectAsStateWithLifecycle()
            CameraSettingsSheet(
                CameraSheetUi(
                    streams = video.streams,
                    selectedStreamId = video.selectedStreamId,
                    hasVideo = video.main != null && videoState is VideoState.Live,
                    digitalZoom = zoom,
                    canRecord = canRecord,
                    recording = recording,
                    pipAvailable = video.streams.count { it.live && it.switchKind == StreamSwitchKind.Concurrent } > 1,
                    pipOn = video.pip != null,
                ),
                onDismiss = { vm.setCameraSettings(false) },
                onSelectStream = vm::selectStream,
                onTogglePip = vm::togglePip,
                onToggleRecording = vm::toggleRecording,
            )
        }
        CockpitSheet.Status, CockpitSheet.Quick, null -> Unit
    }
    tourStep?.let { step ->
        // No scrim and no touch handler outside the card: STOP and both columns stay usable.
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS), contentAlignment = Alignment.Center) {
            ControllerTourCard(step, vm::nextTourStep, vm::finishTour)
        }
    }
}

/** The sheets open now, before [SheetStack] picks the one that draws. */
@Composable
private fun openSheets(vm: CockpitViewModel, status: StatusListState): Set<CockpitSheet> {
    val quickOpen by vm.quickSettingsOpen.collectAsStateWithLifecycle()
    val cameraOpen by vm.cameraSettingsOpen.collectAsStateWithLifecycle()
    val preflightOpen by vm.preflightOpen.collectAsStateWithLifecycle()
    val confirm by vm.confirm.state.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val modeSheet by vm.modeSheet.collectAsStateWithLifecycle()
    val launch by vm.launch.collectAsStateWithLifecycle()
    return buildSet {
        if (status.open) add(CockpitSheet.Status)
        if (cameraOpen) add(CockpitSheet.Camera)
        if (quickOpen) add(CockpitSheet.Quick)
        if (picker != null) add(CockpitSheet.Picker)
        if (modeSheet != null) add(CockpitSheet.Mode)
        if (launch != null) add(CockpitSheet.Launch)
        if (preflightOpen) add(CockpitSheet.Preflight)
        if (confirm != null) add(CockpitSheet.Confirm)
    }
}

/** What the tip queue needs from the flight screen ([TipFacts]); airborne as the left column reads it. */
@Composable
private fun tipFacts(vm: CockpitViewModel, status: StatusListState): TipFacts {
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val tourStep by vm.tourStep.collectAsStateWithLifecycle()
    val gimbal by vm.gimbal.available.collectAsStateWithLifecycle()
    val skills by vm.skillsAvailable.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val flow by vm.flow.collectAsStateWithLifecycle()
    val sheets = openSheets(vm, status)
    val p = pilot
    return TipFacts(
        armed = p?.armed ?: (band.armed == true),
        airborne = p?.let { it.armed && !it.landed } ?: (band.armed == true),
        sheetOrMenuOpen = sheets.isNotEmpty() || status.menuOpen,
        tourVisible = tourStep != null,
        skillsAvailable = skills,
        gotoOffered = p != null && PilotAction.GOTO in p.surfaceOps,
        gimbalAvailable = gimbal,
        alertBannerVisible = alerts.any { it.level == AlertLevel.Critical } ||
            flow.launch != null || flow.banner != null || flow.sticksWith != null || flow.linkLossInS != null || flow.rthCountdownS != null,
    )
}
