package com.altnautica.gcs.feature.cockpit.modes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.skills.spec.ModeSheetSpec
import com.altnautica.gcs.core.skills.spec.SpecConfirmKind
import com.altnautica.gcs.feature.cockpit.R

/** What the Mode sheet shows: the pilot's current settings and what the link offers. */
data class ModeSheetUi(
    val profile: PilotProfile?,
    val controlMode: ControlMode?,
    val avoid: AvoidMode?,
    val owner: PilotOwner?,
    val vehicleClass: PilotVehicleClass?,
    /** The vehicle is flown through a ground station, so the ELRS lane exists. */
    val elrsAvailable: Boolean,
    /** A pilot channel is open; profile, control mode, avoidance and handover need it. */
    val pilotLinked: Boolean,
    /** Every firmware mode for the advanced section. */
    val fcModes: ModePickerUi,
)

/** What the Mode sheet asks for; each maps to one pilot action or the `set-mode` skill. */
interface ModeSheetActions {
    fun onProfile(profile: PilotProfile)
    fun onControlMode(mode: ControlMode)
    fun onAvoid(mode: AvoidMode)
    fun onHandToFc(mode: String)
    fun onTakeControl()
    fun onFcMode(name: String)
    fun onDismiss()
}

/** Control modes offered on this link: ELRS only where a ground station carries the vehicle. */
fun offeredControlModes(spec: ModeSheetSpec, elrsAvailable: Boolean): List<String> =
    spec.controlMode.values.filter { it != ControlMode.Elrs.wire || elrsAvailable }

/** Hold-to-FC modes for the airframe: planes loiter only. */
fun handToFcModes(spec: ModeSheetSpec, vehicleClass: PilotVehicleClass?): List<String> =
    if (vehicleClass == PilotVehicleClass.FixedWing && spec.handToFc.planeValues.isNotEmpty()) spec.handToFc.planeValues else spec.handToFc.values

