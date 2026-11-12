package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** A zone rectangle in dp, from the flight screen's top-left corner. */
@Immutable
data class ZoneRect(val x: Dp, val y: Dp, val width: Dp, val height: Dp) {
    val right: Dp get() = x + width
    val bottom: Dp get() = y + height

    fun intersects(o: ZoneRect): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun inside(w: Dp, h: Dp): Boolean = x >= 0.dp && y >= 0.dp && right <= w && bottom <= h

    val offset: DpOffset get() = DpOffset(x, y)
    val size: DpSize get() = DpSize(width, height)
}

/** Per-class sizes of the flight grid (`layout.json` classes, TV at 1.33×). */
@Immutable
data class FlightSizes(
    val edge: Dp,
    val gap: Dp,
    val topBar: Dp,
    /** Left-column buttons: take off or land, return home, pause. */
    val leftButton: Dp,
    val leftGap: Dp,
    /** The shutter; the right column's width. */
    val shutter: Dp,
    /** Photo/video switch height, zoom pill height (two 48 dp halves) and the camera-settings button; every target at least 48 dp. */
    val modeSwitch: Dp,
    val zoom: Dp,
    val rightButton: Dp,
    val rightGap: Dp,
    val gimbalPad: Dp,
    val card: DpSize,
    val bottomRow: Dp,
    val skills: DpSize,
) {
    val rightColumnHeight: Dp get() = shutter + modeSwitch + zoom + rightButton + rightGap * 3
    val leftColumnHeight: Dp get() = leftButton * 3 + leftGap * 2
}

fun flightSizes(width: WidthClass): FlightSizes {
    val s = if (width == WidthClass.Tv) 1.33f else 1f
    val card = when (width) {
        WidthClass.Compact -> DpSize(144.dp, 88.dp)
        WidthClass.Medium -> DpSize(176.dp, 112.dp)
        WidthClass.Expanded -> DpSize(232.dp, 144.dp)
        WidthClass.Tv -> DpSize(232.dp * s, 144.dp * s)
    }
    return FlightSizes(
        edge = 8.dp * s,
        gap = 8.dp * s,
        topBar = 44.dp * s,
        leftButton = 56.dp * s,
        leftGap = 12.dp * s,
        shutter = 56.dp * s,
        modeSwitch = 48.dp * s,
        zoom = 96.dp * s,
        rightButton = 48.dp * s,
        rightGap = 6.dp * s,
        gimbalPad = 76.dp * s,
        card = card,
        bottomRow = 44.dp * s,
        skills = DpSize(152.dp * s, 48.dp * s),
    )
}

/**
 * Where every flight-screen zone goes in a [width] × [height] window. Nothing intersects:
 * - the top bar spans the top edge;
 * - the left column centres in the space above the bottom-left card; on a window too short
 *   for both, the card moves to the right of the column instead;
 * - the right column centres in the space above the skills chip; the gimbal pad joins it
 *   when there is room, else sits beside it, inward;
 * - the bottom row runs between the card and the skills chip;
 * - the centre is what is left between the columns, under the top bar and above the card.
 */
@Immutable
data class FlightZones(
    val topBar: ZoneRect,
    val leftColumn: ZoneRect,
    val rightColumn: ZoneRect,
    val gimbalPad: ZoneRect?,
    val gimbalInColumn: Boolean,
    val card: ZoneRect,
    val bottomRow: ZoneRect,
    val skills: ZoneRect,
    val centre: ZoneRect,
    /** False when the column is too short even without the gimbal pad: zoom leaves it next (`right_column.drop_order`). */
    val zoomInColumn: Boolean = true,
) {
    /** Every zone with its name, for checks. */
    fun named(): List<Pair<String, ZoneRect>> = listOfNotNull(
        "top_bar" to topBar,
        "left_column" to leftColumn,
        "right_column" to rightColumn,
        gimbalPad?.takeIf { !gimbalInColumn }?.let { "gimbal_pad" to it },
        "card" to card,
        "bottom_row" to bottomRow,
        "skills" to skills,
        "centre" to centre,
    )
}

