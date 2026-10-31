package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId

/**
 * Full-frame preview of a console surface in one theme, with the app-level locals
 * the surfaces expect (overlay host, command dispatcher, an active PlayStation pad).
 */
@Composable
internal fun ConsolePreview(themeId: AdosThemeId = AdosThemeId.BrandDark, content: @Composable () -> Unit) {
    val commands = remember { ConsoleCommandDispatcher() }
    CompositionLocalProvider(
        LocalConsoleCommands provides commands,
        LocalControllerUi provides ControllerUi(active = true, glyphs = GlyphSet.PlayStation, batteryPct = 62),
    ) {
        AdosTheme(themeId = themeId) {
            ProvideOverlayHost {
                Box(Modifier.fillMaxSize().background(AdosTheme.colors.bgCanvas)) { content() }
            }
        }
    }
}
