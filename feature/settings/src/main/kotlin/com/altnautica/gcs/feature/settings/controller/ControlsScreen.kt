package com.altnautica.gcs.feature.settings.controller

import androidx.annotation.StringRes
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.feature.settings.R
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.controls.Gesture
import com.altnautica.gcs.core.skills.controls.PadKind
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec

/** What the controls screen asks for; the route maps each to [ControlsViewModel]. */
interface ControlsActions {
    fun openControl(control: String)
    fun closeControl()
    fun setAction(row: ControlBindingSpec, action: String)
    fun addBinding(control: String, gesture: String, action: String)
    fun save()
    fun discard()
    fun resetToDefault()
    fun setStickMode(mode: StickMode)
    fun setDeadzone(value: Float)
    fun startPress()
    fun endPress()
    fun pressedOnScreen(control: String)
    fun startCalibration()
    fun closeCalibration()
    fun clearCalibration()
    fun testRumble()
    fun testLightbar()
    fun toggleStickTest()
    fun exportFile()
    fun importFile()
    fun nextTourStep()
    fun finishTour()
    fun setOnScreenSticks(on: Boolean)
}

/** The gestures a control's sheet offers: triggers are analog, every other control tap / hold / double-tap. */
internal fun gesturesFor(control: String): List<String> =
    if (control == "l2" || control == "r2") listOf(Gesture.ANALOG, Gesture.TAP, Gesture.HOLD) else Gesture.EDITABLE

/** The short label each hotspot shows: its single-control tap (or analog) binding, else its first row. */
internal fun hotspotLabels(rows: List<ControlBindingSpec>, label: (String) -> String): Map<String, String> =
    rows.groupBy { it.control }.mapValues { (_, list) ->
        val primary = list.firstOrNull { it.with.isEmpty() && (it.gesture == Gesture.TAP || it.gesture == Gesture.ANALOG) } ?: list.first()
        label(primary.action)
    }

/** The operator-facing name of a controller control id (`dpad_up` → "D-pad up"); null for an unknown id. */
@StringRes
internal fun controlName(id: String): Int? = when (id) {
    "stick_l" -> R.string.controls_control_stick_l
    "stick_r" -> R.string.controls_control_stick_r
    "l1" -> R.string.controls_control_l1
    "r1" -> R.string.controls_control_r1
    "l2" -> R.string.controls_control_l2
    "r2" -> R.string.controls_control_r2
    "l3" -> R.string.controls_control_l3
    "r3" -> R.string.controls_control_r3
    "dpad_up" -> R.string.controls_control_dpad_up
    "dpad_down" -> R.string.controls_control_dpad_down
    "dpad_left" -> R.string.controls_control_dpad_left
    "dpad_right" -> R.string.controls_control_dpad_right
    "cross" -> R.string.controls_control_cross
    "circle" -> R.string.controls_control_circle
    "square" -> R.string.controls_control_square
    "triangle" -> R.string.controls_control_triangle
    "share" -> R.string.controls_control_share
    "options" -> R.string.controls_control_options
    "ps" -> R.string.controls_control_ps
    "touchpad" -> R.string.controls_control_touchpad
    "lightbar" -> R.string.controls_control_lightbar
    else -> null
}

@Composable
private fun controlNameText(id: String): String = controlName(id)?.let { stringResource(it) } ?: id

