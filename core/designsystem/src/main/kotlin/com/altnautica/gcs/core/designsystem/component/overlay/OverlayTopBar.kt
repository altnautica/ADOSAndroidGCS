package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

/**
 * The bottom edge of a top bar that must stay usable under any overlay, measured in root
 * (window) coordinates. Overlays draw from the window root, so the edge includes whatever
 * sits above the screen: a window caption bar, a cutout, system bars.
 */
@Stable
class OverlayTopBar {
    internal var bottomPx by mutableFloatStateOf(0f)
}

@Composable
fun rememberOverlayTopBar(): OverlayTopBar = remember { OverlayTopBar() }

/** Records this element's bottom edge in root coordinates as [bar]'s edge. */
fun Modifier.overlayTopBar(bar: OverlayTopBar): Modifier =
    onGloballyPositioned { bar.bottomPx = it.boundsInRoot().bottom }

/** Provides [bar]'s measured edge as [LocalOverlayTopInset] to [content]. */
@Composable
fun ProvideOverlayTopBar(bar: OverlayTopBar, content: @Composable () -> Unit) {
    val inset = with(LocalDensity.current) { bar.bottomPx.toDp() }
    CompositionLocalProvider(LocalOverlayTopInset provides inset, content = content)
}
