package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.plan.model.altitudeProfile
import kotlin.math.roundToInt

// ── Altitude profile ───────────────────────────────────────

@Composable
internal fun AltitudeProfile(state: PlannerUiState) {
    val colors = AdosTheme.colors
    val profile = state.plan.altitudeProfile()
    if (profile.size < 2) {
        Text(stringResource(R.string.plan_profile_empty), style = MaterialTheme.typography.bodySmall, color = colors.hudInk2)
        return
    }
    val maxAlt = profile.maxOf { it.altM }
    val minAlt = minOf(0f, profile.minOf { it.altM })
    val description = pluralStringResource(R.plurals.plan_profile_description, profile.size, profile.size, maxAlt.roundToInt(), profile.minOf { it.altM }.roundToInt())
    val selected = (state.selection as? Selection.Item)?.id
    val line = colors.accentPrimary
    val grid = colors.hudHair
    val mark = colors.fgPrimary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = description },
    ) {
        val total = profile.last().distanceM.coerceAtLeast(1.0)
        val span = (maxAlt - minAlt).coerceAtLeast(1f)
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(a: Float) = size.height - (a - minAlt) / span * (size.height - 8f) - 4f
        drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1f)
        val path = Path()
        profile.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.distanceM), y(p.altM)) else path.lineTo(x(p.distanceM), y(p.altM)) }
        drawPath(path, line, style = Stroke(width = 3f))
        profile.forEach { p ->
            drawCircle(if (p.itemId == selected) mark else line, radius = if (p.itemId == selected) 7f else 4f, center = Offset(x(p.distanceM), y(p.altM)))
        }
    }
}
