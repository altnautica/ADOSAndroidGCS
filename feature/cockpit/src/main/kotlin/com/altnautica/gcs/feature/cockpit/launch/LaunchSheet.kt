package com.altnautica.gcs.feature.cockpit.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.modes.Segments
import kotlin.math.cos

/** Hold that starts the launch sequence. */
const val LAUNCH_HOLD_MS = 1_500

/** Take-off altitude range of the launch sheet, metres. */
val LAUNCH_ALT_RANGE = 1f..120f

/** Default copter take-off altitude, metres. */
const val DEFAULT_LAUNCH_ALT_M = 3f

/** What runs once the vehicle reaches its take-off altitude. */
enum class LaunchAfter(val wire: String) { Hold("hold"), Skill("skill"), Mission("mission"), Goto("goto") }

/** What runs when a mission completes or a one-shot skill ends. */
enum class LaunchWhenDone(val wire: String?) { None(null), Land("land"), Rth("rth") }

/** A local NED point (metres) chosen on the map for the go-to option. */
data class NedPoint(val n: Float, val e: Float, val d: Float)

/** Metres per degree of latitude (and of longitude at the equator). */
private const val METRES_PER_DEG = 111_319.49

/**
 * The go-to point at [toLat]/[toLon] in the pilot's local NED frame, [altM] above its
 * origin: the local position ([fromN], [fromE]) of the reference at [fromLat]/[fromLon]
 * (the vehicle, or home at the origin) plus the map offset from it, flat-earth, which is
 * exact to centimetres over a launch's few hundred metres.
 */
fun launchGotoNed(fromLat: Double, fromLon: Double, fromN: Float, fromE: Float, toLat: Double, toLon: Double, altM: Float): NedPoint {
    val n = (toLat - fromLat) * METRES_PER_DEG
    val e = (toLon - fromLon) * METRES_PER_DEG * cos(Math.toRadians(fromLat))
    return NedPoint(fromN + n.toFloat(), fromE + e.toFloat(), -altM)
}

/** The launch sheet's choices. */
data class LaunchDraft(
    val altM: Float = DEFAULT_LAUNCH_ALT_M,
    val after: LaunchAfter = LaunchAfter.Hold,
    val skillId: String? = null,
    val whenDone: LaunchWhenDone = LaunchWhenDone.None,
)

/** Builds the `launch` action args: `{after, skill?, goto?, alt_m, when_done?}`. Null when a choice is incomplete. */
fun launchArgs(draft: LaunchDraft, skills: List<Skill>, goto: NedPoint?): Map<String, Any?>? {
    val args = LinkedHashMap<String, Any?>()
    args["after"] = draft.after.wire
    when (draft.after) {
        LaunchAfter.Skill -> {
            val skill = skills.firstOrNull { it.id == draft.skillId } ?: return null
            val src = skill.source as? SkillSource.Extension ?: return null
            args["skill"] = mapOf("plugin_id" to src.pluginId, "skill_id" to skill.id.removePrefix(src.pluginId + ":"))
        }
        LaunchAfter.Goto -> {
            val p = goto ?: return null
            args["goto"] = mapOf("n" to p.n, "e" to p.e, "d" to p.d)
        }
        LaunchAfter.Hold, LaunchAfter.Mission -> Unit
    }
    args["alt_m"] = draft.altM
    args["when_done"] = draft.whenDone.wire
    return args
}

/** What the sheet needs from the cockpit. */
data class LaunchUi(
    val draft: LaunchDraft,
    /** Behaviour skills the after-takeoff action can start. */
    val skills: List<Skill>,
    val mission: MissionState?,
    val gotoPoint: NedPoint?,
    val vehicleClass: PilotVehicleClass?,
    /** The sequence in progress; the sheet shows its step strip. */
    val sequence: LaunchSequence?,
)

/**
 * The Launch sheet: take-off altitude, the after-takeoff action (hold, a skill, start the
 * mission only when one is loaded, go to a map point once one is chosen) and the optional
 * when-done action. Holding "Hold to launch" sends the launch sequence; its progress shows
 * as a step strip.
 */
