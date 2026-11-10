package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.spec.ZoneLinkSpec
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChip
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

/** The Menu's order when the spec names none (`layout.json` `menu.order`). */
private val DEFAULT_MENU_ORDER = listOf("overflow", "view", "cameras", "layers", "preflight", "modes", "controls", "extension_panels", "setup", "settings", "exit")

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
        controller = { ControllerCell(band.stick, vm::toggleStickControl) },
        layers = { LayerLevelButton(vm.autonomy.layers, Modifier.tipAnchor("layer_button")) },
        menu = { MenuButton(onClick = { menu.expanded = true }, modifier = Modifier.adosMenuAnchor(menu)) },
        stop = { StopButton(onClick = { vm.activate(BuiltinIds.KILL) }, modifier = Modifier.tipAnchor("stop")) },
    )
    val moved = overflow.moved
    val nav by vm.autonomy.navChip.collectAsStateWithLifecycle()
    val layerFlags by vm.autonomy.layers.flags.collectAsStateWithLifecycle()
    val strings = LocalCockpitStrings.current
    val navText = com.altnautica.gcs.feature.cockpit.autonomy.TargetText(strings)
    val menuOrder = vm.cockpitSpec.layout.menu?.order.orEmpty().ifEmpty { DEFAULT_MENU_ORDER }
    val items = buildList {
        band.nodeName?.let { name ->
            add(AdosMenuItem(name + (band.reach?.let { " · " + reachText(it) } ?: ""), enabled = false) {})
        }
        for (entry in menuOrder) when (entry) {
            // Only the items the top bar actually had no room for.
            "overflow" -> {
                if (TopBarItem.Controller in moved) {
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
