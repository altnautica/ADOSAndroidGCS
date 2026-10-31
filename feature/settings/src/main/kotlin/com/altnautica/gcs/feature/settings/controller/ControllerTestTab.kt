package com.altnautica.gcs.feature.settings.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.ControllerGlyph
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.settings.R
import kotlinx.coroutines.flow.StateFlow

private val StickBox = 88.dp
private val TriggerSize = 88.dp

/** Live input test: every button lights while pressed, stick dots, trigger bars and raw axis ranges. */
@Composable
internal fun ControllerTestTab(
    state: ControllerUiState,
    buttons: StateFlow<Int>,
    axes: StateFlow<GamepadAxes>,
    onActive: (Boolean) -> Unit,
    onTestRumble: () -> Unit,
    onCycleLightbar: () -> Unit,
    onDone: () -> Unit,
    padding: PaddingValues,
) {
    val active by rememberUpdatedState(onActive)
    DisposableEffect(Unit) {
        active(true)
        onDispose { active(false) }
    }
    // A single back press only lights the diagram; holding it (or Done) leaves.
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true, onBackCompleted = {})
    // Tab, menu and detail buttons are under test too; the Quick menu and Home still work.
    ConsoleCommandHandler { command -> command != ConsoleCommand.QuickMenu && command != ConsoleCommand.Home }

    val pressed by buttons.collectAsStateWithLifecycle()
    val sticks by axes.collectAsStateWithLifecycle()
    val firstAction = remember { FocusRequester() }
    // With a pad in hand, focus starts on the rumble check so a confirm press tests it, never Done.
    val controllerActive = LocalControllerUi.current.active
    LaunchedEffect(firstAction, controllerActive) { if (controllerActive) runCatching { firstAction.requestFocus() } }

    val diagram: @Composable () -> Unit = { Diagram(pressed, sticks, connected = state.controller != null) }
    val details: LazyListScope.() -> Unit = { detailItems(state, onTestRumble, onCycleLightbar, onDone, firstAction) }
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(AdosSpacing.S4)) { diagram() }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = AdosSpacing.S8), content = details)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = AdosSpacing.S8)) {
            item(key = "diagram") { Box(Modifier.padding(AdosSpacing.S4)) { diagram() } }
            details()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Diagram(pressed: Int, axes: GamepadAxes, connected: Boolean) {
    val colors = AdosTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4)) {
        if (!connected) {
            Text(stringResource(R.string.settings_controller_test_no_controller), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            for (button in 0..GamepadButton.HOME) {
                MapInputGlyph(mapInputFor(button), lit = pressed and (1 shl button) != 0, size = 28.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S4), verticalAlignment = Alignment.Bottom) {
            Trigger(R.string.settings_controller_test_left_trigger, axes.leftTrigger)
            Stick(R.string.settings_controller_input_left_stick, axes.leftX, axes.leftY)
            Stick(R.string.settings_controller_input_right_stick, axes.rightX, axes.rightY)
            Trigger(R.string.settings_controller_test_right_trigger, axes.rightTrigger)
        }
    }
}

@Composable
private fun Stick(label: Int, x: Float, y: Float) {
    val colors = AdosTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        Canvas(Modifier.size(StickBox).border(1.dp, colors.borderStrong, RoundedCornerShape(StickBox / 2))) {
            val r = size.minDimension / 2
            drawLine(colors.borderDefault, Offset(r, 0f), Offset(r, size.height))
            drawLine(colors.borderDefault, Offset(0f, r), Offset(size.width, r))
            // Y is positive with the stick pushed up; the canvas grows downwards.
            drawCircle(colors.accentPrimary, radius = r * 0.16f, center = Offset(r + x * r * 0.84f, r - y * r * 0.84f))
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
        Text(stringResource(R.string.settings_controller_test_xy, x, y), style = AdosTypography.numeric.small, color = colors.fgSecondary)
    }
}

@Composable
private fun Trigger(label: Int, value: Float) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        Box(Modifier.width(20.dp).height(TriggerSize).border(1.dp, colors.borderStrong, shape), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(colors.accentPrimary, shape))
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
        Text(stringResource(R.string.settings_controller_test_value, value), style = AdosTypography.numeric.small, color = colors.fgSecondary)
    }
}

private fun LazyListScope.detailItems(
    state: ControllerUiState,
    onTestRumble: () -> Unit,
    onCycleLightbar: () -> Unit,
    onDone: () -> Unit,
    firstAction: FocusRequester,
) {
    val connected = state.controller != null
    item(key = "leave") {
        Row(
            Modifier.fillMaxWidth().padding(AdosSpacing.S4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            ControllerGlyph(ConsoleButton.Back)
            Text(stringResource(R.string.settings_controller_test_hold_leave), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary, modifier = Modifier.weight(1f))
            AdosButton(stringResource(R.string.settings_controller_test_done), onClick = onDone, variant = AdosButtonVariant.Secondary)
        }
    }
    item(key = "hardware") { SectionHeader(stringResource(R.string.settings_controller_test_hardware)) }
    item(key = "rumble") {
        HardwareAction(
            label = stringResource(R.string.settings_controller_test_rumble),
            supported = state.capabilities.rumble,
            onClick = onTestRumble,
            modifier = Modifier.focusRequester(firstAction),
            connected = connected,
        )
    }
    item(key = "lightbar") {
        HardwareAction(stringResource(R.string.settings_controller_test_lightbar), state.capabilities.lightbar, onCycleLightbar, connected = connected)
    }
    item(key = "ranges") { SectionHeader(stringResource(R.string.settings_controller_test_ranges)) }
    if (state.ranges.isEmpty()) {
        item(key = "ranges-none") {
            Text(
                stringResource(R.string.settings_controller_test_ranges_none),
                style = MaterialTheme.typography.bodyMedium,
                color = AdosTheme.colors.fgSecondary,
                modifier = Modifier.padding(horizontal = AdosSpacing.S4),
            )
        }
    }
    items(state.ranges, key = { "range-${it.name}" }) { range ->
        Row(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1)) {
            Text(range.name, style = AdosTypography.numeric.small, color = AdosTheme.colors.fgPrimary, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_controller_test_range, range.min, range.max, range.flat),
                style = AdosTypography.numeric.small,
                color = AdosTheme.colors.fgSecondary,
            )
        }
    }
}

/** A hardware check button, or the reason it is unavailable. */
@Composable
private fun HardwareAction(label: String, supported: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, connected: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        AdosButton(label, onClick = onClick, enabled = supported, variant = AdosButtonVariant.Secondary, modifier = modifier)
        if (connected && !supported) {
            Text(stringResource(R.string.settings_controller_test_unsupported), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        }
    }
}