@Composable
fun LaunchSheet(
    ui: LaunchUi,
    onDraft: (LaunchDraft) -> Unit,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit,
    altRange: ClosedFloatingPointRange<Float> = LAUNCH_ALT_RANGE,
) {
    AdosSheet(onDismiss = onDismiss, title = specText("flow.launch.title")) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
            modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S6).testTag("launch-sheet"),
        ) {
            ui.sequence?.let { StepStrip(it) }
            val d = ui.draft
            Text(specText("flow.launch.alt_m") + " · " + "%.0f m".format(d.altM), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = d.altM,
                // Whole metres, so the wide spec range (`layout.json` `launch`) stays a single smooth slider.
                onValueChange = { onDraft(d.copy(altM = kotlin.math.round(it).coerceIn(altRange))) },
                valueRange = altRange,
                modifier = Modifier.testTag("launch-alt"),
            )
            Text(specText("flow.launch.after"), style = MaterialTheme.typography.labelLarge)
            val afters = buildList {
                add(LaunchAfter.Hold)
                if (ui.skills.isNotEmpty()) add(LaunchAfter.Skill)
                if (ui.mission == MissionState.Loaded) add(LaunchAfter.Mission)
                if (ui.gotoPoint != null) add(LaunchAfter.Goto)
            }
            Segments(
                values = afters.map { it.wire },
                labels = afters.map { specText("flow.launch.after.${it.wire}") },
                selected = d.after.wire,
                enabled = true,
                tag = "launch-after",
            ) { w -> onDraft(d.copy(after = LaunchAfter.entries.first { it.wire == w })) }
            if (ui.mission != MissionState.Loaded) {
                Text(specText("flow.launch.mission_needs_loaded"), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (ui.gotoPoint == null) {
                Text(stringResource(R.string.launch_pick_point_hint), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (d.after == LaunchAfter.Skill) {
                Segments(
                    values = ui.skills.map { it.id },
                    labels = ui.skills.map { it.labelKey },
                    selected = d.skillId,
                    enabled = true,
                    tag = "launch-skill",
                ) { id -> onDraft(d.copy(skillId = id)) }
                Text(specText("flow.launch.designate_first"), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(specText("flow.launch.when_done"), style = MaterialTheme.typography.labelLarge)
            Segments(
                values = LaunchWhenDone.entries.map { it.name },
                labels = LaunchWhenDone.entries.map { specText("flow.launch.when_done.${(it.wire ?: "none")}") },
                selected = d.whenDone.name,
                enabled = true,
                tag = "launch-when-done",
            ) { n -> onDraft(d.copy(whenDone = LaunchWhenDone.valueOf(n))) }
            if (ui.vehicleClass == PilotVehicleClass.FixedWing) {
                Text(specText("flow.plane_land_hint"), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            }
            val ready = d.after != LaunchAfter.Skill || d.skillId != null
            if (ready) {
                HoldToConfirmButton(
                    specText("flow.launch.hold_to_launch"),
                    LAUNCH_HOLD_MS,
                    onLaunch,
                    modifier = Modifier.fillMaxWidth().testTag("launch-go"),
                )
            }
        }
    }
}

/** The sequence's steps (checks, arm, take off, after, done), the current one highlighted. */
@Composable
fun StepStrip(sequence: LaunchSequence, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    val steps = LaunchStep.entries
    val at = steps.indexOf(sequence.step)
    Column(modifier.testTag("launch-steps"), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1), verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { i, step ->
                val label = if (step == LaunchStep.After) {
                    specText("flow.launch.step.after", "after" to specText("flow.launch.after.${sequence.after ?: "hold"}"))
                } else {
                    specText("flow.launch.step.${step.wire}")
                }
                val done = i < at || sequence.step == LaunchStep.Done
                val current = i == at && sequence.step != LaunchStep.Done
                Text(
                    (if (done) "✓ " else "") + label,
                    color = when {
                        current -> colors.accentForeground
                        done -> colors.fgPrimary
                        else -> colors.fgTertiary
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(if (current) colors.accentPrimary else colors.bgRaised, RoundedCornerShape(AdosRadius.Sm))
                        .padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
                )
            }
        }
        sequence.error?.let { code ->
            val reason = com.altnautica.gcs.feature.cockpit.skillbar.reasonText(code)
            Text(specText("flow.launch.refused", "reason" to reason), color = colors.statusError)
        }
    }
}
