package com.altnautica.gcs.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.altnautica.gcs.ui.agriculture.AgricultureScreen
import com.altnautica.gcs.ui.gallery.VideoGalleryScreen
import com.altnautica.gcs.ui.gcs.GcsScreen
import com.altnautica.gcs.ui.groundstation.GroundStationScreen
import com.altnautica.gcs.ui.maps.TileDownloadScreen
import com.altnautica.gcs.ui.mission.MissionPlannerScreen
import com.altnautica.gcs.ui.navigation.NavRoutes
import com.altnautica.gcs.ui.settings.SettingsScreen
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.SurfaceDark
import com.altnautica.gcs.ui.video.VideoScreen

@Composable
fun ADOSApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
            ) {
                NavRoutes.all.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = DeepBlack,
                        ),
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.Video.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoutes.Video.route) {
                VideoScreen()
            }
            composable(NavRoutes.Map.route) {
                GroundStationScreen()
            }
            composable(NavRoutes.GCS.route) {
                GcsScreen()
            }
            composable(NavRoutes.Agriculture.route) {
                AgricultureScreen()
            }
            composable(NavRoutes.MissionPlanner.route) {
                MissionPlannerScreen()
            }
            composable(NavRoutes.Gallery.route) {
                VideoGalleryScreen()
            }
            composable(NavRoutes.TileDownload.route) {
                TileDownloadScreen()
            }
            composable(NavRoutes.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

