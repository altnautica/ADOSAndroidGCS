package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuState
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.core.skills.spec.ZoneLinkSpec
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import com.altnautica.gcs.feature.cockpit.chip.ChipButton
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChip
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipResolver
import com.altnautica.gcs.feature.cockpit.chrome.BatteryGauge
import com.altnautica.gcs.feature.cockpit.chrome.ChromeText
import com.altnautica.gcs.feature.cockpit.chrome.ControllerIndicator
import com.altnautica.gcs.feature.cockpit.chrome.LinkIcon
import com.altnautica.gcs.feature.cockpit.chrome.MenuButton
import com.altnautica.gcs.feature.cockpit.chrome.StatusSeverity
import com.altnautica.gcs.feature.cockpit.chrome.StopButton
import com.altnautica.gcs.feature.cockpit.chrome.TopBar
import com.altnautica.gcs.feature.cockpit.chrome.TopBarItem
import com.altnautica.gcs.feature.cockpit.chrome.TopBarOverflow
import com.altnautica.gcs.feature.cockpit.chrome.batteryUi
import com.altnautica.gcs.feature.cockpit.chrome.linkAgeSeconds
import com.altnautica.gcs.feature.cockpit.chrome.linkIconHealth
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerLevelButton
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.tips.tipAnchor

/** The flight screen's top bar and its Menu (the items the top bar had no room for, then the spec's `menu.order`). */
@Composable
internal fun FlightTopBar(
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
    val reducedRate by vm.autonomy.feed.reducedRate.collectAsStateWithLifecycle()
    val menu = rememberAdosMenuState()
    val linkMenu = rememberAdosMenuState()
    LaunchedEffect(menu.expanded) { onMenuOpen(menu.expanded) }
    val topBarZone = remember(vm) { vm.cockpitSpec.layout.zones.firstOrNull { it.id == "top_bar" } }
    val linkSpec = topBarZone?.link
    val linkText = linkText(band, linkSpec)
    val strings = LocalCockpitStrings.current
    // The 2 Hz fallback is named on the link cell itself, so every view shows it.
    val linkDetail = linkCellText(strings, linkText, reducedRate)
    val openLink = { linkMenu.expanded = true }
    val linkLabel = strings.format("cockpit.menu.link_named", mapOf("label" to linkText))
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
        linkIcon = {
            LinkCellTarget(linkLabel, openLink, Modifier.adosMenuAnchor(linkMenu).testTag(LINK_CELL_TAG)) {
                LinkIcon(linkIconHealth(band.link, band.linkAgeDeciS, linkSpec), linkText)
            }
        },
        linkDetail = {
            if (band.link != LinkHealth.None || reducedRate) {
                LinkCellTarget(linkLabel, openLink, Modifier.adosMenuAnchor(linkMenu)) { ChromeText(linkDetail, dim = true) }
            }
        },
        navSource = { NavChipSlot(vm, width) },
        controller = { ControllerCell(band.stick, vm::toggleStickControl) },
        layers = { LayerLevelButton(vm.autonomy.layers, Modifier.tipAnchor("layer_button")) },
        menu = { MenuButton(onClick = { menu.expanded = true }, modifier = Modifier.adosMenuAnchor(menu)) },
        stop = { StopButton(onClick = { vm.activate(BuiltinIds.KILL) }, modifier = Modifier.tipAnchor("stop")) },
    )
    AdosMenu(linkMenu, linkRows(strings, linkText, band.rssiDbm, band.lqPct, reducedRate).map { AdosMenuItem(it, enabled = false) {} })
    if (menu.expanded) FlightMenu(vm, band, overflow.moved, linkDetail, menu, onExit, onOpenLink = openLink)
}

