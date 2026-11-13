package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.R
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.ConfirmSheetBody
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsolePreview
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.HintBar
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** Widest a portrait (bottom) sheet grows on large screens. */
private val PortraitMaxWidth = 720.dp
private const val LANDSCAPE_WIDTH_FRACTION = 0.42f
private val LandscapeMinWidth = 320.dp
private val LandscapeMaxWidth = 480.dp

/**
 * Sheet drawn in the app window through the overlay host: a side panel anchored
 * right in landscape, a bottom panel in portrait. Tapping the scrim, system back
 * and the controller back button call [onDismiss]. Focus is trapped inside and
 * starts on the first focusable control of [content], which scrolls vertically.
 */
@Composable
fun AdosSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Overlay(onDismiss = onDismiss) {
        SheetFrame(onDismiss, modifier, title, content)
    }
}

@Composable
private fun SheetFrame(
    onDismiss: () -> Unit,
    modifier: Modifier,
    title: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdosTheme.colors
    val landscape = isLandscape()
    val reduced = rememberReducedMotion()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val enter: EnterTransition = when {
        reduced -> EnterTransition.None
        landscape -> slideInHorizontally(tween(AdosMotion.BaseMs, easing = AdosMotion.Emphasized)) { it }
        else -> slideInVertically(tween(AdosMotion.BaseMs, easing = AdosMotion.Emphasized)) { it }
    }
    val requester = remember { FocusRequester() }
    OverlayBackHandler(onDismiss)
    val topInset = LocalOverlayTopInset.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = topInset)) {
        OverlayScrim(colors.mediaScrim, onDismiss)
        val panelModifier = if (landscape) {
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width((maxWidth * LANDSCAPE_WIDTH_FRACTION).coerceIn(LandscapeMinWidth, LandscapeMaxWidth))
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = PortraitMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.9f)
        }
        AnimatedVisibility(visibleState = visible, modifier = panelModifier, enter = enter, exit = ExitTransition.None) {
            // Surface blocks pointer input, so taps on the panel never reach the scrim.
            Surface(
                modifier = modifier.overlayFocusTrap(requester),
                shape = if (landscape) {
                    RoundedCornerShape(topStart = AdosRadius.Xl, bottomStart = AdosRadius.Xl)
                } else {
                    RoundedCornerShape(topStart = AdosRadius.Xl, topEnd = AdosRadius.Xl)
                },
                color = colors.bgSurface,
                contentColor = colors.fgPrimary,
            ) {
                val insets = when {
                    !landscape -> WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    // Below a screen's own top bar the status-bar inset is already behind it.
                    topInset > 0.dp -> WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
                    else -> WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)
                }
                Column(Modifier.windowInsetsPadding(insets)) {
                    if (title != null) {
                        Text(
                            title,
                            modifier = Modifier.padding(start = AdosSpacing.S6, end = AdosSpacing.S6, top = AdosSpacing.S4),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.fgPrimary,
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = AdosSpacing.S2),
                        content = content,
                    )
                    HintBar(
                        listOf(
                            Hint(ConsoleButton.Confirm, stringResource(R.string.console_hint_select)),
                            Hint(ConsoleButton.Back, stringResource(R.string.console_hint_close)),
                        ),
                    )
                }
            }
            OverlayInitialFocus(requester)
        }
    }
}

/** [AdosSheet] framing a confirmation; [content] holds the confirming control. */
@Composable
fun AdosConfirmSheet(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AdosSheet(onDismiss = onDismiss, modifier = modifier) {
        ConfirmSheetBody(title, message, danger, content)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun AdosSheetLandscapePreview() = ConsolePreview {
    AdosSheet(onDismiss = {}, title = "Quick settings") {
        Box(Modifier.padding(AdosSpacing.S6)) { AdosButton("Apply", onClick = {}) }
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun AdosSheetPortraitPreview() = ConsolePreview {
    AdosConfirmSheet("Return to launch", "The vehicle flies home at the configured altitude.", onDismiss = {}, danger = true) {
        HoldToConfirmButton("Hold to confirm", holdMs = 800, onConfirmed = {}, danger = true, modifier = Modifier.fillMaxWidth())
    }
}
