@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package com.altnautica.gcs.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass> {
    // Fallback for previews / tests that don't wrap in the composition provider.
    // 400 x 800 dp = Compact width, Medium height. Safe phone-portrait default.
    WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
}

@Composable
@ReadOnlyComposable
fun isCompactWidth(): Boolean =
    LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Compact

@Composable
@ReadOnlyComposable
fun isCompactHeight(): Boolean =
    LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Compact

@Composable
@ReadOnlyComposable
fun isPortrait(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
