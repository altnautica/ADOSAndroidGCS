package com.altnautica.gcs.feature.settings.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.altnautica.gcs.core.skills.spec.LabelSide

/** One label to place: its hotspot's leader-line anchor in viewBox units (relative to the viewBox origin), its side, and its measured size in px. */
internal data class LabelRequest(val id: String, val side: LabelSide, val anchor: Offset, val width: Float, val height: Float)

/** A placed label and its leader line, from the anchor on the control to the label's near edge. */
internal data class PlacedLabel(val rect: Rect, val leaderFrom: Offset, val leaderTo: Offset)

/** The drawing's frame: where the art sits, its scale, the total height and every placed label. */
internal data class ControllerLabelFrame(val art: Rect, val scale: Float, val height: Float, val labels: Map<String, PlacedLabel>)

/**
 * Lays the controller drawing out with its labels outside the art: left and right labels in
 * gutters beside it, top and bottom labels in bands above and below it. Each band is spread
 * along its axis so no two labels overlap, keeping the anchors' order so leader lines never
 * cross; a top or bottom band that cannot fit in one row splits into two alternating rows.
 */
internal fun layoutControllerLabels(
    requests: List<LabelRequest>,
    totalWidth: Float,
    gutter: Float,
    viewBoxWidth: Float,
    viewBoxHeight: Float,
    gap: Float,
    spacing: Float,
): ControllerLabelFrame {
    val artWidth = (totalWidth - 2 * gutter).coerceAtLeast(1f)
    val scale = artWidth / viewBoxWidth
    val artHeight = viewBoxHeight * scale
    val bySide = requests.groupBy { it.side }
    val topRows = bandRows(bySide[LabelSide.Top].orEmpty(), totalWidth, spacing)
    val bottomRows = bandRows(bySide[LabelSide.Bottom].orEmpty(), totalWidth, spacing)
    val topBand = bandHeight(topRows, gap, spacing)
    val art = Rect(gutter, topBand, gutter + artWidth, topBand + artHeight)
    val height = art.bottom + bandHeight(bottomRows, gap, spacing)
    fun anchorPx(r: LabelRequest) = Offset(art.left + r.anchor.x * scale, art.top + r.anchor.y * scale)
    val placed = LinkedHashMap<String, PlacedLabel>()

    var rowEdge = art.top - gap
    for (row in topRows) {
        val xs = spread(row.map { anchorPx(it).x }, row.map { it.width }, 0f, totalWidth, spacing)
        row.forEachIndexed { i, r ->
            val rect = Rect(xs[i], rowEdge - r.height, xs[i] + r.width, rowEdge)
            placed[r.id] = PlacedLabel(rect, anchorPx(r), Offset(rect.center.x, rect.bottom))
        }
        rowEdge -= (row.maxOf { it.height } + spacing)
    }
    rowEdge = art.bottom + gap
    for (row in bottomRows) {
        val xs = spread(row.map { anchorPx(it).x }, row.map { it.width }, 0f, totalWidth, spacing)
        row.forEachIndexed { i, r ->
            val rect = Rect(xs[i], rowEdge, xs[i] + r.width, rowEdge + r.height)
            placed[r.id] = PlacedLabel(rect, anchorPx(r), Offset(rect.center.x, rect.top))
        }
        rowEdge += row.maxOf { it.height } + spacing
    }
    for (side in listOf(LabelSide.Left, LabelSide.Right)) {
        val column = bySide[side].orEmpty().sortedBy { it.anchor.y }
        if (column.isEmpty()) continue
        val heights = column.map { it.height }
        val fitsBesideArt = heights.sum() + spacing * (column.size - 1) <= art.height
        val (lo, hi) = if (fitsBesideArt) art.top to art.bottom else 0f to height
        val ys = spread(column.map { anchorPx(it).y }, heights, lo, hi, spacing)
        column.forEachIndexed { i, r ->
            val rect = if (side == LabelSide.Left) {
                Rect(art.left - gap - r.width, ys[i], art.left - gap, ys[i] + r.height)
            } else {
                Rect(art.right + gap, ys[i], art.right + gap + r.width, ys[i] + r.height)
            }
            val to = if (side == LabelSide.Left) Offset(rect.right, rect.center.y) else Offset(rect.left, rect.center.y)
            placed[r.id] = PlacedLabel(rect, anchorPx(r), to)
        }
    }
    return ControllerLabelFrame(art, scale, height, placed)
}

/** One row when the band's labels fit across [width], otherwise two rows taking alternate labels (nearest row first). */
private fun bandRows(band: List<LabelRequest>, width: Float, spacing: Float): List<List<LabelRequest>> {
    if (band.isEmpty()) return emptyList()
    val sorted = band.sortedBy { it.anchor.x }
    val needed = sorted.sumOf { it.width.toDouble() } + spacing * (sorted.size - 1)
    if (needed <= width) return listOf(sorted)
    return listOf(sorted.filterIndexed { i, _ -> i % 2 == 0 }, sorted.filterIndexed { i, _ -> i % 2 == 1 })
}

private fun bandHeight(rows: List<List<LabelRequest>>, gap: Float, spacing: Float): Float =
    if (rows.isEmpty()) 0f else gap + rows.sumOf { row -> row.maxOf { it.height }.toDouble() }.toFloat() + spacing * (rows.size - 1)

/**
 * Starts of items with sizes [sizes] wanting to centre on [centers] (sorted ascending),
 * pushed apart so consecutive items keep [spacing] and clamped to [lo]..[hi] where they fit.
 */
internal fun spread(centers: List<Float>, sizes: List<Float>, lo: Float, hi: Float, spacing: Float): List<Float> {
    val n = centers.size
    val start = FloatArray(n) { centers[it] - sizes[it] / 2f }
    for (i in 0 until n) {
        val min = if (i == 0) lo else start[i - 1] + sizes[i - 1] + spacing
        if (start[i] < min) start[i] = min
    }
    for (i in n - 1 downTo 0) {
        val max = if (i == n - 1) hi - sizes[i] else start[i + 1] - spacing - sizes[i]
        if (start[i] > max) start[i] = max
    }
    // A band wider than the space keeps its order from the low edge rather than overlapping.
    for (i in 0 until n) {
        val min = if (i == 0) lo else start[i - 1] + sizes[i - 1] + spacing
        if (start[i] < min) start[i] = min
    }
    return start.toList()
}
