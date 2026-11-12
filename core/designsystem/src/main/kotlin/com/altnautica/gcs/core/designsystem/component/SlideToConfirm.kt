package com.altnautica.gcs.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Confirms when the thumb is dragged to the end of the track; otherwise it snaps back.
 * With focus, holding Enter, D-pad centre or the controller confirm button drives the
 * thumb across in [keyHoldMs]; releasing early snaps it back.
 */
@Composable
fun SlideToConfirm(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyHoldMs: Int = 1500,
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
    var keyHold by remember { mutableStateOf<Job?>(null) }
    // A confirm key that ends cancelled (pad gone, focus lost, a chord took the button) never
    // reaches onKeyEvent: the app guard drops it. Abort a keyed slide here instead.
    OnConfirmCancelled {
        val hold = keyHold
        if (hold != null && hold.isActive) {
            hold.cancel()
            keyHold = null
            scope.launch { offset.animateTo(0f, back) }
        }
    }
    val shape = RoundedCornerShape(AdosRadius.Xl)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thumb + AdosSpacing.S2)
            .onSizeChanged { trackPx = it.width }
            .consoleFocus(shape)
            .alpha(if (enabled) 1f else 0.5f)
            .background(colors.bgRaised, shape)
            .border(1.dp, colors.borderDefault, shape)
            .semantics {
                if (enabled) {
                    onLongClick(label = label) {
                        currentOnConfirmed()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .onKeyEvent { event ->
                if (!enabled || !isConfirmKey(event.key)) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (keyHold?.isActive != true && maxOffset > 0f) {
                            keyHold = scope.launch {
                                val remaining = ((1f - offset.value / maxOffset) * keyHoldMs).roundToInt().coerceAtLeast(0)
                                offset.animateTo(maxOffset, tween(remaining, easing = LinearEasing))
                                currentOnConfirmed()
                                offset.animateTo(0f, back)
                            }
                        }
                    }
                    KeyEventType.KeyUp -> {
                        val hold = keyHold
                        if (hold != null && hold.isActive && offset.value < maxOffset) {
                            hold.cancel()
                            scope.launch { offset.animateTo(0f, back) }
                        }
                    }
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(enabled),
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

@Preview(widthDp = 1000)
@Composable
private fun SlideToConfirmPreview() = ThemePreviews {
    SlideToConfirm("Slide to take off", onConfirmed = {})
}
