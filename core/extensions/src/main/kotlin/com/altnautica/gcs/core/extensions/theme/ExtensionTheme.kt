package com.altnautica.gcs.core.extensions.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The CSS custom properties sent to extension frames with `theme.changed`. Keys are the
 * variables every extension styles against; values come from the active theme's roles.
 */
object ExtensionTheme {

    fun cssVariables(roles: AdosColorRoles): Map<String, String> = linkedMapOf(
        "--bg-primary" to css(roles.bgCanvas),
        "--bg-secondary" to css(roles.bgSurface),
        "--bg-tertiary" to css(roles.bgHover),
        "--bg-raised" to css(roles.bgRaised),
        "--text-primary" to css(roles.fgPrimary),
        "--text-secondary" to css(roles.fgSecondary),
        "--text-tertiary" to css(roles.fgTertiary),
        "--accent-primary" to css(roles.accentPrimary),
        "--accent-primary-hover" to css(roles.accentPrimaryHover),
        "--accent-secondary" to css(roles.accentSecondary),
        "--accent-foreground" to css(roles.accentForeground),
        "--border-default" to css(roles.borderDefault),
        "--border-strong" to css(roles.borderStrong),
        "--status-success" to css(roles.statusSuccess),
        "--status-warning" to css(roles.statusWarning),
        "--status-serious" to css(roles.statusSerious),
        "--status-error" to css(roles.statusError),
        "--status-info" to css(roles.statusInfo),
        "--on-status" to css(roles.statusOn),
        "--focus-ring" to css(roles.focusRing),
        "--hud-primary" to css(roles.hudPrimary),
        "--hud-dim" to css(roles.hudDim),
        "--hud-ink" to css(roles.hudInk),
        "--hud-ink-2" to css(roles.hudInk2),
        "--hud-muted" to css(roles.hudMuted),
        "--hud-good" to css(roles.hudGood),
        "--hud-warn" to css(roles.hudWarn),
        "--hud-crit" to css(roles.hudCrit),
        "--hud-lock" to css(roles.hudLock),
        "--hud-glass" to css(roles.hudGlass),
        "--hud-glass-strong" to css(roles.hudGlassStrong),
        "--hud-hair" to css(roles.hudHair),
        "--hud-hair-2" to css(roles.hudHair2),
    )

    /** `#RRGGBB` for opaque colours, `rgba(r, g, b, a)` otherwise (alpha to two decimals). */
    fun css(color: Color): String {
        val argb = color.toArgb()
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (a == 0xFF) return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
        val alpha = (a / 255.0 * 100).roundToInt() / 100.0
        val alphaText = alpha.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
        return "rgba($r, $g, $b, $alphaText)"
    }
}