fun flightZones(width: Dp, height: Dp, sizes: FlightSizes, gimbal: Boolean): FlightZones {
    val e = sizes.edge
    val g = sizes.gap
    val topBar = ZoneRect(e, e, width - e * 2, sizes.topBar)
    val bodyTop = topBar.bottom + g
    val bodyBottom = height - e

    // Bottom-right: the skills chip.
    val skills = ZoneRect(width - e - sizes.skills.width, bodyBottom - sizes.skills.height, sizes.skills.width, sizes.skills.height)

    // Bottom-left: the card, never taller than a third of the body.
    val cardH = minOf(sizes.card.height, (bodyBottom - bodyTop) * 0.4f)
    val cardW = sizes.card.width * (cardH / sizes.card.height)
    val leftSpan = bodyBottom - cardH - g - bodyTop
    val cardBeside = leftSpan < sizes.leftColumnHeight
    val cardX = if (cardBeside) e + sizes.leftButton + g else e
    val card = ZoneRect(cardX, bodyBottom - cardH, cardW, cardH)
    val leftTop = bodyTop
    val leftBottom = if (cardBeside) bodyBottom else card.y - g
    val leftH = sizes.leftColumnHeight
    val leftColumn = ZoneRect(e, leftTop + ((leftBottom - leftTop - leftH) / 2).coerceAtLeast(0.dp), sizes.leftButton, minOf(leftH, leftBottom - leftTop))

    // Right: the camera column above the skills chip.
    val rightBottom = skills.y - g
    val spanR = rightBottom - bodyTop
    val withPad = sizes.rightColumnHeight + sizes.gimbalPad + sizes.rightGap
    val gimbalInColumn = gimbal && spanR >= withPad
    // Drop order (`right_column.drop_order`): the gimbal pad leaves the column first, then zoom.
    val withoutZoom = sizes.rightColumnHeight - sizes.zoom - sizes.rightGap
    val zoomInColumn = gimbalInColumn || spanR >= sizes.rightColumnHeight
    val rightH = minOf(if (gimbalInColumn) withPad else if (zoomInColumn) sizes.rightColumnHeight else withoutZoom, spanR)
    val rightW = if (gimbalInColumn) maxOf(sizes.shutter, sizes.gimbalPad) else sizes.shutter
    val rightColumn = ZoneRect(width - e - rightW, bodyTop + ((spanR - rightH) / 2).coerceAtLeast(0.dp), rightW, rightH)
    val gimbalPad = when {
        !gimbal -> null
        gimbalInColumn -> ZoneRect(rightColumn.x, rightColumn.bottom - sizes.rightButton - sizes.rightGap - sizes.gimbalPad, sizes.gimbalPad, sizes.gimbalPad)
        else -> ZoneRect(rightColumn.x - g - sizes.gimbalPad, rightBottom - sizes.gimbalPad, sizes.gimbalPad, sizes.gimbalPad)
    }

    // Bottom row between the card and the skills chip.
    val rowX = card.right + g
    val bottomRow = ZoneRect(rowX, bodyBottom - sizes.bottomRow, (skills.x - g - rowX).coerceAtLeast(0.dp), sizes.bottomRow)

    // Centre: between the columns (and the beside-pad), above the card and the bottom row.
    val centreX = leftColumn.right + g
    val centreRight = (gimbalPad?.takeIf { !gimbalInColumn }?.x ?: rightColumn.x) - g
    val centreBottom = minOf(card.y, bottomRow.y) - g
    val centre = ZoneRect(centreX, bodyTop, (centreRight - centreX).coerceAtLeast(0.dp), (centreBottom - bodyTop).coerceAtLeast(0.dp))
    return FlightZones(topBar, leftColumn, rightColumn, gimbalPad, gimbalInColumn, card, bottomRow, skills, centre, zoomInColumn)
}

/** One zone's content, placed at its rectangle. */
@Composable
fun BoxScope.Zone(rect: ZoneRect, tag: String, z: Float, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.absoluteOffset(rect.x, rect.y).size(rect.width, rect.height).zIndex(z).testTag(tag), content = content)
}

/**
 * The flight grid: [background] fills the window (video and layers), then each zone's
 * content at its rectangle. [centre] receives the centre rectangle for its own overlays.
 */
@Composable
fun FlightGrid(
    width: WidthClass,
    gimbal: Boolean,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.(FlightZones) -> Unit,
    topBar: @Composable BoxScope.() -> Unit,
    leftColumn: @Composable BoxScope.() -> Unit,
    rightColumn: @Composable BoxScope.(zones: FlightZones) -> Unit,
    gimbalPad: @Composable BoxScope.() -> Unit,
    card: @Composable BoxScope.() -> Unit,
    bottomRow: @Composable BoxScope.() -> Unit,
    skills: @Composable BoxScope.() -> Unit,
    centre: @Composable BoxScope.(FlightZones) -> Unit,
) {
    val sizes = remember(width) { flightSizes(width) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val zones = remember(maxWidth, maxHeight, sizes, gimbal) { flightZones(maxWidth, maxHeight, sizes, gimbal) }
        background(zones)
        centre(zones)
        Zone(zones.card, "zone-card", ZONE_Z) { card() }
        Zone(zones.bottomRow, "zone-bottom-row", ZONE_Z) { bottomRow() }
        Zone(zones.leftColumn, "zone-left-column", ZONE_Z) { leftColumn() }
        Zone(zones.rightColumn, "zone-right-column", ZONE_Z) { rightColumn(zones) }
        zones.gimbalPad?.takeIf { !zones.gimbalInColumn }?.let { Zone(it, "zone-gimbal-pad", ZONE_Z) { gimbalPad() } }
        Zone(zones.skills, "zone-skills", ZONE_Z) { skills() }
        Zone(zones.topBar, "zone-top-bar", TOP_Z) { topBar() }
    }
}

private const val ZONE_Z = 30f
private const val TOP_Z = 40f
