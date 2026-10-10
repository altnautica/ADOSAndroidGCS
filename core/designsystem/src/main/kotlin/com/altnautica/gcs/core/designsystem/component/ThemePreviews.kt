package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId

/** Renders [content] once per theme, side by side, for previews. */
@Composable
internal fun ThemePreviews(content: @Composable () -> Unit) {
    Row {
        AdosThemeId.entries.forEach { id ->
            AdosTheme(themeId = id) {
                Box(
                    Modifier
                        .width(320.dp)
                        .background(AdosTheme.colors.bgCanvas)
                        .padding(AdosSpacing.S4),
                ) { content() }
            }
        }
    }
}
