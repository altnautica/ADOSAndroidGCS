package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Button glyph family shown in hints. */
enum class GlyphSet { PlayStation, Generic }

/**
 * Controller presentation state: [active] while a controller was the last input,
 * the glyphs matching the active pad, and its battery when known.
 */
@Immutable
data class ControllerUi(
    val active: Boolean = false,
    val glyphs: GlyphSet = GlyphSet.Generic,
    val batteryPct: Int? = null,
)

val LocalControllerUi = staticCompositionLocalOf { ControllerUi() }
