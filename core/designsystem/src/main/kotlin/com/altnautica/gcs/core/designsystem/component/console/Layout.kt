package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * True when the window is at least as wide as it is tall. This is the only
 * orientation test in the app: every surface lays out for landscape first and
 * falls back to a simpler portrait layout when this is false.
 */
@Composable
fun isLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width >= size.height
}
