package com.altnautica.gcs.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

sealed class NavRoutes(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Video : NavRoutes("video", "Video", Icons.Filled.Videocam)
    data object Map : NavRoutes("map", "Map", Icons.Filled.Map)
    data object GCS : NavRoutes("gcs", "GCS", Icons.Filled.Speed)
    data object Agriculture : NavRoutes("agriculture", "Agriculture", Icons.Filled.Agriculture)
    data object MissionPlanner : NavRoutes("mission_planner", "Mission", Icons.Filled.Route)
    data object Gallery : NavRoutes("gallery", "Gallery", Icons.Filled.VideoLibrary)
    data object TileDownload : NavRoutes("tile_download", "Offline Maps", Icons.Filled.CloudDownload)
    data object Settings : NavRoutes("settings", "Settings", Icons.Filled.Settings)

    companion object {
        /** Routes shown in the bottom navigation bar. */
        val all = listOf(Video, Map, GCS, MissionPlanner, Agriculture, Settings)
    }
}
