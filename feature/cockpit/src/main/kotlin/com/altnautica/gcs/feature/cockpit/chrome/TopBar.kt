package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.State
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import com.altnautica.gcs.feature.cockpit.alerts.alertTitleRes
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.band.BatteryReserve
import com.altnautica.gcs.feature.cockpit.alerts.fcStateDetailRes
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.preflight.preflightItemLabel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** Top-bar items that move into the Menu when the row runs out of room (`top_bar.overflow`). */
enum class TopBarItem(val specId: String) {
    Controller("controller"),
    NavSource("nav_source"),
    LinkDetail("link_text"),
    LinkIcon("link_icon"),
    Layers("layer_button"),
    ;

    companion object {
        /** The spec's `to_menu_order` as items, lowest priority first; unknown ids are skipped. */
        fun order(specIds: List<String>): List<TopBarItem> = specIds.mapNotNull { id -> entries.firstOrNull { it.specId == id } }
    }
}

/** Holds which items are in the Menu after the last layout; read when the Menu opens. */
class TopBarOverflow {
    var moved: Set<TopBarItem> = emptySet()
        internal set
}

/**
 * The top bar: one row that never scrolls. Left: the flight-state chip, the status pill,
 * battery, link, navigation source and controller. Right: the layer button, Menu and STOP.
 * When the row is too narrow, items move into the Menu ([overflow]) in the spec's
 * [overflowOrder] (controller, navigation source, link text, link icon, layer button);
 * then the pill truncates, and last the chip ellipsizes. The chip, pill, battery, Menu and
 * STOP never move, and the two groups never overlap.
 */
@Composable
fun TopBar(
    overflow: TopBarOverflow,
    overflowOrder: List<TopBarItem>,
    chip: @Composable () -> Unit,
    pill: @Composable () -> Unit,
    battery: @Composable () -> Unit,
    linkIcon: @Composable () -> Unit,
    linkDetail: @Composable () -> Unit,
    navSource: @Composable () -> Unit,
    controller: @Composable () -> Unit,
    layers: @Composable () -> Unit,
    menu: @Composable () -> Unit,
    stop: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalChrome.current
    ChromePanel(modifier.fillMaxSize().testTag("top-bar")) {
        Layout(
            contents = listOf(chip, pill, battery, linkIcon, linkDetail, navSource, controller, layers, menu, stop),
            modifier = Modifier.fillMaxSize().padding(horizontal = c.edge),
        ) { measurables, constraints ->
            val gap = c.gap.roundToPx()
            val loose = Constraints(maxHeight = constraints.maxHeight)
            // Every item but the chip and the pill at its natural width; those two by their intrinsic
            // widths, measured once at the end, since either may have to give up width.
            val placeables = measurables.mapIndexed { i, slot -> if (i == CHIP || i == PILL) null else slot.firstOrNull()?.measure(loose) }.toMutableList()
            val chipNatural = measurables[CHIP].firstOrNull()?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
            val pillNatural = measurables[PILL].firstOrNull()?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
            val width = { i: Int ->
                when (i) {
                    CHIP -> chipNatural
                    PILL -> pillNatural
                    else -> placeables[i]?.width ?: 0
                }
            }
            val shown = BooleanArray(placeables.size) { width(it) > 0 }
            fun total(): Int {
                var sum = 0
                var n = 0
                for (i in placeables.indices) if (shown[i]) { sum += width(i); n++ }
                return sum + gap * (n - 1).coerceAtLeast(0) + gap // one extra gap between the groups
            }
            val moved = HashSet<TopBarItem>()
            for (item in overflowOrder) {
                if (total() <= constraints.maxWidth) break
                val index = when (item) {
                    TopBarItem.Controller -> CONTROLLER
                    TopBarItem.NavSource -> NAV
                    TopBarItem.LinkDetail -> LINK_DETAIL
                    TopBarItem.LinkIcon -> LINK_ICON
                    TopBarItem.Layers -> LAYERS
                }
                if (shown[index]) { shown[index] = false; moved += item }
            }
            // Last resorts on a very narrow window: the pill truncates, then the chip ellipsizes. STOP never shrinks.
            val over = (total() - constraints.maxWidth).coerceAtLeast(0)
            val pillMax = if (over > 0) (pillNatural - over).coerceAtLeast(MIN_PILL_PX.coerceAtMost(pillNatural)) else Constraints.Infinity
            placeables[PILL] = measurables[PILL].firstOrNull()?.measure(Constraints(maxWidth = pillMax, maxHeight = constraints.maxHeight))
            val rest = (over - (pillNatural - (placeables[PILL]?.width ?: 0))).coerceAtLeast(0)
            placeables[CHIP] = measurables[CHIP].firstOrNull()?.measure(
                Constraints(maxWidth = (chipNatural - rest).coerceAtLeast(0), maxHeight = constraints.maxHeight),
            )
            overflow.moved = moved
            layout(constraints.maxWidth, constraints.maxHeight) {
                var x = 0
                for (i in 0 until LAYERS) {
                    val p = placeables[i] ?: continue
                    if (!shown[i]) continue
                    p.place(x, (constraints.maxHeight - p.height) / 2)
                    x += p.width + gap
                }
                // The right group is laid out from the right edge, then placed left to right, so
                // keyboard and D-pad focus follow the drawn order: layers, Menu, STOP.
                val rightX = IntArray(placeables.size)
                var r = constraints.maxWidth
                for (i in placeables.indices.reversed()) {
                    if (i < LAYERS) break
                    val p = placeables[i] ?: continue
                    if (!shown[i]) continue
                    r -= p.width
                    rightX[i] = r
                    r -= gap
                }
                for (i in LAYERS until placeables.size) {
                    val p = placeables[i] ?: continue
                    if (!shown[i]) continue
                    p.place(rightX[i], (constraints.maxHeight - p.height) / 2)
                }
            }
        }
    }
}

