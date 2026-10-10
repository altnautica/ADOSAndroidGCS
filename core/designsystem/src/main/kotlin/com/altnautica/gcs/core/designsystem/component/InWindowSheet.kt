package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** Widest an in-window sheet grows on large screens. */
private val SheetMaxWidth = 720.dp

/**
 * Bottom sheet drawn inside the host window (scrim plus panel), not in a dialog
 * window. Hardware input (gamepad, keyboard) keeps reaching the activity and the
 * system bars stay as the host set them, so full-screen surfaces such as the cockpit
 * use it. Place it in a full-size box above the content; tapping the scrim calls
 * [onDismiss]. The host handles system back.
 */
@Composable
fun InWindowSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdosTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.mediaScrim)
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        )
        // Surface blocks pointer input, so taps on the panel never reach the scrim.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = SheetMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.9f),
            shape = RoundedCornerShape(topStart = AdosRadius.Xl, topEnd = AdosRadius.Xl),
            color = colors.bgSurface,
            contentColor = colors.fgPrimary,
        ) {
            Column(Modifier.padding(bottom = AdosSpacing.S4)) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = AdosSpacing.S2)
                        .size(width = 32.dp, height = 4.dp)
                        .background(colors.borderStrong, RoundedCornerShape(AdosRadius.Sm)),
                )
                content()
            }
        }
    }
}

/** [ConfirmSheet] drawn in the host window; see [InWindowSheet]. */
@Composable
fun InWindowConfirmSheet(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    InWindowSheet(onDismiss, modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ConfirmSheetBody(title, message, danger, content)
        }
    }
}

@Preview(widthDp = 800, heightDp = 480)
@Composable
private fun InWindowConfirmSheetPreview() = ThemePreviews {
    Box(Modifier.size(width = 800.dp, height = 400.dp)) {
        InWindowConfirmSheet("Kill motors", "The motors stop at once. In flight the vehicle falls.", onDismiss = {}, danger = true) {
            HoldToConfirmButton("Hold to confirm", holdMs = 1500, onConfirmed = {}, danger = true)
        }
    }
}
