@file:OptIn(ExperimentalMaterial3Api::class)

package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.RallyPoint
import kotlin.math.roundToInt

@Composable
private fun InclusionSelector(inclusion: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(true, false)
    Column {
        Text(stringResource(R.string.plan_fence_type), style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == inclusion,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(stringResource(if (value) R.string.plan_fence_inclusion else R.string.plan_fence_exclusion)) },
                )
            }
        }
    }
}

@Composable
internal fun FenceToolPanel(state: PlannerUiState, actions: PlannerActions) {
    SectionHeader(stringResource(R.string.plan_fence_title))
    InclusionSelector(state.fenceDraftInclusion, actions.onFenceDraftInclusion)
    if (state.tool == EditTool.FencePolygon) {
        Text(
            pluralStringResource(R.plurals.plan_fence_corners, state.fenceDraft.size, state.fenceDraft.size),
            style = MaterialTheme.typography.bodyMedium,
            color = AdosTheme.colors.hudInk,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(stringResource(R.string.plan_fence_close), onClick = actions.onCloseFence, enabled = state.fenceDraft.size >= 3)
            AdosButton(stringResource(R.string.plan_fence_discard), onClick = actions.onDiscardFence, variant = AdosButtonVariant.Secondary, enabled = state.fenceDraft.isNotEmpty())
        }
    }
    state.plan.fence.forEach { zone ->
        val label = when (zone) {
            is FenceZone.Polygon -> stringResource(
                R.string.plan_fence_polygon_summary,
                pluralStringResource(R.plurals.plan_fence_corners, zone.points.size, zone.points.size),
            )
            is FenceZone.Circle -> stringResource(R.string.plan_fence_circle_summary)
        }
        val kind = stringResource(if (zone.inclusion) R.string.plan_fence_inclusion else R.string.plan_fence_exclusion)
        AdosButton(stringResource(R.string.plan_pair_label, label, kind), onClick = { actions.onSelect(Selection.Fence(zone.id)) }, variant = AdosButtonVariant.Secondary)
    }
}

@Composable
internal fun FenceEditor(zone: FenceZone, actions: PlannerActions) {
    EditorHeader(stringResource(R.string.plan_fence_title)) { actions.onSelect(null) }
    InclusionSelector(zone.inclusion) { if (it != zone.inclusion) actions.onToggleFenceInclusion(zone.id) }
    if (zone is FenceZone.Circle) {
        NumberSettingRow(
            title = stringResource(R.string.plan_fence_radius),
            value = zone.radiusM.roundToInt(),
            onValueChange = { actions.onFenceRadius(zone.id, it.toDouble()) },
            range = PlannerViewModel.MIN_FENCE_RADIUS_M.toInt()..50_000,
            step = 10,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = metresFormatter(),
        )
    }
    AdosButton(stringResource(R.string.plan_fence_delete), onClick = { actions.onDeleteFence(zone.id) }, variant = AdosButtonVariant.Danger)
}

@Composable
internal fun RallyEditor(rally: RallyPoint, actions: PlannerActions) {
    EditorHeader(stringResource(R.string.plan_rally_title)) { actions.onSelect(null) }
    NumberSettingRow(
        title = stringResource(R.string.plan_rally_altitude),
        value = rally.altM.roundToInt(),
        onValueChange = { actions.onRallyAltitude(rally.id, it.toFloat()) },
        range = 0..1000,
        step = 5,
        decrementDescription = stringResource(R.string.plan_decrease),
        incrementDescription = stringResource(R.string.plan_increase),
        format = metresFormatter(),
    )
    AdosButton(stringResource(R.string.plan_rally_delete), onClick = { actions.onDeleteRally(rally.id) }, variant = AdosButtonVariant.Danger)
}
