package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.R

@Composable
internal fun levelLabel(level: LayerLevel): String = stringResource(
    when (level) {
        LayerLevel.Full -> R.string.layers_level_full
        LayerLevel.Route -> R.string.layers_level_route
        LayerLevel.Off -> R.string.layers_level_off
    },
)

@Composable
internal fun layerLabel(id: String): String = when (id) {
    LayerSpec.TARGETS -> stringResource(R.string.layer_targets)
    LayerSpec.AR_ROUTE -> stringResource(R.string.layer_ar_route)
    LayerSpec.AR_MOTION -> stringResource(R.string.layer_ar_motion)
    LayerSpec.OBSTACLES -> stringResource(R.string.layer_obstacles)
    LayerSpec.DEPTH -> stringResource(R.string.layer_depth)
    LayerSpec.SURFACES -> stringResource(R.string.layer_surfaces)
    else -> id
}

/**
 * The top-bar layer button: tap cycles the level (Full → Route → Off), long-press opens
 * a toggle for each layer. Both persist per vehicle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayerLevelButton(controller: LayerController, modifier: Modifier = Modifier) {
    val flags by controller.flags.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val label = levelLabel(flags.level)
    val description = stringResource(R.string.layers_button_description, label)
    Box(modifier) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .background(AdosTheme.colors.mediaScrim, RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { controller.cycleLevel(1) }, onLongClick = { menu = true })
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.layers_button, label), color = AdosTheme.colors.mediaOn)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (id in controller.spec.canvasOrder) {
                DropdownMenuItem(
                    text = { Text(layerLabel(id)) },
                    onClick = { controller.toggle(id) },
                    trailingIcon = { Checkbox(checked = flags.isOn(id), onCheckedChange = { controller.toggle(id) }) },
                )
            }
        }
    }
}
