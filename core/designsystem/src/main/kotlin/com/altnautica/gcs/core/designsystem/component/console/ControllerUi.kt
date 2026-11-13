package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

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

/**
 * The controller presentation for the composition. It changes on every switch between touch
 * and controller and on each pad battery change, so it is a tracked local: a change
 * recomposes only the composables that read it, not everything below the provider.
 */
val LocalControllerUi = compositionLocalOf { ControllerUi() }