/** The Menu's rows, built only while it is open. */
@Composable
private fun FlightMenu(
    vm: CockpitViewModel,
    band: BandState,
    moved: Set<TopBarItem>,
    cellText: String,
    menu: AdosMenuState,
    onExit: () -> Unit,
    onOpenLink: () -> Unit,
) {
    val video by vm.video.collectAsStateWithLifecycle()
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    val fresh by vm.pilotFresh.collectAsStateWithLifecycle()
    val nav by vm.autonomy.navChip.collectAsStateWithLifecycle()
    val layerFlags by vm.autonomy.layers.flags.collectAsStateWithLifecycle()
    val strings = LocalCockpitStrings.current
    val navText = com.altnautica.gcs.feature.cockpit.autonomy.TargetText(strings)
    val controllerText = controllerText(band.stick)
    val live = video.streams.filter { it.live }
    val setupNeeded = fresh && FlightStateChipResolver.setupNeeded(pilot)
    val order = vm.cockpitSpec.layout.menu?.order.orEmpty().ifEmpty { DEFAULT_MENU_ORDER }
    fun f(key: String, vararg args: Pair<String, String>) = strings.format(key, args.toMap())
    val items = buildList {
        band.nodeName?.let { name ->
            add(AdosMenuItem(name + (band.reach?.let { " · " + reachText(strings, it) } ?: ""), enabled = false) {})
        }
        for (entry in menuEntries(order, live.size, band.preflightTotal, setupNeeded)) when (entry) {
            // Only the items the top bar actually had no room for; the layer button has its own row.
            "overflow" -> {
                if (TopBarItem.Controller in moved) {
                    add(AdosMenuItem(f("cockpit.menu.controller_named", "name" to controllerText), enabled = band.stick is StickChip.Available) { vm.toggleStickControl() })
                }
                if (TopBarItem.NavSource in moved) nav?.let { add(AdosMenuItem(navText.navLabel(it), Icons.Outlined.SatelliteAlt, enabled = false) {}) }
                if ((TopBarItem.LinkDetail in moved || TopBarItem.LinkIcon in moved) && band.link != LinkHealth.None) {
                    // Where the link cell has moved into the Menu, its row opens the same link popover.
                    add(AdosMenuItem(menuLinkRow(strings, cellText, band.rssiDbm, band.lqPct)) { onOpenLink() })
                }
            }
            "view" -> add(AdosMenuItem(f("cockpit.menu.view"), Icons.Outlined.GridView) { vm.nextView() })
            "cameras" -> for (s in live) {
                add(AdosMenuItem(f("cockpit.menu.camera_named", "name" to s.role), Icons.Outlined.Videocam, enabled = s.id != video.selectedStreamId) { vm.selectStream(s.id) })
            }
            "layers" -> add(AdosMenuItem(com.altnautica.gcs.feature.cockpit.hud.layers.layerButtonText(layerFlags.level), Icons.Outlined.Layers) { vm.autonomy.layers.cycleLevel(1) })
            "preflight" -> add(
                AdosMenuItem(f("cockpit.menu.preflight_count", "n" to band.preflightPassed.toString(), "total" to band.preflightTotal.toString()), Icons.Outlined.Checklist) {
                    vm.setPreflight(true)
                },
            )
            "modes" -> add(AdosMenuItem(f("cockpit.menu.modes"), Icons.Outlined.FlightTakeoff) { vm.openModeSheet() })
            "controls" -> add(AdosMenuItem(f("cockpit.menu.controls"), Icons.Outlined.SportsEsports) { vm.openControls() })
            // Extension panels are hosted in quick settings.
            "extension_panels" -> add(AdosMenuItem(f("cockpit.menu.extension_panels"), Icons.Outlined.Extension) { vm.setQuickSettings(true) })
            "setup" -> add(AdosMenuItem(f("cockpit.menu.setup"), Icons.Outlined.Build) { vm.onChipButton(ChipButton.SetUpAircraft) })
            "settings" -> add(AdosMenuItem(f("cockpit.menu.settings"), Icons.Outlined.Tune) { vm.setQuickSettings(true) })
            "exit" -> add(AdosMenuItem(f("cockpit.menu.home"), Icons.AutoMirrored.Outlined.ArrowBack) { onExit() })
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
        age != null -> specText(
            spec?.text ?: "cockpit.link.age",
            "s" to linkAgeSeconds(age * 100L, spec?.ageFormat ?: com.altnautica.gcs.core.skills.spec.LinkAgeFormatSpec()),
        )
        else -> "—"
    }
}

/** The top-bar controller cell: pad battery or why the sticks are blocked, and "No controller" before any pad is seen. */
@Composable
internal fun ControllerCell(stick: StickChip, onToggle: () -> Unit) {
    val text = controllerText(stick)
    when (stick) {
        is StickChip.Available -> ControllerIndicator(text, stick.on, onToggle)
        is StickChip.Pilot -> ControllerIndicator(text, stick.streaming, null)
        is StickChip.ModeBlocked, StickChip.LinkBlocked, StickChip.NoGamepad -> ControllerIndicator(text, false, null)
    }
}

@Composable
private fun controllerText(stick: StickChip): String {
    val pct = com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi.current.batteryPct
    return when (stick) {
        StickChip.NoGamepad -> specText("controls.no_controller")
        StickChip.LinkBlocked -> specText("cockpit.sticks.blocked")
        is StickChip.ModeBlocked -> specText("cockpit.sticks.mode_blocked", "mode" to stick.mode.uppercase())
        is StickChip.Available, is StickChip.Pilot -> pct?.let { "$it%" } ?: "—"
    }
}

/** How the cockpit reaches the vehicle, in the spec's words (`cockpit.reach.*`). */
internal fun reachText(strings: SetupStringsSpec, badge: ReachBadge): String = strings.format(
    when (badge) {
        ReachBadge.Direct -> "cockpit.reach.direct"
        ReachBadge.Lan -> "cockpit.reach.lan"
        ReachBadge.ViaGround -> "cockpit.reach.via_ground"
        ReachBadge.Cloud -> "cockpit.reach.cloud"
        ReachBadge.PhoneRadio -> "cockpit.reach.phone_radio"
        ReachBadge.Blocked -> "cockpit.reach.blocked"
    },
)
