package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.plan.model.PlanIssue
import com.altnautica.gcs.feature.plan.model.PlanIssueKind

/** Pre-upload findings, one row per kind: blocking ones as errors, the rest as warnings. */
@Composable
internal fun PlanIssueList(issues: List<PlanIssue>) {
    if (issues.isEmpty()) return
    val byKind = issues.groupingBy { it.kind }.eachCount()
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        Text(stringResource(R.string.plan_issues_title), style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
        PlanIssueKind.entries.filter { it in byKind }.forEach { kind ->
            StatusChip(
                level = if (kind.blocking) StatusLevel.Error else StatusLevel.Warning,
                label = issueLabel(kind, byKind.getValue(kind)),
            )
        }
    }
}

@Composable
private fun issueLabel(kind: PlanIssueKind, count: Int): String = when (kind) {
    PlanIssueKind.Empty -> stringResource(R.string.plan_issue_empty)
    PlanIssueKind.NoTakeoffFirst -> stringResource(R.string.plan_issue_no_takeoff)
    PlanIssueKind.MissingTakeoff -> stringResource(R.string.plan_issue_missing_takeoff)
    PlanIssueKind.AltitudeAtOrBelowHome -> pluralStringResource(R.plurals.plan_issue_alt_low, count, count)
    PlanIssueKind.InvalidCoordinates -> pluralStringResource(R.plurals.plan_issue_bad_coordinates, count, count)
    PlanIssueKind.LegCrossesFence -> pluralStringResource(R.plurals.plan_issue_leg_fence, count, count)
    PlanIssueKind.OutsideHomeCircleFence -> pluralStringResource(R.plurals.plan_issue_outside_home_circle, count, count)
    PlanIssueKind.AboveFenceCeiling -> pluralStringResource(R.plurals.plan_issue_above_ceiling, count, count)
    PlanIssueKind.BelowFenceFloor -> pluralStringResource(R.plurals.plan_issue_below_floor, count, count)
    PlanIssueKind.InvalidJump -> pluralStringResource(R.plurals.plan_issue_bad_jump, count, count)
    PlanIssueKind.NoLandOrRtlLast -> stringResource(R.string.plan_issue_no_end)
    PlanIssueKind.AltitudeAbove120mRel -> pluralStringResource(R.plurals.plan_issue_alt_high, count, count)
    PlanIssueKind.OutsideInclusionFence -> pluralStringResource(R.plurals.plan_issue_outside_fence, count, count)
    PlanIssueKind.InsideExclusionFence -> pluralStringResource(R.plurals.plan_issue_inside_exclusion, count, count)
}
