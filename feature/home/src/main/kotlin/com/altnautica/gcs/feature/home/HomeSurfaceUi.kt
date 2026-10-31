package com.altnautica.gcs.feature.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/** Launch card icon of a surface. */
val HomeSurface.icon: ImageVector
    get() = when (this) {
        HomeSurface.Fly -> Icons.Outlined.SportsEsports
        HomeSurface.Plan -> Icons.Outlined.Map
        HomeSurface.Setup -> Icons.Outlined.Build
        HomeSurface.Parameters -> Icons.Outlined.Tune
        HomeSurface.Vehicle -> Icons.Outlined.Info
        HomeSurface.Logs -> Icons.Outlined.History
        HomeSurface.Settings -> Icons.Outlined.Settings
    }

/** Launch card title of a surface. */
@get:StringRes
val HomeSurface.label: Int
    get() = when (this) {
        HomeSurface.Fly -> R.string.home_surface_fly
        HomeSurface.Plan -> R.string.home_surface_plan
        HomeSurface.Setup -> R.string.home_surface_setup
        HomeSurface.Parameters -> R.string.home_surface_parameters
        HomeSurface.Vehicle -> R.string.home_surface_vehicle
        HomeSurface.Logs -> R.string.home_surface_logs
        HomeSurface.Settings -> R.string.home_surface_settings
    }
