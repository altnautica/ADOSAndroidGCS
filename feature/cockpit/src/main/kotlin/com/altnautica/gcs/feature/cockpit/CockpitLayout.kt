package com.altnautica.gcs.feature.cockpit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** Named cockpit spacings, so each clearance is defined once. */
internal object CockpitLayout {
    private val SkillBarClearanceDefault = 112.dp
    private val SkillBarClearanceTv = 140.dp

    /** Space kept free above the bottom edge for the Skill Bar. */
    fun skillBarClearance(width: WidthClass): Dp = when (width) {
        WidthClass.Compact, WidthClass.Medium, WidthClass.Expanded -> SkillBarClearanceDefault
        WidthClass.Tv -> SkillBarClearanceTv
    }

    /** Space kept free below the safety band for the inset row (minimap, tabs, strip). */
    val ZoneTopClearance = 140.dp

    private val InsetMin = 160.dp
    private val InsetMax = 280.dp
    private val InsetMinTv = 200.dp
    private val InsetMaxTv = 360.dp
    private const val INSET_FRACTION = 0.26f
    const val INSET_ASPECT = 16f / 10f

    /** Minimap inset width for a cockpit [containerWidth] wide. */
    fun insetWidth(containerWidth: Dp, width: WidthClass): Dp = when (width) {
        WidthClass.Compact, WidthClass.Medium, WidthClass.Expanded ->
            (containerWidth * INSET_FRACTION).coerceIn(InsetMin, InsetMax)
        WidthClass.Tv -> (containerWidth * INSET_FRACTION).coerceIn(InsetMinTv, InsetMaxTv)
    }
}

/** Per-class sizes of the cockpit grid (layout.json `classes`). */
internal data class GridSizes(val column: Dp, val shutter: Dp, val card: Pair<Dp, Dp>?, val chipCard: Dp?, val scale: Float) {
    /** The left column's buttons: never under the 56 dp left-column touch target. */
    val leftButton: Dp get() = maxOf(column, LEFT_COLUMN_MIN * scale)

    /** Width of the right column: its widest button, the shutter. */
    val rightWidth: Dp get() = maxOf(column, shutter)
}

internal fun gridSizes(width: WidthClass): GridSizes = when (width) {
    WidthClass.Compact -> GridSizes(48.dp, 56.dp, null, 96.dp, 1f)
    WidthClass.Medium -> GridSizes(56.dp, 56.dp, 200.dp to 140.dp, null, 1f)
    WidthClass.Expanded -> GridSizes(56.dp, 56.dp, 280.dp to 180.dp, null, 1f)
    WidthClass.Tv -> GridSizes(56.dp * 1.33f, 56.dp * 1.33f, 280.dp * 1.33f to 180.dp * 1.33f, null, 1.33f)
}

/**
 * Where the stacked zones of the landscape grid go in a body [GridFit] was computed for, so
 * no zone covers another on a short window (a phone in landscape is expanded by width but
 * only about 360 dp tall):
 * - the left column centres in the space above the bottom-left card, and the card shrinks
 *   (keeping its aspect) until the column fits; below [MIN_CARD] it becomes the compact chip;
 * - the right column centres in the space above the skill picker chip; when the full column
 *   does not fit it drops the camera settings button (zoom's long-press opens it instead),
 *   and then its small buttons shrink to the 48 dp touch minimum.
 */
internal data class GridFit(
    /** The bottom-left card's size, or null when it shows as the square map chip. */
    val card: Pair<Dp, Dp>?,
    /** The map chip's side when [card] is null. */
    val chip: Dp,
    /** Space below the left column's centring box: the card plus its margins. */
    val leftBottom: Dp,
    /** Space below the right column's centring box: the picker chip plus its margins. */
    val rightBottom: Dp,
    val cameraSettingsInColumn: Boolean,
    /** The right column's small buttons (all but the shutter). */
    val rightButton: Dp,
    /**
     * The left column and the card cannot stack in this height: the card keeps its spec
     * size and sits beside the column (to its right) instead of shrinking to a chip.
     */
    val cardBesideColumn: Boolean = false,
) {
    /** Height of the bottom-left zone (card or chip). */
    val bottomLeftHeight: Dp get() = card?.second ?: chip
}

/** Margin between the grid's zones and the body's edges. */
internal val GridEdge = 8.dp
private val GridGap = 8.dp
private val LeftGap = 12.dp
private val RightGap = 8.dp
private val LEFT_COLUMN_MIN = 56.dp
private val MIN_CARD = 96.dp
private val MIN_CHIP = 48.dp

/** Height the skill picker chip takes in the bottom-right corner (two text lines at most). */
internal val PICKER_CHIP_HEIGHT = 56.dp

/** Height of the left column: take off or land, return home, pause. */
internal fun leftColumnHeight(sizes: GridSizes): Dp = sizes.leftButton * 3 + LeftGap * 2

/** Height of the right column: zoom in, shutter, record (when offered), zoom out, gimbal, camera settings. */
internal fun rightColumnHeight(sizes: GridSizes, record: Boolean, cameraSettings: Boolean, button: Dp = sizes.column): Dp {
    val buttons = 3 + (if (record) 1 else 0) + (if (cameraSettings) 1 else 0)
    val count = buttons + 1
    return button * buttons + sizes.shutter + RightGap * (count - 1)
}

/** Fits the landscape grid's stacked zones into a body [height] tall. */
internal fun gridFit(height: Dp, sizes: GridSizes, record: Boolean): GridFit {
    val leftRoom = height - GridEdge * 2 - GridGap - leftColumnHeight(sizes)
    val specCard = sizes.card
    var card: Pair<Dp, Dp>? = null
    var chip = sizes.chipCard ?: MIN_CARD
    var beside = false
    if (specCard != null && leftRoom >= MIN_CARD) {
        val h = minOf(specCard.second, leftRoom)
        card = specCard.first * (h / specCard.second) to h
    } else if (specCard != null) {
        // Too short to stack: the card keeps the class's spec size beside the column.
        val h = minOf(specCard.second, height - GridEdge * 2)
        card = specCard.first * (h / specCard.second) to h
        beside = true
    } else {
        chip = minOf(chip, leftRoom).coerceAtLeast(MIN_CHIP)
    }
    val leftBottom = if (beside) GridEdge else GridEdge + (card?.second ?: chip) + GridGap
    val rightBottom = GridEdge + PICKER_CHIP_HEIGHT * sizes.scale + GridGap
    val rightRoom = height - GridEdge - rightBottom
    val settings = rightColumnHeight(sizes, record, cameraSettings = true) <= rightRoom
    val button = if (settings || rightColumnHeight(sizes, record, cameraSettings = false) <= rightRoom) sizes.column else minOf(sizes.column, MIN_CHIP)
    return GridFit(card, chip, leftBottom, rightBottom, settings, button, beside)
}
