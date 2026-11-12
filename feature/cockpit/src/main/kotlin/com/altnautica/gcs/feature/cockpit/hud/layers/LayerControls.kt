package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText

@Composable
internal fun levelLabel(level: LayerLevel): String = specText(
    when (level) {
        LayerLevel.Instruments -> "cockpit.level.instruments"
        LayerLevel.Full -> "cockpit.level.full"
        LayerLevel.Route -> "cockpit.level.route"
        LayerLevel.Off -> "cockpit.level.off"
    },
)

@Composable
internal fun layerLabel(id: String): String = when (id) {
    LayerSpec.TARGETS -> specText("layer.name.targets")
    LayerSpec.AR_ROUTE -> specText("layer.name.ar_route")
    LayerSpec.AR_MOTION -> specText("layer.name.ar_motion")
    LayerSpec.OBSTACLES -> specText("layer.name.obstacles")
    LayerSpec.DEPTH -> specText("vision.depth")
    LayerSpec.SURFACES -> specText("vision.surfaces")
    else -> id
}

/** The layer level as a menu entry, where no icon is drawn: "Layers · <level>". */
@Composable
internal fun layerButtonText(level: LayerLevel): String = specText("cockpit.menu.layers") + " · " + levelLabel(level)

/**
 * The top-bar layer button: tap cycles the level (Instruments → Full → Route → Off), and
 * the ▾ beside it (or a long-press on the button) opens a checkbox for each layer. Both
 * persist per vehicle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayerLevelButton(controller: LayerController, modifier: Modifier = Modifier) {
    val flags by controller.flags.collectAsStateWithLifecycle()
    val menu = rememberAdosMenuState()
    val label = levelLabel(flags.level)
    val description = specText("cockpit.layers.next", "level" to label)
    val flagsLabel = specText("cockpit.layers.flags")
    // A top-bar control: the chrome control height drawn, a 48 dp touch target.
    val c = LocalChrome.current
    val shape = RoundedCornerShape(c.radiusSmall)
    Row(modifier.adosMenuAnchor(menu), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .height(c.control)
                .clip(shape)
                .border(c.borderWidth, c.border, shape)
                .combinedClickable(onClick = { controller.cycleLevel(1) }, onLongClick = { menu.expanded = true })
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp)
                .testTag("layer-level"),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Layers, contentDescription = null, tint = c.text, modifier = Modifier.size(c.icon).testTag("layer-level-icon"))
                Text(label, style = c.topBarText, maxLines = 1)
            }
        }
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .size(c.control)
                .clip(shape)
                .border(c.borderWidth, c.border, shape)
                .clickable(role = Role.DropdownList, onClickLabel = flagsLabel) { menu.expanded = true }
                .semantics { contentDescription = flagsLabel }
                .testTag("layer-flags"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = c.text)
        }
    }
    // An in-window menu: the activity input router and the D-pad focus trap reach it, unlike a popup window.
    AdosMenu(menu, layerFlagItems(controller, flags) { controller.spec.label(it) ?: layerLabel(it) })
}

/** One checkbox row per layer the canvas draws, in canvas order; a tap toggles it. */
@Composable
internal fun layerFlagItems(controller: LayerController, flags: LayerFlags, label: @Composable (String) -> String): List<AdosMenuItem> =
    controller.spec.canvasOrder.map { id ->
        val on = flags.isOn(id)
        AdosMenuItem(
            label = label(id),
            icon = if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            onClick = { controller.toggle(id) },
        )
    }
