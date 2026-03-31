package com.altnautica.gcs.ui.navigation

sealed class NavRoutes(val route: String) {
    data object Home : NavRoutes("home")
    data object Fly : NavRoutes("fly")
    data object Map : NavRoutes("map")
    data object Plan : NavRoutes("plan")
    data object Agriculture : NavRoutes("agriculture")
    data object Settings : NavRoutes("settings")
    data object Gallery : NavRoutes("gallery")
    data object TileDownload : NavRoutes("tile_download")
}
