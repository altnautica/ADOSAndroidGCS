package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.altnautica.gcs.core.designsystem.R
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L
private const val FAST_AFTER_MS = 1500L
private const val FAST_MULTIPLIER = 5

/**
 * Integer stepper; [format] renders the value (for units). The row is one focus
 * stop: focused D-pad Left/Right steps by [step] (passing through at a bound so
 * focus can still move), and confirm opens typed entry. Holding a ± button or a
 * D-pad direction repeats after 400 ms every 80 ms, at 5 × [step] after 1.5 s.
 * Tapping the value opens typed entry, checked against [range].
 */
@Composable
fun NumberSettingRow(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    decrementDescription: String,
    incrementDescription: String,
    modifier: Modifier = Modifier,
    step: Int = 1,
    summary: String? = null,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
) {
    val colors = AdosTheme.colors
    val scope = rememberCoroutineScope()
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    var editing by rememberSaveable { mutableStateOf(false) }
    var keyRepeat by remember { mutableStateOf<Job?>(null) }

    /** Applies [delta] steps from the current value; false at a bound. */
    fun stepBy(delta: Int): Boolean {
        val next = (currentValue + delta).coerceIn(range)
        if (next == currentValue) return false
        currentOnChange(next)
        return true
    }

    SettingRowLayout(
        title = title,
        summary = summary,
        enabled = enabled,
        modifier = modifier
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                when {
                    direction != 0 && event.type == KeyEventType.KeyDown -> {
                        // Auto-repeat downs are absorbed; the repeat job does the stepping.
                        if (event.nativeKeyEvent.repeatCount > 0) return@onKeyEvent keyRepeat?.isActive == true
                        if (!stepBy(direction * step)) return@onKeyEvent false
                        keyRepeat?.cancel()
                        keyRepeat = scope.launchRepeat(step * direction, ::stepBy)
                        true
                    }
                    direction != 0 && event.type == KeyEventType.KeyUp -> {
                        val held = keyRepeat
                        keyRepeat = null
                        held?.cancel()
                        held != null
                    }
                    isConfirmKey(event.key) && event.type == KeyEventType.KeyUp -> {
                        editing = true
                        true
                    }
                    isConfirmKey(event.key) -> true
                    else -> false
                }
            }
            .focusable(enabled),
    ) {
        StepButton(
            enabled = enabled && value > range.first,
            description = decrementDescription,
            onStep = { stepBy(-step) },
            delta = -step,
            stepBy = ::stepBy,
        ) {
            Icon(Icons.Outlined.Remove, contentDescription = null, tint = colors.fgPrimary, modifier = Modifier.size(AdosSize.IconLg))
        }
        Text(
            format(value),
            modifier = Modifier
                .widthIn(min = AdosTheme.touchMin)
                .focusProperties { canFocus = false }
                .clickable(enabled = enabled) { editing = true },
            style = AdosTypography.numeric.medium,
            color = colors.fgPrimary,
            textAlign = TextAlign.Center,
        )
        StepButton(
            enabled = enabled && value < range.last,
            description = incrementDescription,
            onStep = { stepBy(step) },
            delta = step,
            stepBy = ::stepBy,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = colors.fgPrimary, modifier = Modifier.size(AdosSize.IconLg))
        }
    }
    if (editing) {
        NumberEntryDialog(title = title, value = value, range = range, onDismiss = { editing = false }) {
            editing = false
            onValueChange(it)
        }
    }
}

/** Steps once, then repeats while held; false from [stepBy] (a bound) ends the repeat. */
private fun CoroutineScope.launchRepeat(delta: Int, stepBy: (Int) -> Boolean): Job = launch {
    delay(REPEAT_DELAY_MS)
    var elapsed = REPEAT_DELAY_MS
    while (true) {
        val amount = if (elapsed >= FAST_AFTER_MS) delta * FAST_MULTIPLIER else delta
        if (!stepBy(amount)) break
        delay(REPEAT_INTERVAL_MS)
        elapsed += REPEAT_INTERVAL_MS
    }
}

/** A ± touch target: press steps once and repeats while held. Not a focus stop; the row is. */
@Composable
private fun StepButton(
    enabled: Boolean,
    description: String,
    onStep: () -> Unit,
    delta: Int,
    stepBy: (Int) -> Boolean,
    icon: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    Box(
        Modifier
            .size(AdosTheme.touchMin)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (enabled) {
                    onClick {
                        onStep()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(delta) {
                detectTapGestures(onPress = {
                    if (!currentEnabled) return@detectTapGestures
                    onStep()
                    val repeat = scope.launchRepeat(delta, stepBy)
                    tryAwaitRelease()
                    repeat.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** Typed entry for a number row, validated against [range] with the error shown inline. */
@Composable
private fun NumberEntryDialog(title: String, value: Int, range: IntRange, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val colors = AdosTheme.colors
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in range
    AdosDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            AdosButton(stringResource(R.string.number_entry_set), onClick = { parsed?.let(onSet) }, enabled = valid)
        },
        dismiss = {
            AdosButton(stringResource(R.string.number_entry_cancel), onClick = onDismiss, variant = AdosButtonVariant.Secondary)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = !valid,
                supportingText = if (valid) null else {
                    { Text(stringResource(R.string.number_entry_range, range.first, range.last)) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = AdosTypography.numeric.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.borderDefault,
                    cursorColor = colors.accentPrimary,
                    focusedTextColor = colors.fgPrimary,
                    unfocusedTextColor = colors.fgPrimary,
                ),
            )
        },
    )
}

@Preview(widthDp = 1000)
@Composable
private fun NumberSettingRowPreview() = ThemePreviews {
    NumberSettingRow(
        "Return altitude",
        value = 60,
        onValueChange = {},
        range = 10..120,
        step = 5,
        decrementDescription = "Decrease",
        incrementDescription = "Increase",
        format = { "$it m" },
    )
}
