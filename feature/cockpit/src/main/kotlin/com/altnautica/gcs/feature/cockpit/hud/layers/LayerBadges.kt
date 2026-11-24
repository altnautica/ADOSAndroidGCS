package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.flow.specText

/**
 * What an unavailable-layer badge's menu does: [onCloseView] leaves a view that forces the
 * layer on (the Vision view) for the pilot view, [onSetUp] opens the vision settings, and
 * [engineUp] reads whether the vision engine answers (null while unknown).
 */
class LayerBadgeActions(
    val onCloseView: () -> Unit,
    val onSetUp: () -> Unit,
    val engineUp: suspend () -> Boolean?,
)

/** The settings section a badge's Set up and the vision panel open (the agent's vision settings). */
const val VISION_SETTINGS = "vision"

/** Why a layer badge shows: the vision engine that makes it is off, or no frame came for 2 s. */
enum class BadgeReason { EngineOff, NoData }

/** The layers that come from the vision engine; the rest (obstacles, AR) come from navigation. */
internal val VISION_LAYERS = setOf(LayerSpec.DEPTH, LayerSpec.SURFACES, LayerSpec.TARGETS)

/** A badge's reason: a vision layer reads engine off when the engine does not answer. */
fun badgeReason(layer: String, engineUp: Boolean?): BadgeReason =
    if (layer in VISION_LAYERS && engineUp == false) BadgeReason.EngineOff else BadgeReason.NoData

/**
 * The layers that are on but draw nothing for lack of data, in canvas order: the same tests
 * the canvas makes before it would draw a layer's unavailable chip.
 */
internal fun hiddenLayers(controller: LayerController, cameraId: String, f: LayerFlags, targets: CanvasTargets?, now: Long): List<String> {
    val store = controller.store
    val out = ArrayList<String>(2)
    for (id in controller.spec.canvasOrder) {
        if (!f.isOn(id)) continue
        val hidden = when (id) {
            LayerSpec.DEPTH -> stale(store.depth, now, LayerStore.HIDE_MS)
            LayerSpec.SURFACES -> stale(store.surfaces, now, SURFACES_BADGE_HIDE_MS)
            LayerSpec.OBSTACLES -> store.obstacles.cur != null && stale(store.obstacles, now, LayerStore.HIDE_MS)
            LayerSpec.TARGETS -> targets != null && targetsFreshness(targets, now) == Freshness.Hidden
            LayerSpec.AR_ROUTE -> store.ar.cur.let { frame ->
                frame != null && (stale(store.ar, now, LayerStore.HIDE_MS) || frame.reason != null || frame.cameras[cameraId]?.reason != null)
            }
            else -> false
        }
        if (hidden) out += id
    }
    return out
}

private fun stale(slot: LayerSlot<*>, now: Long, hideMs: Long): Boolean = (slot.ageMs(now) ?: return true) > hideMs

private const val SURFACES_BADGE_HIDE_MS = 3_000L

/**
 * The unavailable-layer badges as buttons, stacked up from the bottom-left of the video
 * inside [inset]. Each opens a menu: the reason, Turn off (Close Vision view when the open
 * view forces the layer on) and Set up (the vision settings). Each is a 48 dp target.
 */
@Composable
internal fun LayerBadges(hidden: List<String>, controller: LayerController, inset: PaddingValues, actions: LayerBadgeActions) {
    val view by controller.view.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().padding(inset).padding(8.dp), contentAlignment = Alignment.BottomStart) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in hidden) LayerBadge(id, forced = id in controller.spec.forced(view), controller, actions)
        }
    }
}

@Composable
private fun LayerBadge(id: String, forced: Boolean, controller: LayerController, actions: LayerBadgeActions) {
    val menu = rememberAdosMenuState()
    var engineUp by remember { mutableStateOf<Boolean?>(null) }
    if (menu.expanded) LaunchedEffect(Unit) { engineUp = actions.engineUp() }
    val name = layerLabel(id)
    val reason = when (badgeReason(id, engineUp)) {
        BadgeReason.EngineOff -> stringResource(R.string.vision_badge_engine_off)
        BadgeReason.NoData -> stringResource(R.string.vision_badge_no_data, name)
    }
    Text(
        specText("layer.unavailable", "layer" to name),
        color = Color.White,
        modifier = Modifier
            .adosMenuAnchor(menu)
            .heightIn(min = 48.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClickLabel = name) { menu.expanded = true }
            .padding(horizontal = 10.dp, vertical = 14.dp)
            .testTag("layer-badge-$id"),
    )
    AdosMenu(
        menu,
        listOf(
            // The reason reads first; it is not an action.
            AdosMenuItem(reason, enabled = false) {},
            AdosMenuItem(stringResource(if (forced) R.string.vision_badge_close_view else R.string.vision_badge_turn_off)) {
                if (forced) actions.onCloseView() else controller.toggle(id)
            },
            AdosMenuItem(stringResource(R.string.vision_badge_set_up), onClick = actions.onSetUp),
        ),
    )
}