private const val CHIP = 0
private const val PILL = 1
private const val LINK_ICON = 3
private const val LINK_DETAIL = 4
private const val NAV = 5
private const val CONTROLLER = 6
private const val LAYERS = 7

/** Narrowest the pill truncates to (px): its dot and a few letters stay readable. */
private const val MIN_PILL_PX = 96

/**
 * The status pill: "All systems OK", the one alert's text, or "n warnings"; flashes while
 * unacknowledged. The flash is read only in the draw phase, so it never recomposes or
 * re-lays out the pill.
 */
@Composable
fun StatusPill(ui: StatusPillUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val tint = severityColor(ui.severity)
    val reduced = rememberReducedMotion()
    val flash: State<Float>? = if (ui.flashing && !reduced) {
        val t = rememberInfiniteTransition(label = "status-flash")
        t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(FLASH_HALF_MS), RepeatMode.Reverse), label = "status-flash-alpha")
    } else {
        null
    }
    val ok = ui.severity == StatusSeverity.Ok
    val text = when {
        ui.count == 0 -> specText("cockpit.status.ok")
        ui.count == 1 -> ui.top?.let { statusItemTitle(it) } ?: specText("cockpit.status.one")
        else -> specText("cockpit.status.many", "n" to ui.count.toString())
    }
    Row(
        modifier
            .heightIn(min = 32.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(c.radiusSmall))
            .drawBehind { drawRect(tint.copy(alpha = if (ok) 0.14f else 0.22f * (flash?.value ?: 1f) + 0.06f)) }
            .clickable(role = Role.Button, onClickLabel = specText("cockpit.alerts.title"), onClick = onOpen)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("status-pill")
            .padding(horizontal = c.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = flash?.value ?: 1f }.background(tint, CircleShape))
        Text(text, style = c.chipText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun severityColor(s: StatusSeverity): Color {
    val c = LocalChrome.current
    return when (s) {
        StatusSeverity.Ok -> c.ok
        StatusSeverity.Warning -> c.warning
        StatusSeverity.Critical -> c.critical
    }
}

/** An item's one-line title. */
@Composable
fun statusItemTitle(item: StatusItem): String {
    item.alert?.let { a ->
        val title = stringResource(alertTitleRes(a.id))
        val detail = a.detail?.let { d -> fcStateDetailRes(d)?.let { stringResource(it) } ?: d }
        return if (detail != null) "$title · $detail" else title
    }
    item.check?.let { return preflightItemLabel(it, known = true) }
    return item.text.orEmpty()
}

/**
 * The alert list under the pill: every active item with its source. Opening it
 * acknowledges what it shows; a tap outside it or system Back closes it. Nothing above
 * [topInset] (the top bar) is covered, so STOP takes the first press.
 */
@Composable
fun StatusList(items: List<StatusItem>, onClose: () -> Unit, modifier: Modifier = Modifier, topInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val c = LocalChrome.current
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = onClose,
        )
    }
    Box(Modifier.fillMaxSize().padding(top = topInset).clickable(interactionSource = null, indication = null, onClick = onClose)) {
        ChromePanel(modifier.widthIn(min = 280.dp, max = 420.dp).testTag("status-list")) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(c.pad)) {
                Text(specText("cockpit.alerts.title"), style = c.chipText)
                Spacer(Modifier.height(6.dp))
                if (items.isEmpty()) {
                    Text(specText("cockpit.alerts.empty"), style = c.topBarText.copy(color = c.textDim))
                }
                for (item in items) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(severityColor(item.severity), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(statusItemTitle(item), style = c.topBarText, maxLines = 2)
                            Text(specText("cockpit.status.source.${item.source.key}"), style = c.labelText)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Battery: a gauge with the return-reserve tick, the percent and the time left. [reservePct]
 * is the flight controller's return reserve (null hides the tick); [toReserve] says whether
 * [timeLeftS] runs to that reserve or, without one, to empty.
 */
@Immutable
data class BatteryUi(val pct: Int?, val timeLeftS: Int?, val reservePct: Int?, val toReserve: Boolean, val severity: StatusSeverity)

/**
 * The gauge from the agent battery engine's [reserve] when there is one: its reserve tick
 * and its time to that reserve. Without one there is no tick, and the flight controller's
 * own estimate ([flightControllerTimeS], `BATTERY_STATUS.time_remaining`) is time to empty.
 */
fun batteryUi(pct: Int?, reserve: BatteryReserve?, flightControllerTimeS: Int?, severity: StatusSeverity): BatteryUi {
    val toReserve = reserve?.toReserveS
    return BatteryUi(
        pct = pct,
        timeLeftS = toReserve ?: flightControllerTimeS,
        reservePct = reserve?.reservePct,
        toReserve = toReserve != null,
        severity = severity,
    )
}

@Composable
fun BatteryGauge(ui: BatteryUi, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val tint = if (ui.pct == null) c.textDim else severityColor(ui.severity)
    val reserveLabel = specText("cockpit.battery.reserve")
    val clock = ui.timeLeftS?.let(::formatClock)
    val timeLeft = clock?.let {
        stringResource(if (ui.toReserve) R.string.cockpit_battery_to_reserve else R.string.cockpit_battery_to_empty, it)
    }
    val shown = clock?.let {
        stringResource(if (ui.toReserve) R.string.cockpit_battery_to_reserve_short else R.string.cockpit_battery_to_empty_short, it)
    }
    val described = listOfNotNull(ui.pct?.let { "$it%" } ?: "—", timeLeft, ui.reservePct?.let { "$reserveLabel $it%" }).joinToString(", ")
    Row(
        modifier.semantics { contentDescription = described; liveRegion = LiveRegionMode.Polite }.testTag("battery"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
            val stroke = 1.2.dp.toPx()
            val bodyW = size.width - 2.dp.toPx()
            drawRoundRect(c.textDim, Offset.Zero, Size(bodyW, size.height), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
            drawRect(c.textDim, Offset(bodyW, size.height * 0.3f), Size(2.dp.toPx(), size.height * 0.4f))
            val inner = bodyW - stroke * 4
            ui.pct?.let { p ->
                drawRect(tint, Offset(stroke * 2, stroke * 2), Size(inner * p.coerceIn(0, 100) / 100f, size.height - stroke * 4))
            }
            // The return reserve the flight controller keeps for the trip home, when it reports one.
            ui.reservePct?.let { r ->
                val rx = stroke * 2 + inner * r.coerceIn(0, 100) / 100f
                drawLine(c.text, Offset(rx, -1f), Offset(rx, size.height + 1f), 1.5.dp.toPx())
            }
        }
        Text(ui.pct?.let { "$it%" } ?: "—", style = c.chipText)
        shown?.let { Text(it, style = c.topBarText.copy(color = c.textDim), maxLines = 1) }
    }
}

/** m:ss, or h:mm:ss from an hour. */
fun formatClock(totalS: Int): String {
    val s = totalS.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Link signal icon, coloured by health. */
@Composable
fun LinkIcon(health: LinkHealth, description: String, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val (icon, tint) = when (health) {
        LinkHealth.None -> Icons.Outlined.SignalCellularConnectedNoInternet0Bar to c.textDim
        LinkHealth.Ok -> Icons.Outlined.SignalCellularAlt to c.ok
        LinkHealth.Stale -> Icons.Outlined.SignalCellularAlt to c.warning
        LinkHealth.Lost -> Icons.Outlined.SignalCellularConnectedNoInternet0Bar to c.critical
    }
    Icon(icon, contentDescription = description, tint = tint, modifier = modifier.size(c.icon).testTag("link"))
}

@Composable
fun ChromeText(text: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    val c = LocalChrome.current
    Text(text, style = if (dim) c.topBarText.copy(color = c.textDim) else c.topBarText, maxLines = 1, modifier = modifier)
}

/** The controller: pad icon plus its battery, accent while it flies the sticks. */
@Composable
fun ControllerIndicator(text: String, active: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(c.radiusSmall))
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 4.dp)
            .testTag("controller"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Gamepad, contentDescription = null, tint = if (active) c.ok else c.textDim, modifier = Modifier.size(c.icon))
        Text(text, style = c.topBarText, maxLines = 1)
    }
}

/** A square top-bar icon button (48 dp touch target). */
@Composable
fun ChromeIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = LocalChrome.current
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(c.radiusSmall))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: c.text, modifier = Modifier.size(c.icon + 2.dp))
    }
}

@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeIconButton(Icons.Outlined.Menu, specText("cockpit.menu"), onClick, modifier.testTag("menu"))
}

/** STOP: always fully visible, never scrolled or moved into the Menu. */
@Composable
fun StopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val label = specText("cockpit.stop")
    Box(
        modifier
            .defaultMinSize(minWidth = 72.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(c.radiusSmall))
            .background(c.stopBg)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label }
            .testTag("stop")
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = c.chipText.copy(color = c.stopText))
    }
}

private const val FLASH_HALF_MS = 450
