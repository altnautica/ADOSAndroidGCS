package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.ThemePreviews
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** One controller hint: the [button] to press and what it does. */
@Immutable
data class Hint(val button: ConsoleButton, val label: String)

private val HintBarHeight = 32.dp

/**
 * Right-aligned strip of the controller buttons that matter on the current screen.
 * Shows only while a controller was the last input; renders nothing otherwise.
 * The bar repeats actions that are reachable by touch, so it is hidden from
 * accessibility services.
 */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier) {
    if (!LocalControllerUi.current.active || hints.isEmpty()) return
    val colors = AdosTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HintBarHeight)
            .background(colors.hudGlass)
            .padding(horizontal = AdosSpacing.S4)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S4, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControllerGlyph(hint.button, size = 18.dp)
                Spacer(Modifier.width(AdosSpacing.S1))
                Text(hint.label, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary, maxLines = 1)
            }
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun HintBarPreview() = ThemePreviews {
    CompositionLocalProvider(LocalControllerUi provides ControllerUi(active = true, glyphs = GlyphSet.PlayStation)) {
        HintBar(listOf(Hint(ConsoleButton.Confirm, "Open"), Hint(ConsoleButton.Details, "Details"), Hint(ConsoleButton.Back, "Back")))
    }
}
