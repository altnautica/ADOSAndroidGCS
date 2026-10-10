package com.altnautica.gcs.core.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId

/** Touch targets grow by this factor when glove mode is on. */
const val GLOVE_TOUCH_SCALE = 1.25f

val LocalAdosColors = staticCompositionLocalOf { AdosColors.BrandDark }
val LocalGloveMode = staticCompositionLocalOf { false }

/** Maps the token colour roles onto a Material 3 scheme. Never uses dynamic colour. */
internal fun adosColorScheme(themeId: AdosThemeId): ColorScheme {
    val c = AdosColors.forTheme(themeId)
    return when (themeId) {
        AdosThemeId.BrandLight -> lightColorScheme(
            primary = c.accentPrimary,
            onPrimary = c.accentForeground,
            primaryContainer = c.bgRaised,
            onPrimaryContainer = c.fgPrimary,
            secondary = c.accentSecondary,
            onSecondary = c.accentForeground,
            secondaryContainer = c.bgRaised,
            onSecondaryContainer = c.fgPrimary,
            tertiary = c.statusInfo,
            onTertiary = c.statusOn,
            background = c.bgCanvas,
            onBackground = c.fgPrimary,
            surface = c.bgSurface,
            onSurface = c.fgPrimary,
            surfaceVariant = c.bgRaised,
            onSurfaceVariant = c.fgSecondary,
            surfaceContainerLowest = c.bgCanvas,
            surfaceContainerLow = c.bgSurface,
            surfaceContainer = c.bgSurface,
            surfaceContainerHigh = c.bgRaised,
            surfaceContainerHighest = c.bgHover,
            outline = c.borderStrong,
            outlineVariant = c.borderDefault,
            error = c.statusError,
            onError = c.statusOn,
            scrim = c.mediaScrim,
        )
        AdosThemeId.BrandDark, AdosThemeId.Nvg -> darkColorScheme(
            primary = c.accentPrimary,
            onPrimary = c.accentForeground,
            primaryContainer = c.bgRaised,
            onPrimaryContainer = c.fgPrimary,
            secondary = c.accentSecondary,
            onSecondary = c.accentForeground,
            secondaryContainer = c.bgRaised,
            onSecondaryContainer = c.fgPrimary,
            tertiary = c.statusInfo,
            onTertiary = c.statusOn,
            background = c.bgCanvas,
            onBackground = c.fgPrimary,
            surface = c.bgSurface,
            onSurface = c.fgPrimary,
            surfaceVariant = c.bgRaised,
            onSurfaceVariant = c.fgSecondary,
            surfaceContainerLowest = c.bgCanvas,
            surfaceContainerLow = c.bgSurface,
            surfaceContainer = c.bgSurface,
            surfaceContainerHigh = c.bgRaised,
            surfaceContainerHighest = c.bgHover,
            outline = c.borderStrong,
            outlineVariant = c.borderDefault,
            error = c.statusError,
            onError = c.statusOn,
            scrim = c.mediaScrim,
        )
    }
}

internal val AdosShapes = Shapes(
    extraSmall = RoundedCornerShape(AdosRadius.Sm),
    small = RoundedCornerShape(AdosRadius.Md),
    medium = RoundedCornerShape(AdosRadius.Lg),
    large = RoundedCornerShape(AdosRadius.Xl),
    extraLarge = RoundedCornerShape(AdosRadius.Xl),
)

@Composable
fun AdosTheme(
    themeId: AdosThemeId = AdosThemeId.BrandDark,
    gloveMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = remember(themeId) { adosColorScheme(themeId) }
    val typography = remember { adosMaterialTypography() }
    CompositionLocalProvider(
        LocalAdosColors provides AdosColors.forTheme(themeId),
        LocalGloveMode provides gloveMode,
    ) {
        MaterialTheme(colorScheme = scheme, shapes = AdosShapes, typography = typography, content = content)
    }
}

object AdosTheme {
    val colors: AdosColorRoles
        @Composable @ReadOnlyComposable get() = LocalAdosColors.current

    val gloveMode: Boolean
        @Composable @ReadOnlyComposable get() = LocalGloveMode.current

    /** Minimum touch target; scaled up in glove mode. */
    val touchMin: Dp
        @Composable @ReadOnlyComposable get() =
            if (LocalGloveMode.current) AdosSize.TouchMin * GLOVE_TOUCH_SCALE else AdosSize.TouchMin
}

/** True when the system animator scale is zero (user asked to remove animations). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