/**
 * The Mode sheet opened from the flight-state chip: profiles, control mode (hold to switch,
 * with a line on what each mode means), avoidance (Off needs a hold), Hand to FC or Take
 * control, and every FC mode under Advanced. Place it in a full-size box.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeSheet(ui: ModeSheetUi, spec: ModeSheetSpec, actions: ModeSheetActions) {
    val colors = AdosTheme.colors
    AdosSheet(actions::onDismiss, title = stringResource(R.string.mode_sheet_title)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4),
        ) {
            if (!ui.pilotLinked) {
                Text(stringResource(R.string.mode_sheet_no_pilot), style = MaterialTheme.typography.bodyMedium, color = colors.statusWarning)
            }
            for (section in spec.sections) {
                when (section) {
                    "profile" -> Section(stringResource(R.string.mode_sheet_profile)) {
                        Segments(spec.profile.values, spec.profile.labels, ui.profile?.wire, ui.pilotLinked, TAG_PROFILE) { v ->
                            PilotProfile.of(v)?.let(actions::onProfile)
                        }
                    }
                    "control_mode" -> Section(stringResource(R.string.mode_sheet_control_mode)) {
                        ControlModeSection(ui, spec, actions)
                    }
                    "avoidance" -> Section(stringResource(R.string.mode_sheet_avoidance)) {
                        AvoidanceSection(ui, spec, actions)
                    }
                    "hand_to_fc" -> HandoverSection(ui, spec, actions)
                    "fc_modes" -> if (spec.fcModes.advanced) FcModesSection(ui, actions)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = AdosTheme.colors.fgSecondary)
        content()
    }
}

@Composable
private fun ControlModeSection(ui: ModeSheetUi, spec: ModeSheetSpec, actions: ModeSheetActions) {
    val values = offeredControlModes(spec, ui.elrsAvailable)
    val labels = values.map { v -> spec.controlMode.labels[spec.controlMode.values.indexOf(v)] }
    var pending by remember(ui.controlMode) { mutableStateOf<String?>(null) }
    val shown = pending ?: ui.controlMode?.wire
    Segments(values, labels, shown, ui.pilotLinked, TAG_CONTROL) { v -> pending = v.takeIf { it != ui.controlMode?.wire } }
    shown?.let { spec.controlMode.explain[it] }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary) }
    val target = pending ?: return
    val label = labels[values.indexOf(target)]
    val confirm = spec.controlMode.confirm
    ConfirmedChange(
        label = stringResource(R.string.mode_sheet_hold_switch, label),
        holdMs = if (confirm.kind == SpecConfirmKind.Tap) 0 else confirm.ms,
        tag = TAG_CONTROL_HOLD,
    ) {
        pending = null
        ControlMode.of(target)?.let(actions::onControlMode)
    }
}

@Composable
private fun AvoidanceSection(ui: ModeSheetUi, spec: ModeSheetSpec, actions: ModeSheetActions) {
    var pendingOff by remember(ui.avoid) { mutableStateOf(false) }
    val shown = if (pendingOff) AvoidMode.Off.wire else ui.avoid?.wire
    Segments(spec.avoidance.values, spec.avoidance.labels, shown, ui.pilotLinked, TAG_AVOID) { v ->
        val mode = AvoidMode.of(v) ?: return@Segments
        if (mode == AvoidMode.Off && spec.avoidance.confirmOff.kind != SpecConfirmKind.Tap) {
            pendingOff = ui.avoid != AvoidMode.Off
        } else {
            pendingOff = false
            actions.onAvoid(mode)
        }
    }
    if (pendingOff) {
        Text(stringResource(R.string.mode_sheet_avoid_off_warning), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.statusWarning)
        ConfirmedChange(stringResource(R.string.mode_sheet_hold_avoid_off), spec.avoidance.confirmOff.ms, TAG_AVOID_HOLD, danger = true) {
            pendingOff = false
            actions.onAvoid(AvoidMode.Off)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandoverSection(ui: ModeSheetUi, spec: ModeSheetSpec, actions: ModeSheetActions) {
    if (!ui.pilotLinked) return
    val fcFlies = ui.owner == PilotOwner.FcMode || ui.owner == PilotOwner.MissionFc
    if (fcFlies) {
        Section(stringResource(R.string.mode_sheet_take_control_title)) {
            AdosButton(
                stringResource(R.string.flight_chip_take_control),
                onClick = actions::onTakeControl,
                modifier = Modifier.fillMaxWidth().testTag(TAG_TAKE_CONTROL),
            )
        }
        return
    }
    Section(stringResource(R.string.mode_sheet_hand_to_fc)) {
        val values = handToFcModes(spec, ui.vehicleClass)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            values.forEach { v ->
                val label = spec.handToFc.labels.getOrNull(spec.handToFc.values.indexOf(v)) ?: v
                AdosButton(
                    label,
                    onClick = { actions.onHandToFc(v) },
                    variant = if (v == spec.handToFc.default) AdosButtonVariant.Primary else AdosButtonVariant.Secondary,
                    modifier = Modifier.testTag("$TAG_HAND_TO_FC:$v"),
                )
            }
        }
    }
}

@Composable
private fun FcModesSection(ui: ModeSheetUi, actions: ModeSheetActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        AdosButton(
            stringResource(if (open) R.string.mode_sheet_fc_modes_hide else R.string.mode_sheet_fc_modes_show),
            onClick = { open = !open },
            variant = AdosButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(TAG_FC_MODES),
        )
        if (open) {
            Text(stringResource(R.string.mode_sheet_fc_modes_note), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
            FcModeGrid(ui.fcModes, actions::onFcMode)
        }
    }
}

/** A hold-to-confirm for [holdMs]; a zero hold is a plain button. */
@Composable
private fun ConfirmedChange(label: String, holdMs: Int, tag: String, danger: Boolean = false, onConfirmed: () -> Unit) {
    if (holdMs <= 0) {
        AdosButton(label, onClick = onConfirmed, modifier = Modifier.fillMaxWidth().testTag(tag))
    } else {
        HoldToConfirmButton(label, holdMs, onConfirmed, modifier = Modifier.fillMaxWidth().testTag(tag), danger = danger)
    }
}

/** A row of selectable segments, the [selected] one outlined in the accent colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Segments(values: List<String>, labels: List<String>, selected: String?, enabled: Boolean, tag: String, onSelect: (String) -> Unit) {
    val colors = AdosTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        values.forEachIndexed { i, v ->
            val on = v == selected
            Surface(
                modifier = Modifier
                    .defaultMinSize(minWidth = 88.dp, minHeight = AdosTheme.touchMin)
                    .testTag("$tag:$v")
                    .semantics { this.selected = on }
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(v) },
                shape = RoundedCornerShape(AdosRadius.Lg),
                color = if (on) colors.accentPrimary.copy(alpha = 0.16f) else colors.bgRaised,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) colors.accentPrimary else colors.borderDefault),
            ) {
                Box(Modifier.padding(horizontal = AdosSpacing.S3), contentAlignment = Alignment.Center) {
                    Text(labels.getOrElse(i) { v }, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

const val TAG_PROFILE = "mode_sheet_profile"
const val TAG_CONTROL = "mode_sheet_control"
const val TAG_CONTROL_HOLD = "mode_sheet_control_hold"
const val TAG_AVOID = "mode_sheet_avoid"
const val TAG_AVOID_HOLD = "mode_sheet_avoid_hold"
const val TAG_TAKE_CONTROL = "mode_sheet_take_control"
const val TAG_HAND_TO_FC = "mode_sheet_hand_to_fc"
const val TAG_FC_MODES = "mode_sheet_fc_modes"
