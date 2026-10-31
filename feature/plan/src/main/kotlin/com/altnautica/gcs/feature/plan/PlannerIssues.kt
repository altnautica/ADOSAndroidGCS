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
    PlanIssueKind.NoLandOrRtlLast -> stringResource(R.string.plan_issue_no_end)
    PlanIssueKind.AltitudeAbove120mRel -> pluralStringResource(R.plurals.plan_issue_alt_high, count, count)
    PlanIssueKind.OutsideInclusionFence -> pluralStringResource(R.plurals.plan_issue_outside_fence, count, count)
    PlanIssueKind.InsideExclusionFence -> pluralStringResource(R.plurals.plan_issue_inside_exclusion, count, count)
}
