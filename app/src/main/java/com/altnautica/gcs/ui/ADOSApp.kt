package com.altnautica.gcs.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.altnautica.gcs.ui.agriculture.AgricultureScreen
import com.altnautica.gcs.ui.gallery.VideoGalleryScreen
import com.altnautica.gcs.ui.gcs.MapScreen
import com.altnautica.gcs.ui.home.HomeScreen
import com.altnautica.gcs.ui.maps.TileDownloadScreen
import com.altnautica.gcs.ui.mission.MissionPlannerScreen
import com.altnautica.gcs.ui.navigation.NavRoutes
import com.altnautica.gcs.ui.settings.SettingsScreen
import com.altnautica.gcs.ui.video.FlyScreen

@Composable
fun ADOSApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.Home.route,
    ) {
        composable(NavRoutes.Home.route) {
            HomeScreen(onNavigate = { navController.navigate(it) })
        }
        composable(NavRoutes.Fly.route) {
            FlyScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.Map.route) {
            MapScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.Plan.route) {
            MissionPlannerScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.Agriculture.route) {
            AgricultureScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.Gallery.route) {
            VideoGalleryScreen()
        }
        composable(NavRoutes.TileDownload.route) {
            TileDownloadScreen()
        }
    }
}
