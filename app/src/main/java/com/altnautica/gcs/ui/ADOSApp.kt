package com.altnautica.gcs.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.altnautica.gcs.ui.agriculture.AgricultureScreen
import com.altnautica.gcs.ui.configure.ConfigureScreen
import com.altnautica.gcs.ui.flightlog.FlightDetailScreen
import com.altnautica.gcs.ui.flightlog.FlightHistoryScreen
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
            HomeScreen(onNavigate = { route ->
                navController.navigate(route) {
                    launchSingleTop = true
                    restoreState = true
                }
            })
        }
        composable(NavRoutes.Fly.route) {
            FlyScreen(onBack = { navController.navigateUp() })
        }
        composable(NavRoutes.Map.route) {
            MapScreen(onBack = { navController.navigateUp() })
        }
        composable(NavRoutes.Plan.route) {
            MissionPlannerScreen(onBack = { navController.navigateUp() })
        }
        composable(NavRoutes.Agriculture.route) {
            AgricultureScreen(onBack = { navController.navigateUp() })
        }
        composable(NavRoutes.Settings.route) {
            SettingsScreen(onBack = { navController.navigateUp() })
        }
        composable(NavRoutes.Gallery.route) {
            VideoGalleryScreen()
        }
        composable(NavRoutes.TileDownload.route) {
            TileDownloadScreen()
        }
        composable(NavRoutes.Configure.route) {
            ConfigureScreen(onBack = { navController.navigateUp() })
        }
        composable(NavRoutes.Logs.route) {
            FlightHistoryScreen(
                onBack = { navController.navigateUp() },
                onSessionClick = { sessionId ->
                    navController.navigate(NavRoutes.FlightDetail.createRoute(sessionId))
                },
            )
        }
        composable(
            route = NavRoutes.FlightDetail.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            FlightDetailScreen(
                sessionId = sessionId,
                onBack = { navController.navigateUp() },
            )
        }
    }
}
