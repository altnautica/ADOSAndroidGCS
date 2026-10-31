package com.altnautica.gcs.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Two-step guard for dangerous commands: the first tap arms a red guard for [guardWindowMs];
 * a hold of [holdMs] while armed fires [onFired]. An armed guard that is not held expires.
 * D-pad focus stays on the control while it swaps between the guard and the hold button.
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
    // Which child (guard or hold) held focus; null when focus is elsewhere.
    var focusedChildArmed by remember { mutableStateOf<Boolean?>(null) }
    val holdState = rememberHoldToConfirmState()
    val requester = remember { FocusRequester() }
    LaunchedEffect(armed) {
        if (armed) {
            delay(guardWindowMs)
            snapshotFlow { holdState.isPressed }.first { !it }
            armed = false
        }
    }
    // The swapped-in child takes the focus its predecessor held.
    LaunchedEffect(armed) {
        val held = focusedChildArmed
        if (held != null && held != armed) requester.requestFocus()
    }
    val reduced = rememberReducedMotion()
    val colors = AdosTheme.colors
    val edge by animateColorAsState(
        targetValue = if (armed) colors.statusError else colors.borderDefault,
        animationSpec = if (reduced) snap() else tween(AdosMotion.QuickMs, easing = AdosMotion.Standard),
        label = "guardEdge",
    )
    Box(
        modifier
            .onFocusChanged {
                // A loss caused by the swap itself keeps the record, so the new child can take focus.
                if (it.hasFocus) focusedChildArmed = armed else if (focusedChildArmed == armed) focusedChildArmed = null
            }
            .border(1.dp, edge, RoundedCornerShape(AdosRadius.Lg)),
    ) {
        if (armed) {
            HoldToConfirmButton(
                label = guardLabel,
                holdMs = holdMs,
                onConfirmed = {
                    armed = false
                    onFired()
                },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                enabled = enabled,
                danger = true,
                state = holdState,
            )
        } else {
            AdosButton(
                label = label,
                onClick = { armed = true },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                variant = AdosButtonVariant.Danger,
                enabled = enabled,
            )
        }
    }
}

@Preview(widthDp = 1000)
@Composable
private fun GuardedButtonPreview() = ThemePreviews {
    GuardedButton("Kill motors", guardLabel = "Hold to confirm", onFired = {})
}
