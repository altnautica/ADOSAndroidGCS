package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome

/**
 * A tappable part of the link cell (its icon or its text): a 48 dp touch target at the
 * chrome control height whose tap opens the link popover.
 */
@Composable
internal fun LinkCellTarget(label: String, onOpen: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalChrome.current
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(c.control)
            .clip(RoundedCornerShape(c.radiusSmall))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** The link cell's text: the link age, then the reduced update rate notice while the 2 Hz poll stands in. */
internal fun linkCellText(strings: SetupStringsSpec, linkText: String, reducedRate: Boolean): String =
    if (reducedRate) linkText + " · " + strings.format("nav.reduced_rate") else linkText

/** The Menu's link row (the link cell moved into the Menu): the cell's text ([linkCellText]) and the radio's. */
internal fun menuLinkRow(strings: SetupStringsSpec, cellText: String, rssiDbm: Int?, lqPct: Int?): String =
    strings.format("cockpit.menu.link_named", mapOf("label" to listOfNotNull(cellText, radioText(strings, rssiDbm, lqPct)).joinToString(" · ")))

/**
 * The link popover's rows, opened by a tap on the link cell at every width: the newest
 * frame's age ([linkText]), the radio's RSSI and link quality when it reports them, and
 * the reduced update rate notice while the navigation stream falls back to the 2 Hz poll.
 */
internal fun linkRows(strings: SetupStringsSpec, linkText: String, rssiDbm: Int?, lqPct: Int?, reducedRate: Boolean): List<String> = buildList {
    add(strings.format("cockpit.menu.link_named", mapOf("label" to linkText)))
    rssiDbm?.let { add(strings.format("cockpit.link.rssi", mapOf("v" to strings.format("cockpit.link.rssi_dbm", mapOf("dbm" to it.toString()))))) }
    lqPct?.let { add(strings.format("cockpit.link.lq", mapOf("pct" to it.toString()))) }
    if (reducedRate) add(strings.format("nav.reduced_rate"))
}

/** RSSI and link quality on one line for the Menu's link row, or null when the radio reports neither. */
internal fun radioText(strings: SetupStringsSpec, rssiDbm: Int?, lqPct: Int?): String? = when {
    rssiDbm != null && lqPct != null -> strings.format("cockpit.link.rssi_dbm_lq", mapOf("dbm" to rssiDbm.toString(), "pct" to lqPct.toString()))
    rssiDbm != null -> strings.format("cockpit.link.rssi_dbm", mapOf("dbm" to rssiDbm.toString()))
    lqPct != null -> strings.format("cockpit.link.lq", mapOf("pct" to lqPct.toString()))
    else -> null
}

/**
 * The Menu's rows in the spec's `menu.order`: `overflow` (the top-bar items moved into the
 * Menu), then each entry that applies now. `cameras` needs more than one live stream,
 * `preflight` a checklist, and `setup` an aircraft that still needs setup.
 */
internal fun menuEntries(order: List<String>, liveStreams: Int, preflightTotal: Int, setupNeeded: Boolean): List<String> = order.filter { entry ->
    when (entry) {
        "cameras" -> liveStreams > 1
        "preflight" -> preflightTotal > 0
        "setup" -> setupNeeded
        "overflow", "view", "layers", "modes", "controls", "extension_panels", "settings", "exit" -> true
        else -> false
    }
}

/** The Menu's order when the spec names none (`layout.json` `menu.order`). */
internal val DEFAULT_MENU_ORDER = listOf("overflow", "view", "cameras", "layers", "preflight", "modes", "controls", "extension_panels", "setup", "settings", "exit")

/** Test tag of the link cell's tappable icon. */
internal const val LINK_CELL_TAG = "link-cell"
