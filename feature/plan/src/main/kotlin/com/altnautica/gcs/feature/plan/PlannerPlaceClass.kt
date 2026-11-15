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
import com.altnautica.gcs.core.link.mission.VehiclePilotSource
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.RouteCheckState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/** How long Start reads the pilot back for the class it set, and how often. */
internal const val PLACE_CLASS_READ_BACK_MS = 3_000L
internal const val PLACE_CLASS_POLL_MS = 250L

/**
 * Sets [placeClass] on the pilot of [nodeId] and reads it back: the error that stops the start
 * (null to go on) and the last view read. An absent or stale pilot stops it before anything is
 * sent, with its own reason. After an accepted set the pilot is read until a fresh frame newer
 * than the one before the set reports the class (and, for a confined class, its route check of
 * the mission), for at most [PLACE_CLASS_READ_BACK_MS]; a confined plan then needs that route
 * check, and one that reads infeasible stops the start.
 */
internal suspend fun setPlaceClassChecked(source: VehiclePilotSource, nodeId: DeviceId, placeClass: PlaceClass): Pair<StartError?, PilotPlanView> {
    val before = source.read(nodeId)
    val unready = when {
        before.placeClass == null -> "no_pilot"
        !before.fresh -> "pilot_stale"
        else -> null
    }
    if (unready != null) return StartError.PlaceClassRefused(PilotActionResult.Refused(unready)) to before
    val result = source.setPlaceClass(nodeId, placeClass)
    if (result != PilotActionResult.Accepted) return StartError.PlaceClassRefused(result) to before
    val beforeTs = before.tsMs
    fun settled(v: PilotPlanView): Boolean {
        val newer = beforeTs == null || (v.tsMs ?: Long.MIN_VALUE) > beforeTs
        return v.fresh && newer && v.placeClass == placeClass && (placeClass != PlaceClass.Confined || v.routeCheck != null)
    }
    var now = source.read(nodeId)
    withTimeoutOrNull(PLACE_CLASS_READ_BACK_MS) {
        while (!settled(now)) {
            delay(PLACE_CLASS_POLL_MS)
            now = source.read(nodeId)
        }
    }
    val error = when {
        now.placeClass != placeClass -> StartError.PlaceClassNotSet(now.placeClass)
        placeClass != PlaceClass.Confined -> null
        now.routeCheck == null -> StartError.RouteNotChecked
        now.routeCheck?.state == RouteCheckState.Infeasible -> StartError.RouteInfeasible
        else -> null
    }
    return error to now
}

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
        // A plan from the vehicle with its class unset flies the pilot's: explain that one.
        (selected ?: state.pilot.placeClass)?.let { c ->
            Text(text.explain(c, placeClassMarginM(c, state.pilot)), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        }
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
