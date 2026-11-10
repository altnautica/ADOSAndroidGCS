package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
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
) {
    // The flight screen is locked to landscape, so it always takes the width's landscape class.
    val layoutClass = vm.cockpitSpec.layout.classes[layoutClassId(width, landscape = true)]
    val chrome = remember(vm) { Chrome.from(vm.cockpitSpec.tokens.chrome) }
    val backdrop = remember { ChromeBackdrop() }
    CompositionLocalProvider(
        LocalCockpitStrings provides vm.specStrings,
        LocalLayoutClass provides layoutClass,
        LocalChrome provides chrome,
        LocalChromeBackdrop provides backdrop,
    ) {
        BackdropSampler(backdrop)
        Box(Modifier.fillMaxSize().background(Color.Black).focusRings(layoutClass.focusRingsAlways, AdosTheme.colors.focusRing)) {
            FlightScreen(vm, width, onExit)
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

@Composable
private fun FlightScreen(vm: CockpitViewModel, width: WidthClass, onExit: () -> Unit) {
    val view by vm.view.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gimbal by vm.gimbal.available.collectAsStateWithLifecycle()
    val statusItems by vm.statusItems.collectAsStateWithLifecycle()
    var acknowledged by remember { mutableStateOf(emptySet<String>()) }
    var statusOpen by remember { mutableStateOf(false) }
    var pillX by remember { mutableFloatStateOf(0f) }
    val overflow = remember { TopBarOverflow() }
    val sizes = remember(width) { flightSizes(width) }
    Box(Modifier.fillMaxSize()) {
        FlightGrid(
            width = width,
            gimbal = gimbal,
            background = { zones ->
                CompositionLocalProvider(LocalLayerChipInset provides layerChipInset(zones)) {
                    MainView(vm, view, settings.splitRatio, centreInset(zones), Modifier.fillMaxSize())
                }
            },
            topBar = {
                FlightTopBar(
                    vm = vm,
                    width = width,
                    overflow = overflow,
                    onExit = onExit,
                    pill = {
                        StatusPill(
                            StatusModel.pill(statusItems, acknowledged),
                            onOpen = {
                                // Opening the list acknowledges what it shows.
                                acknowledged = statusItems.mapTo(HashSet()) { it.key }
                                statusOpen = true
                            },
                            modifier = Modifier.onGloballyPositioned { pillX = it.boundsInRoot().left },
                        )
                    },
                )
            },
            leftColumn = { LeftColumn(vm, sizes.leftButton, sizes.leftGap) },
            rightColumn = { inColumn -> RightColumn(vm, sizes, inColumn) },
            gimbalPad = { Pad(vm, sizes.gimbalPad) },
            card = { Card(vm) },
            bottomRow = {
                val values by vm.flightValues.collectAsStateWithLifecycle()
                BottomRow(values)
            },
            skills = { PickerChip(vm, Modifier.align(Alignment.BottomEnd)) },
            centre = { zones -> Centre(vm, width, zones) },
        )
        if (statusOpen) {
            val density = LocalDensity.current
            StatusList(
                statusItems,
                onClose = { statusOpen = false },
                modifier = Modifier
                    .absoluteOffset(x = with(density) { pillX.toDp() }, y = sizes.edge + sizes.topBar + 4.dp)
                    .zIndex(Z.SHEETS),
            )
        }
    }
}

/** The HUD's inset: the centre zone's sides and top, so no column or the top bar ever sits on a tape or the heading scale. */
/** The video's corner left free by the chrome: the layer chips stack up from the centre zone's bottom-left. */
private fun layerChipInset(zones: FlightZones): PaddingValues {
    val windowHeight = zones.card.bottom + zones.topBar.y
    return PaddingValues(start = zones.centre.x, top = zones.centre.y, bottom = windowHeight - zones.centre.bottom)
}

private fun centreInset(zones: FlightZones): PaddingValues {
    val windowWidth = zones.topBar.right + zones.topBar.x
    return PaddingValues(start = zones.centre.x, top = zones.centre.y, end = windowWidth - zones.centre.right)
}

@Composable
private fun FlightTopBar(
    vm: CockpitViewModel,
    width: WidthClass,
    overflow: TopBarOverflow,
    onExit: () -> Unit,
    pill: @Composable () -> Unit,
) {
    val chip by vm.chip.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val reserve by vm.batteryReserve.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val menu = rememberAdosMenuState()
    val linkText = linkText(band)
    val controllerText = controllerText(band.stick)
    TopBar(
        overflow = overflow,
        chip = { FlightStateChip(chip, onOpen = vm::openModeSheet, onButton = vm::onChipButton) },
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
            )
        },
        linkIcon = { LinkIcon(band.link, linkText) },
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
        layers = { LayerLevelButton(vm.autonomy.layers) },
        menu = { MenuButton(onClick = { menu.expanded = true }, modifier = Modifier.adosMenuAnchor(menu)) },
        stop = { StopButton(onClick = { vm.activate(BuiltinIds.KILL) }) },
    )
    val items = buildList {
        band.nodeName?.let { name ->
            add(AdosMenuItem(name + (band.reach?.let { " · " + reachText(it) } ?: ""), enabled = false) {})
        }
        add(AdosMenuItem(stringResource(R.string.cockpit_menu_home), Icons.AutoMirrored.Outlined.ArrowBack) { onExit() })
        if (band.preflightTotal > 0) {
            add(AdosMenuItem(stringResource(R.string.cockpit_menu_preflight, band.preflightPassed, band.preflightTotal), Icons.Outlined.Checklist) { vm.setPreflight(true) })
        }
        add(AdosMenuItem(stringResource(R.string.cockpit_menu_modes), Icons.Outlined.FlightTakeoff) { vm.openModeSheet() })
        add(AdosMenuItem(stringResource(R.string.cockpit_menu_settings), Icons.Outlined.Tune) { vm.setQuickSettings(true) })
        add(AdosMenuItem(stringResource(R.string.cockpit_menu_view), Icons.Outlined.GridView) { vm.nextView() })
        val live = video.streams.filter { it.live }
        if (live.size > 1) {
            for (s in live) {
                add(AdosMenuItem(stringResource(R.string.cockpit_menu_camera, s.role), Icons.Outlined.Videocam, enabled = s.id != video.selectedStreamId) { vm.selectStream(s.id) })
            }
        }
        // Items the top bar had no room for.
        val moved = overflow.moved
        if (TopBarItem.NavSource in moved) {
            val nav by vm.autonomy.navChip.collectAsStateWithLifecycle()
            val text = com.altnautica.gcs.feature.cockpit.autonomy.TargetText(androidx.compose.ui.platform.LocalResources.current)
            nav?.let { add(AdosMenuItem(text.navLabel(it), Icons.Outlined.SatelliteAlt, enabled = false) {}) }
        }
        if (TopBarItem.LinkDetail in moved && band.link != LinkHealth.None) {
            add(AdosMenuItem(stringResource(R.string.cockpit_menu_link, linkText), enabled = false) {})
        }
        if (TopBarItem.Controller in moved && controllerText != null) {
            val stick = band.stick
            add(
                AdosMenuItem(stringResource(R.string.cockpit_menu_controller, controllerText), enabled = stick is StickChip.Available) {
                    vm.toggleStickControl()
                },
            )
        }
        if (TopBarItem.Layers in moved) {
            val layers = vm.autonomy.layers
            val flags by layers.flags.collectAsStateWithLifecycle()
            add(AdosMenuItem(stringResource(R.string.layers_button, com.altnautica.gcs.feature.cockpit.hud.layers.levelLabel(flags.level))) { layers.cycleLevel(1) })
        }
    }
    AdosMenu(menu, items)
}

@Composable
private fun linkText(band: BandState): String = when {
    band.link == LinkHealth.None -> stringResource(R.string.cockpit_link_none)
    band.rssiDbm != null -> "${band.rssiDbm} dBm" + (band.lqPct?.let { " · $it%" } ?: "")
    band.lqPct != null -> "LQ ${band.lqPct}%"
    band.linkAgeDeciS != null -> "%.1f s".format(band.linkAgeDeciS / 10f)
    else -> "—"
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
        if (airborne) {
            RoundButton(Icons.Outlined.FlightLand, labels[BuiltinIds.LAND].orEmpty(), size, "grid-land", { vm.activate(BuiltinIds.LAND) })
        } else {
            RoundButton(Icons.Outlined.FlightTakeoff, labels[BuiltinIds.TAKEOFF].orEmpty(), size, "grid-takeoff", { vm.openLaunch(true) })
        }
        RoundButton(Icons.Outlined.Home, labels[BuiltinIds.RTH].orEmpty(), size, "grid-rth", { vm.activate(BuiltinIds.RTH) })
        RoundButton(Icons.Outlined.PauseCircle, labels[BuiltinIds.PAUSE].orEmpty(), size, "grid-pause", { vm.activate(BuiltinIds.PAUSE) })
    }
}

