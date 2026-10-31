package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.console.ConsolePreview
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

private val DialogMaxWidth = 520.dp

/**
 * Centred dialog drawn in the app window through the overlay host. Tapping the
 * scrim, system back and the controller back button call [onDismiss]. Focus is
 * trapped inside and starts on the first control in [confirm]; buttons sit at the
 * end, [dismiss] before [confirm].
 */
@Composable
fun AdosDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable RowScope.() -> Unit,
    dismiss: (@Composable RowScope.() -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Overlay {
        val colors = AdosTheme.colors
        val trap = remember { FocusRequester() }
        val confirmFocus = remember { FocusRequester() }
        OverlayBackHandler(onDismiss)
        Box(Modifier.fillMaxSize()) {
            OverlayScrim(colors.mediaScrim, onDismiss)
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(AdosSpacing.S4)
                    .widthIn(max = DialogMaxWidth)
                    .fillMaxWidth()
                    .overlayFocusTrap(trap),
                shape = RoundedCornerShape(AdosRadius.Xl),
                color = colors.bgSurface,
                contentColor = colors.fgPrimary,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(AdosSpacing.S6),
                    verticalArrangement = Arrangement.spacedBy(AdosSpacing.S4),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.fgPrimary)
                    text?.invoke()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismiss?.invoke(this)
                        // Focusing this group lands on its first focusable child.
                        Row(
                            Modifier.focusRequester(confirmFocus).focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
                            verticalAlignment = Alignment.CenterVertically,
                            content = confirm,
                        )
                    }
                }
            }
        }
        ConfirmInitialFocus(trap, confirmFocus)
    }
}

/** Enters the dialog, then moves onto the first confirm control. */
@Composable
private fun ConfirmInitialFocus(trap: FocusRequester, confirm: FocusRequester) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(trap, confirm) {
        if (!trap.requestFocus(FocusDirection.Enter)) return@LaunchedEffect
        if (!confirm.requestFocus(FocusDirection.Enter)) focusManager.moveFocus(FocusDirection.Enter)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun AdosDialogPreview() = ConsolePreview {
    AdosDialog(
        title = "Allow notifications?",
        onDismiss = {},
        confirm = { AdosButton("Allow", onClick = {}) },
        dismiss = { AdosButton("Not now", onClick = {}, variant = AdosButtonVariant.Secondary) },
        text = { Text("Alerts reach you while the app is in the background.") },
    )
}
