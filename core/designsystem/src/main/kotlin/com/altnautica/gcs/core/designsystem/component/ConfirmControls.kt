package com.altnautica.gcs.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

private fun isConfirmKey(key: Key) =
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
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = AdosTheme.touchMin, minWidth = AdosTheme.touchMin)
            .alpha(if (enabled) 1f else 0.5f)
            .border(BorderStroke(1.dp, accent), RoundedCornerShape(AdosRadius.Lg))
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(AdosRadius.Lg))
            .semantics { role = Role.Button }
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

/** Confirms when the thumb is dragged to the end of the track; otherwise it snaps back. */
@Composable
fun SlideToConfirm(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AdosTheme.colors
    val thumb = AdosTheme.touchMin
    val density = LocalDensity.current
    val thumbPx = with(density) { thumb.toPx() }
    val inset = with(density) { AdosSpacing.S1.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    val maxOffset = (trackPx - thumbPx - inset * 2).coerceAtLeast(0f)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val currentOnConfirmed by rememberUpdatedState(onConfirmed)
    val back: AnimationSpec<Float> =
        if (reduced) snap() else tween(AdosMotion.QuickMs, easing = AdosMotion.Standard)
    val dragState = rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxOffset)) }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thumb + AdosSpacing.S2)
            .onSizeChanged { trackPx = it.width }
            .alpha(if (enabled) 1f else 0.5f)
            .background(colors.bgRaised, RoundedCornerShape(AdosRadius.Xl))
            .border(1.dp, colors.borderDefault, RoundedCornerShape(AdosRadius.Xl)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth().padding(start = thumb + AdosSpacing.S2, end = AdosSpacing.S4),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = colors.fgSecondary,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((inset + offset.value).roundToInt(), 0) }
                .size(thumb)
                .background(colors.accentPrimary, RoundedCornerShape(AdosRadius.Lg))
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = {
                        if (maxOffset > 0f && offset.value >= maxOffset * 0.95f) currentOnConfirmed()
                        offset.animateTo(0f, back)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = colors.accentForeground,
                modifier = Modifier.size(AdosSize.IconLg),
            )
        }
    }
}

/**
 * Two-step guard for dangerous commands: the first tap arms a red guard for [guardWindowMs];
 * a hold of [holdMs] while armed fires [onFired]. An armed guard that is not held expires.
 */
@Composable
fun GuardedButton(
    label: String,
    guardLabel: String,
    onFired: () -> Unit,
    modifier: Modifier = Modifier,
    guardWindowMs: Long = 3000,
    holdMs: Int = 1500,
    enabled: Boolean = true,
) {
    var armed by remember { mutableStateOf(false) }
    val holdState = rememberHoldToConfirmState()
    LaunchedEffect(armed) {
        if (armed) {
            delay(guardWindowMs)
            snapshotFlow { holdState.isPressed }.first { !it }
            armed = false
        }
    }
    val reduced = rememberReducedMotion()
    val colors = AdosTheme.colors
    val edge by animateColorAsState(
        targetValue = if (armed) colors.statusError else colors.borderDefault,
        animationSpec = if (reduced) snap() else tween(AdosMotion.QuickMs, easing = AdosMotion.Standard),
        label = "guardEdge",
    )
    Box(modifier.border(1.dp, edge, RoundedCornerShape(AdosRadius.Lg))) {
        if (armed) {
            HoldToConfirmButton(
                label = guardLabel,
                holdMs = holdMs,
                onConfirmed = {
                    armed = false
                    onFired()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                danger = true,
                state = holdState,
            )
        } else {
            AdosButton(
                label = label,
                onClick = { armed = true },
                modifier = Modifier.fillMaxWidth(),
                variant = AdosButtonVariant.Danger,
                enabled = enabled,
            )
        }
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

/** Bottom sheet that frames a confirmation; [content] holds the confirming control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdosTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.bgSurface,
        contentColor = colors.fgPrimary,
        scrimColor = colors.mediaScrim,
    ) {
        ConfirmSheetBody(title, message, danger, content)
        Spacer(Modifier.height(AdosSpacing.S4))
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
private fun SlideToConfirmPreview() = ThemePreviews {
    SlideToConfirm("Slide to take off", onConfirmed = {})
}

@Preview(widthDp = 1000)
@Composable
private fun GuardedButtonPreview() = ThemePreviews {
    GuardedButton("Kill motors", guardLabel = "Hold to confirm", onFired = {})
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