/** Shutter, photo/video switch, zoom, the gimbal pad when it fits here, camera settings. */
@Composable
private fun RightColumn(vm: CockpitViewModel, sizes: com.altnautica.gcs.feature.cockpit.chrome.FlightSizes, gimbalInColumn: Boolean) {
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
        ZoomPill(
            inLabel = stringResource(R.string.grid_zoom_in),
            outLabel = stringResource(R.string.grid_zoom_out),
            onIn = vm::zoomIn,
            onOut = vm::zoomOut,
            onLongPress = null,
            width = sizes.rightButton,
            height = sizes.zoom,
        )
        if (gimbalInColumn) Pad(vm, sizes.gimbalPad)
        RoundButton(Icons.Outlined.Tune, stringResource(R.string.grid_camera_settings), sizes.rightButton, "grid-camera-settings", { vm.setQuickSettings(true) })
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
    )
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

/** Centre overlays, all inside the centre zone: banners and the critical alert at its top, rings at the screen centre. */
@Composable
private fun Centre(vm: CockpitViewModel, width: WidthClass, zones: FlightZones) {
    val onScreen by vm.onScreenSticksEnabled.collectAsStateWithLifecycle()
    val rect = zones.centre
    Box(Modifier.fillMaxSize()) {
        CentreRings(vm, Modifier.align(Alignment.Center).zIndex(Z.ALERTS))
        InZone(rect, Z.ALERTS) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            if (onScreen && (width == WidthClass.Compact || width == WidthClass.Expanded)) {
                OnScreenSticksRow(vm, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

/** Height the HUD's heading scale and readout take at the top of the centre at the Instruments level. */
private val HEADING_SCALE_CLEARANCE = 76.dp

@Composable
private fun InZone(rect: ZoneRect, z: Float, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.absoluteOffset(rect.x, rect.y).size(rect.width, rect.height).zIndex(z), content = content)
}

/** The main view: video, map with video picture-in-picture, the two side by side, or video with the vision panel. */
@Composable
private fun MainView(vm: CockpitViewModel, view: CockpitView, savedRatio: Float, hudInset: PaddingValues, modifier: Modifier) {
    val startOnly = PaddingValues(start = hudInset.calculateStartPadding(LayoutDirection.Ltr), top = hudInset.calculateTopPadding())
    when (view) {
        CockpitView.Pilot -> VideoStage(vm, modifier, hudInset = hudInset, sampleBackdrop = true)
        CockpitView.Map -> Box(modifier) {
            Minimap(vm, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 240.dp, height = 135.dp)
                    .clip(RoundedCornerShape(AdosRadius.Lg))
                    .zIndex(Z.PIP),
            ) {
                VideoStage(vm, Modifier.fillMaxSize(), showHud = false, showLayers = false)
            }
        }
        CockpitView.Split -> SplitView(vm, savedRatio, startOnly, modifier)
        CockpitView.Vision -> Row(modifier) {
            VideoStage(vm, Modifier.weight(1f).fillMaxHeight(), hudInset = startOnly, sampleBackdrop = true)
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
    val backdrop = LocalChromeBackdrop.current
    Box(modifier.background(AdosTheme.colors.mediaLetterbox)) {
        if (swapped && showHud) {
            Minimap(vm, Modifier.fillMaxSize())
            return@Box
        }
        val sampler = backdrop?.takeIf { sampleBackdrop }
        VideoSurface(
            video.main,
            Modifier
                .fillMaxSize()
                .zIndex(Z.VIDEO)
                .let { m -> if (sampler != null) m.onGloballyPositioned { sampler.videoBounds = it.boundsInRoot() } else m },
            onSurfaceView = sampler?.let { b -> { view -> b.surfaceView = view } },
        )
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

/** Quick settings and the sheets, all drawn in the cockpit window above the flight surface (z 50). */
@Composable
private fun Overlays(vm: CockpitViewModel, width: WidthClass) {
    val quickOpen by vm.quickSettingsOpen.collectAsStateWithLifecycle()
    val preflightOpen by vm.preflightOpen.collectAsStateWithLifecycle()
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
    val tourStep by vm.tourStep.collectAsStateWithLifecycle()
    tourStep?.let { step ->
        // No scrim and no touch handler outside the card: STOP and both columns stay usable.
        Box(Modifier.fillMaxSize().zIndex(Z.SHEETS), contentAlignment = Alignment.Center) {
            ControllerTourCard(step, vm::nextTourStep, vm::finishTour)
        }
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
