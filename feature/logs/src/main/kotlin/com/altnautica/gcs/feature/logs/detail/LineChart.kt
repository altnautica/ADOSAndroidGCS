package com.altnautica.gcs.feature.logs.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.logs.tlog.SeriesPoint
import java.util.Locale

/** Chart bounds with a little headroom so a flat line is not drawn on the frame edge. */
internal data class ChartBounds(val minT: Float, val maxT: Float, val minV: Float, val maxV: Float) {
    companion object {
        fun of(points: List<SeriesPoint>): ChartBounds? {
            if (points.isEmpty()) return null
            var lo = points.minOf { it.value }
            var hi = points.maxOf { it.value }
            if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
            val pad = (hi - lo) * 0.05f
            val t0 = points.first().tS
            val t1 = points.last().tS.coerceAtLeast(t0 + 1e-3f)
            return ChartBounds(t0, t1, lo - pad, hi + pad)
        }
    }
}

/** Single-series line chart drawn on a Canvas: min/max labels on the left, time across. */
@Composable
fun LineChart(
    title: String,
    points: List<SeriesPoint>,
    color: Color,
    noDataLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    val bounds = remember(points) { ChartBounds.of(points) }
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            if (bounds == null) {
                Text(noDataLabel, style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                return@Box
            }
            val summary = String.format(Locale.ROOT, "%.1f – %.1f", points.minOf { it.value }, points.maxOf { it.value })
            Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "$title $summary" }) {
                val grid = colors.borderDefault
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = (p.tS - bounds.minT) / (bounds.maxT - bounds.minT) * size.width
                    val y = size.height - (p.value - bounds.minV) / (bounds.maxV - bounds.minV) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgTertiary,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}
