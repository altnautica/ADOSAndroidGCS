package com.altnautica.gcs.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Press/release state of a hold-to-confirm control. Touch, hardware keys and gamepad
 * buttons all drive the same [press]/[release] pair.
 */
@Stable
class HoldToConfirmState internal constructor() {
    var isPressed by mutableStateOf(false)
        private set

    internal val progress = Animatable(0f)

    /** Fill fraction of the hold, 0..1. */
    val fraction: Float get() = progress.value

    fun press() {
        isPressed = true
    }

    fun release() {
        isPressed = false
    }
}

@Composable
fun rememberHoldToConfirmState(): HoldToConfirmState = remember { HoldToConfirmState() }

internal fun isConfirmKey(key: Key) =
    key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter || key == Key.ButtonA || key == Key.Spacebar

/**
 * Fires [onConfirmed] once the control has been held for [holdMs]. Releasing early resets
 * the ring. Focused hardware Enter/D-pad centre/gamepad A act as the press as well.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    holdMs: Int,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    state: HoldToConfirmState = rememberHoldToConfirmState(),
) {
    val colors = AdosTheme.colors
    val currentOnConfirmed by rememberUpdatedState(onConfirmed)
    LaunchedEffect(state, enabled, holdMs) {
        snapshotFlow { state.isPressed && enabled }.collectLatest { down ->
            if (down) {
                val remaining = ((1f - state.progress.value) * holdMs).roundToInt()
                state.progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                currentOnConfirmed()
                state.release()
            } else {
                state.progress.snapTo(0f)
            }
        }
    }
    val accent = if (danger) colors.statusError else colors.accentPrimary
    val shape = RoundedCornerShape(AdosRadius.Lg)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = AdosTheme.touchMin, minWidth = AdosTheme.touchMin)
            .consoleFocus(shape)
            .alpha(if (enabled) 1f else 0.5f)
            .border(BorderStroke(1.dp, accent), shape)
            .background(accent.copy(alpha = 0.12f), shape)
            .semantics {
                role = Role.Button
                if (enabled) {
                    // TalkBack double-tap-and-hold confirms, matching the physical hold.
                    onLongClick(label = label) {
                        currentOnConfirmed()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(state, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    state.press()
                    tryAwaitRelease()
                    state.release()
                })
            }
            .onKeyEvent { event ->
                if (!enabled || !isConfirmKey(event.key)) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> state.press()
                    KeyEventType.KeyUp -> state.release()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(enabled)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.size(AdosSize.IconLg),
            color = accent,
            trackColor = colors.borderDefault,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.width(AdosSpacing.S3))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
    }
}

@Composable
internal fun ConfirmSheetBody(
    title: String,
    message: String,
    danger: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdosTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (danger) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = colors.statusError,
                    modifier = Modifier.size(AdosSize.IconLg),
                )
                Spacer(Modifier.width(AdosSpacing.S2))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = if (danger) colors.statusError else colors.fgPrimary,
            )
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        content()
    }
}

@Preview(widthDp = 1000)
@Composable
private fun HoldToConfirmPreview() = ThemePreviews {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        HoldToConfirmButton("Hold to arm", holdMs = 1500, onConfirmed = {})
        HoldToConfirmButton("Hold to kill motors", holdMs = 1500, onConfirmed = {}, danger = true)
    }
}

@Preview(widthDp = 1000)
@Composable
private fun ConfirmSheetPreview() = ThemePreviews {
    Box(Modifier.background(AdosTheme.colors.bgSurface, RoundedCornerShape(AdosRadius.Xl))) {
        ConfirmSheetBody("Return to launch", "The vehicle flies home at the configured altitude.", danger = true) {
            HoldToConfirmButton("Hold to confirm", holdMs = 1500, onConfirmed = {}, danger = true)
        }
    }
}
