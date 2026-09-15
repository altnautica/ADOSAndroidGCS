package com.altnautica.gcs.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.R
import com.altnautica.gcs.ui.navigation.NavRoutes
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.WarningAmber
import com.altnautica.gcs.ui.video.VideoViewModel

private data class ModeCard(
    val route: String,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val icon: ImageVector,
    val color: Color,
)

private val modeCards = listOf(
    ModeCard(
        NavRoutes.Fly.route,
        R.string.home_fly_label,
        R.string.home_fly_description,
        Icons.Filled.Flight,
        ElectricBlue,
    ),
    ModeCard(
        NavRoutes.Map.route,
        R.string.home_map_label,
        R.string.home_map_description,
        Icons.Filled.Map,
        SuccessGreen,
    ),
    ModeCard(
        NavRoutes.Plan.route,
        R.string.home_plan_label,
        R.string.home_plan_description,
        Icons.Filled.Assignment,
        WarningAmber,
    ),
    ModeCard(
        NavRoutes.Agriculture.route,
        R.string.home_agriculture_label,
        R.string.home_agriculture_description,
        Icons.Filled.Agriculture,
        NeonLime,
    ),
    ModeCard(
        NavRoutes.Configure.route,
        R.string.home_configure_label,
        R.string.home_configure_description,
        Icons.Filled.Tune,
        ElectricBlue,
    ),
    ModeCard(
        NavRoutes.Logs.route,
        R.string.home_logs_label,
        R.string.home_logs_description,
        Icons.Filled.History,
        OnSurfaceMedium,
    ),
    // The ground-station surface: radio link stats, the diversity panel,
    // recording, system info and the node service restart. All of it was built
    // and wired to a ViewModel with no route and no card, so none of it was
    // reachable in a shipped build.
    ModeCard(
        NavRoutes.GroundStation.route,
        R.string.home_station_label,
        R.string.home_station_description,
        Icons.Filled.SettingsInputAntenna,
        NeonLime,
    ),
)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    videoViewModel: VideoViewModel = hiltViewModel(),
) {
    val battery by videoViewModel.battery.collectAsStateWithLifecycle()
    val gps by videoViewModel.gps.collectAsStateWithLifecycle()
    val videoMode by videoViewModel.videoMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = { onNavigate(NavRoutes.Settings.route) }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.nav_settings),
                    tint = OnSurfaceMedium,
                )
            }
        }

        // Mode grid
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCardItem(
                    card = modeCards[0],
                    onClick = { onNavigate(modeCards[0].route) },
                    modifier = Modifier.weight(1f),
                )
                ModeCardItem(
                    card = modeCards[1],
                    onClick = { onNavigate(modeCards[1].route) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCardItem(
                    card = modeCards[2],
                    onClick = { onNavigate(modeCards[2].route) },
                    modifier = Modifier.weight(1f),
                )
                ModeCardItem(
                    card = modeCards[3],
                    onClick = { onNavigate(modeCards[3].route) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCardItem(
                    card = modeCards[4],
                    onClick = { onNavigate(modeCards[4].route) },
                    modifier = Modifier.weight(1f),
                )
                ModeCardItem(
                    card = modeCards[5],
                    onClick = { onNavigate(modeCards[5].route) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCardItem(
                    card = modeCards[6],
                    onClick = { onNavigate(modeCards[6].route) },
                    modifier = Modifier.weight(1f),
                )
                // Odd card count: the spacer keeps the last card the same width
                // as the rows above rather than stretching it across both
                // columns.
                Spacer(Modifier.weight(1f))
            }
        }

        // Bottom status bar
        ConnectionStatus(
            videoMode = videoMode,
            batteryPercent = battery.remaining,
            gpsFixType = gps.fixType,
            satellites = gps.satellites,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ModeCardItem(
    card: ModeCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            card.color.copy(alpha = 0.15f),
                            card.color.copy(alpha = 0.05f),
                        ),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = stringResource(card.label),
                    tint = card.color,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(card.label),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = card.color,
                    fontSize = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(card.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMedium,
                )
            }
        }
    }
}