/**
 * Settings › Controls: the controller drawing with live input and a label per control,
 * the header (name, connection, battery, stick mode, profile), the binding sheet of the
 * tapped control, calibration, the hardware checks, reset, export and import, and the
 * first-connect tour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(
    state: ControlsUiState,
    input: State<PadSnapshot>,
    spec: CockpitSpecRepository,
    actions: ControlsActions,
    actionLabel: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val strings = spec.setupStrings
    fun t(key: String, vararg args: Pair<String, String>) = strings.format(key, args.toMap())
    val ds4 = spec.ds4
    val art = if (state.pad == PadKind.Ds4) spec.ds4Art else spec.genericArt
    val hotspots = if (state.pad == PadKind.Ds4) spec.ds4Hotspots else spec.genericHotspots
    val lockedControls = state.rows.filter { it.locked }.mapTo(HashSet()) { it.control }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(AdosSpacing.S4).testTag("controls-screen"),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Header(state, ::t, actions)
        padPromptKey(state.padPrompt)?.let { key ->
            Text(t(key), color = AdosTheme.colors.fgSecondary, modifier = Modifier.testTag("controls-pad-prompt"))
        }
        if (state.press != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Text(t("controls.press_a_control"), style = MaterialTheme.typography.titleSmall)
                AdosButton(stringResource(R.string.controls_cancel), onClick = actions::endPress, variant = AdosButtonVariant.Secondary)
            }
        }
        ControllerDrawing(
            art = art,
            hotspots = hotspots,
            pad = input,
            bitfield = ds4.stream.buttonsBitfield,
            triggerPressed = ds4.stream.triggerPressedThreshold,
            deadzone = state.deadzone,
            labels = hotspotLabels(state.rows, actionLabel),
            names = hotspots.hotspots.keys.associateWith { controlNameText(it) },
            conflicts = state.conflictControls,
            locked = lockedControls,
            pressedLabel = stringResource(R.string.controls_state_pressed),
            conflictLabel = stringResource(R.string.controls_state_conflict),
            onHotspot = { id -> if (state.press != null) actions.pressedOnScreen(id) else actions.openControl(id) },
            modifier = Modifier.widthIn(max = 800.dp).align(Alignment.CenterHorizontally),
        )
        if (state.conflicts.isNotEmpty()) {
            Text(t("controls.conflict"), color = AdosTheme.colors.statusError, modifier = Modifier.testTag("controls-conflict"))
        }
        if (state.keyConflicts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1), modifier = Modifier.testTag("controls-key-conflicts")) {
                Text(stringResource(R.string.controls_key_conflicts_title), style = MaterialTheme.typography.titleSmall)
                for (c in state.keyConflicts) {
                    val holder = when (c.holder) {
                        null -> stringResource(R.string.controls_key_holder_cockpit)
                        "" -> stringResource(R.string.controls_key_holder_builtin)
                        else -> c.holder
                    }
                    Text(stringResource(R.string.controls_key_conflict, c.skill, c.chord, holder), color = AdosTheme.colors.fgSecondary)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(t("controls.save"), onClick = actions::save, enabled = state.canSave, modifier = Modifier.testTag("controls-save"))
            if (state.dirty) AdosButton(stringResource(R.string.controls_cancel), onClick = actions::discard, variant = AdosButtonVariant.Secondary)
            AdosButton(t("controls.press_a_control"), onClick = actions::startPress, variant = AdosButtonVariant.Secondary, modifier = Modifier.testTag("controls-press"))
            AdosButton(t("controls.reset"), onClick = actions::resetToDefault, variant = AdosButtonVariant.Secondary, modifier = Modifier.testTag("controls-reset"))
            AdosButton(t("controls.export"), onClick = actions::exportFile, variant = AdosButtonVariant.Secondary, modifier = Modifier.testTag("controls-export"))
            AdosButton(t("controls.import"), onClick = actions::importFile, variant = AdosButtonVariant.Secondary, modifier = Modifier.testTag("controls-import"))
        }
        SticksCard(state, ::t, actions, ds4.conditioning.deadzoneMin, ds4.conditioning.deadzoneMax)
        TestRow(state, input, ::t, actions)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            Text(t("flow.on_screen_sticks"), modifier = Modifier.weight(1f))
            Switch(checked = state.onScreenSticks, onCheckedChange = actions::setOnScreenSticks, modifier = Modifier.testTag("controls-on-screen-sticks"))
        }
    }
    state.editing?.let { control ->
        BindingSheet(control, state, ::t, actions, actionLabel, spec)
    }
    state.tourStep?.let { step -> TourSheet(step, padPromptKey(state.padPrompt), ::t, actions) }
}

@Composable
private fun Header(state: ControlsUiState, t: (String) -> String, actions: ControlsActions) {
    val c = state.controller
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1), modifier = Modifier.testTag("controls-header")) {
        Text(c?.name ?: t("controls.title"), style = MaterialTheme.typography.titleMedium)
        val bits = listOfNotNull(
            when (state.connection) {
                PadConnection.Usb -> t("controls.connection.usb")
                PadConnection.Bluetooth -> t("controls.connection.bluetooth")
                PadConnection.Unknown -> null
            },
            c?.batteryPct?.let { "$it %" },
            t(if (state.profile.wire == "user") "controls.profile.user" else "controls.profile.ados_default"),
        )
        Text(bits.joinToString(" · "), color = AdosTheme.colors.fgSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            for (mode in StickMode.entries) {
                val on = mode == state.stickMode
                Text(
                    t(if (mode == StickMode.Mode1) "controls.mode1" else "controls.mode2"),
                    color = if (on) AdosTheme.colors.accentForeground else AdosTheme.colors.fgPrimary,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .background(if (on) AdosTheme.colors.accentPrimary else AdosTheme.colors.bgRaised, RoundedCornerShape(AdosRadius.Md))
                        .semantics { selected = on }
                        .clickable(role = Role.RadioButton) { actions.setStickMode(mode) }
                        .padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S3)
                        .testTag("controls-${mode.wire}"),
                )
            }
        }
    }
}

@Composable
private fun SticksCard(state: ControlsUiState, t: (String) -> String, actions: ControlsActions, min: Float, max: Float) {
    Column(
        Modifier.fillMaxWidth().background(AdosTheme.colors.bgSurface, RoundedCornerShape(AdosRadius.Lg)).padding(AdosSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Text(t("controls.calibrate.title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.calibrated) {
                StatusChip(StatusLevel.Success, stringResource(R.string.controls_calibrated), Modifier.testTag("controls-calibrated"))
            }
        }
        val cal = state.calibration
        when {
            cal == null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(t("controls.calibrate.title"), onClick = actions::startCalibration, enabled = state.controller != null, modifier = Modifier.testTag("controls-calibrate"))
                if (state.calibrated) {
                    AdosButton(
                        stringResource(R.string.controls_calibration_clear),
                        onClick = actions::clearCalibration,
                        variant = AdosButtonVariant.Secondary,
                        modifier = Modifier.testTag("controls-calibration-clear"),
                    )
                }
            }
            cal.phase == CalibrationPhase.Saved -> {
                Text(stringResource(R.string.controls_calibrated), modifier = Modifier.testTag("controls-calibration-saved"))
                AdosButton(stringResource(R.string.controls_calibration_done), onClick = actions::closeCalibration, modifier = Modifier.testTag("controls-calibration-done"))
            }
            else -> {
                Text(t("controls.calibrate.body"))
                androidx.compose.material3.LinearProgressIndicator(progress = { cal.coverage }, modifier = Modifier.fillMaxWidth())
                AdosButton(stringResource(R.string.controls_cancel), onClick = actions::closeCalibration, variant = AdosButtonVariant.Secondary)
            }
        }
        Text(t("controls.deadzone") + " · " + "%.2f".format(state.deadzone))
        Slider(value = state.deadzone, onValueChange = actions::setDeadzone, valueRange = min..max, modifier = Modifier.testTag("controls-deadzone"))
    }
}

@Composable
private fun TestRow(state: ControlsUiState, input: State<PadSnapshot>, t: (String) -> String, actions: ControlsActions) {
    val running = stringResource(R.string.controls_test_running)
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            AdosButton(
                if (state.test == HardwareTest.Rumble) running else t("controls.test.rumble"),
                onClick = actions::testRumble,
                enabled = state.controller != null && state.capabilities.rumble && state.test != HardwareTest.Rumble,
                variant = AdosButtonVariant.Secondary,
                modifier = Modifier.testTag("controls-test-rumble"),
            )
            Column {
                AdosButton(
                    if (state.test == HardwareTest.Lightbar) running else t("controls.test.lightbar"),
                    onClick = actions::testLightbar,
                    enabled = state.controller != null && state.capabilities.lightbar && state.test != HardwareTest.Lightbar,
                    variant = AdosButtonVariant.Secondary,
                    modifier = Modifier.testTag("controls-test-lightbar"),
                )
                if (!state.capabilities.lightbar) Text(t("controls.test.lightbar_unavailable"), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
            }
            val sticks = state.test == HardwareTest.Sticks
            AdosButton(
                if (sticks) stringResource(R.string.controls_test_sticks_stop) else t("controls.test.sticks"),
                onClick = actions::toggleStickTest,
                enabled = state.controller != null,
                variant = if (sticks) AdosButtonVariant.Primary else AdosButtonVariant.Secondary,
                modifier = Modifier.testTag("controls-test-sticks").semantics { selected = sticks },
            )
        }
        if (state.test == HardwareTest.Sticks) {
            val a = input.value.axes
            Text(
                stringResource(R.string.controls_test_readout, a.leftX, a.leftY, a.rightX, a.rightY, a.leftTrigger, a.rightTrigger),
                style = AdosTypography.numeric.small,
                modifier = Modifier.testTag("controls-stick-readout"),
            )
        }
    }
}

/** The tapped control's gestures, each with its action and a picker grouped Flight / Skills / Camera / View & layers / Profile / None. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BindingSheet(
    control: String,
    state: ControlsUiState,
    t: (String) -> String,
    actions: ControlsActions,
    actionLabel: (String) -> String,
    spec: CockpitSpecRepository,
) {
    AdosSheet(onDismiss = actions::closeControl, title = controlNameText(control)) {
        Column(Modifier.testTag("binding-sheet"), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            for (gesture in gesturesFor(control)) {
                val row = state.rows.firstOrNull { it.control == control && it.gesture == gesture && it.with.isEmpty() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    Text(t("controls.gesture.$gesture"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 96.dp))
                    if (row?.locked == true) Icon(Icons.Outlined.Lock, contentDescription = t("controls.locked"), modifier = Modifier.size(16.dp))
                    Text(row?.let { actionLabel(it.action) } ?: "—", modifier = Modifier.testTag("binding-$gesture"))
                }
                val groups = spec.ds4.actionGroups.map { group ->
                    group to when (group) {
                        "skills" -> state.skillGroups.flatMap { g -> g.skills.map { it.action to "${g.pluginName}: ${it.label}" } }
                        else -> spec.ds4.actions.filter { it.group == group }.map { it.id to it.label }
                    }
                }
                for ((group, options) in groups) {
                    if (options.isEmpty()) continue
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                        for ((id, label) in options) {
                            val chosen = row?.action == id
                            Box(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .background(if (chosen) AdosTheme.colors.accentPrimary else AdosTheme.colors.bgRaised, RoundedCornerShape(AdosRadius.Md))
                                    .semantics { selected = chosen }
                                    .clickable(role = Role.Button) { if (row != null) actions.setAction(row, id) else actions.addBinding(control, gesture, id) }
                                    .padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S3)
                                    .testTag("pick-$gesture-$id"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, color = if (chosen) AdosTheme.colors.accentForeground else AdosTheme.colors.fgPrimary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (state.rows.any { it.control == control && it.locked }) {
                Text(t("controls.locked"), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TourSheet(step: Int, prompt: String?, t: (String) -> String, actions: ControlsActions) {
    val key = TOUR_KEYS[step.coerceIn(0, TOUR_KEYS.lastIndex)]
    AdosSheet(onDismiss = actions::finishTour, title = t("controls.tour.$key.title")) {
        Column(Modifier.testTag("controls-tour"), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            Text(t("controls.tour.$key.body"))
            if (prompt != null) Text(t(prompt), color = AdosTheme.colors.fgSecondary)
            AdosButton(stringResource(if (step >= TOUR_KEYS.lastIndex) R.string.controls_tour_done else R.string.controls_tour_next), onClick = actions::nextTourStep, modifier = Modifier.fillMaxWidth())
        }
    }
}

private val TOUR_KEYS = listOf("sticks", "safety", "skills")

/** The spec string key for [prompt], or null when no prompt shows. */
private fun padPromptKey(prompt: PadPrompt): String? = when (prompt) {
    PadPrompt.None -> null
    PadPrompt.PressAnyButton -> "controls.press_any_button"
    PadPrompt.NoPad -> "controls.pad.none"
}
