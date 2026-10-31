package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.GlassSurface
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.plan.model.ProfilePoint
import com.altnautica.gcs.feature.plan.model.altitudeProfile
import kotlin.math.roundToInt

private val CollapsedHeight = 56.dp
private val ExpandedHeight = 160.dp

/**
 * Altitude profile over the bottom of the map: a sparkline that expands to a taller
 * chart with the chevron. Hidden until the plan has two positional items.
 */
@Composable
internal fun AltitudeProfileOverlay(state: PlannerUiState, modifier: Modifier = Modifier) {
    val plan = state.displayPlan
    val profile = remember(plan) { plan.altitudeProfile() }
    if (profile.size < 2) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron = stringResource(if (expanded) R.string.plan_profile_collapse else R.string.plan_profile_expand)
    GlassSurface(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(start = AdosSpacing.S3)) {
        Row(Modifier.height(if (expanded) ExpandedHeight else CollapsedHeight), verticalAlignment = Alignment.CenterVertically) {
            AltitudeProfile(
                profile = profile,
                selectedId = (state.selection as? Selection.Item)?.id,
                modifier = Modifier.weight(1f).padding(vertical = AdosSpacing.S2).fillMaxWidth(),
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, contentDescription = chevron, tint = AdosTheme.colors.hudInk)
            }
        }
    }
}

/** Altitude against distance along the path; [selectedId] is drawn larger. Fills its bounds. */
@Composable
internal fun AltitudeProfile(profile: List<ProfilePoint>, selectedId: Long?, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    val maxAlt = profile.maxOf { it.altM }
    val minAlt = minOf(0f, profile.minOf { it.altM })
    val description = pluralStringResource(R.plurals.plan_profile_description, profile.size, profile.size, maxAlt.roundToInt(), profile.minOf { it.altM }.roundToInt())
    val line = colors.accentPrimary
    val grid = colors.hudHair
    val mark = colors.fgPrimary
    Canvas(modifier.semantics { contentDescription = description }) {
        val total = profile.last().distanceM.coerceAtLeast(1.0)
        val span = (maxAlt - minAlt).coerceAtLeast(1f)
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(a: Float) = size.height - (a - minAlt) / span * (size.height - 8f) - 4f
        drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1f)
        val path = Path()
        profile.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.distanceM), y(p.altM)) else path.lineTo(x(p.distanceM), y(p.altM)) }
        drawPath(path, line, style = Stroke(width = 3f))
        profile.forEach { p ->
            drawCircle(if (p.itemId == selectedId) mark else line, radius = if (p.itemId == selectedId) 7f else 4f, center = Offset(x(p.distanceM), y(p.altM)))
        }
    }
}
