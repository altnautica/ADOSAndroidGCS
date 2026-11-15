package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.link.mission.PilotPlanView
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.skills.hud.ConfinedText
import com.altnautica.gcs.core.skills.hud.RouteCheckSeverity
import com.altnautica.gcs.core.skills.hud.SpecText

/** Test tag of the planner's place-class picker. */
const val PLACE_CLASS_PICKER_TAG = "plan-place-class"

/**
 * The obstacle margin [c] flies with, for the explain line: the pilot's own margin from its
 * route check while it flies [c], else the class default (unknown for a confined place, which
 * depends on the airframe's prop radius).
 */
internal fun placeClassMarginM(c: PlaceClass, pilot: PilotPlanView): Double? =
    pilot.routeCheck?.marginM?.takeIf { pilot.placeClass == c }
        ?: ConfinedText.effectiveMarginM(c, configuredM = null, propTipRadiusM = null)

/**
 * The plan's place class: Open / Confined space with the explain line, in the cockpit spec's
 * words. Saved with the plan; Start sets it on the vehicle's pilot.
 */
@Composable
internal fun PlaceClassSelector(state: PlannerUiState, actions: PlannerActions) {
    val spec = state.specText ?: return
    val text = ConfinedText(spec)
    val selected = state.plan.placeClass
    val options = PlaceClass.entries
    Column(Modifier.testTag(PLACE_CLASS_PICKER_TAG)) {
        Text(spec.text("flow.launch.place_class"), style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, c ->
                SegmentedButton(
                    selected = c == selected,
                    onClick = { actions.onPlaceClass(c) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(text.label(c)) },
                )
            }
        }
        Text(text.explain(selected, placeClassMarginM(selected, state.pilot)), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
    }
}

/** The pilot's route check of the plan on the Start sheet: its state line and where the tightest point is. */
@Composable
internal fun StartRouteCheck(routeCheck: RouteCheck, specText: SpecText) {
    val report = ConfinedText(specText).report(routeCheck) ?: return
    Column(Modifier.testTag("plan-start-route-check")) {
        StatusChip(
            level = when (report.severity) {
                RouteCheckSeverity.Ok -> StatusLevel.Success
                RouteCheckSeverity.Warn -> StatusLevel.Warning
                RouteCheckSeverity.Critical -> StatusLevel.Error
            },
            label = report.line,
        )
        report.pinchAt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary) }
    }
}
