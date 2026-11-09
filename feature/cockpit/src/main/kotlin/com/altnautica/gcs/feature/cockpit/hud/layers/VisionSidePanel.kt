package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.CockpitViewModel
import com.altnautica.gcs.feature.cockpit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One registered model from `GET /api/vision/status`. */
internal data class EngineModel(val id: String, val kind: String, val loaded: Boolean, val fps: Double?)

/** The parts of `GET /api/vision/status` the panel shows. */
internal data class EngineStatus(val state: String, val backend: String?, val models: List<EngineModel>) {
    companion object {
        fun parse(o: JsonObject): EngineStatus {
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val models = (o["models"] as? JsonArray).orEmpty().mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                EngineModel(
                    id = m.str("id") ?: return@mapNotNull null,
                    kind = m.str("kind").orEmpty(),
                    loaded = (m["backend_loaded"] as? JsonPrimitive)?.booleanOrNull == true,
                    fps = (m["fps"] as? JsonPrimitive)?.doubleOrNull,
                )
            }
            return EngineStatus(o.str("backend_state") ?: "unavailable", o.str("backend"), models)
        }
    }
}

/**
 * The `vision` view's side panel: the on-screen camera's detections, the detector
 * picker (detection models only; long-press switches while disarmed, the engine restarts), the depth and surfaces toggles with
 * the depth options, and the engine's health. Reads the engine status every 5 s while shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VisionSidePanel(vm: CockpitViewModel, cameraId: String, modifier: Modifier = Modifier) {
    val layers = vm.autonomy.layers
    val batches by vm.autonomy.feed.batches.collectAsStateWithLifecycle()
    val flags by layers.flags.collectAsStateWithLifecycle()
    val depth by layers.depth.collectAsStateWithLifecycle()
    val view by layers.view.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<EngineStatus?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(layers) {
        while (true) {
            val reply = layers.nodeId?.let { runCatching { layers.engine.status(it) }.getOrNull() }
            status = reply?.let(EngineStatus::parse)
            unreachable = reply == null
            delay(STATUS_POLL_MS)
        }
    }
    val c = AdosTheme.colors
    Column(
        modifier
            .background(c.mediaScrim, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.vision_panel_title), color = c.mediaOn)

        Text(stringResource(R.string.vision_detections), color = c.mediaOn)
        val items = batches[cameraId]?.items.orEmpty()
        if (items.isEmpty()) Text(stringResource(R.string.vision_detections_none), color = c.mediaOn.copy(alpha = 0.7f))
        for (d in items.sortedByDescending { it.confidence }.take(MAX_ROWS)) {
            val pct = Math.round(d.confidence * 100)
            val row = d.trackId?.let { stringResource(R.string.vision_detection_row_track, d.classLabel, pct, it) }
                ?: stringResource(R.string.vision_detection_row, d.classLabel, pct)
            Text(row, color = c.mediaOn)
        }

        Text(stringResource(R.string.vision_model), color = c.mediaOn)
        val s = status
        if (s == null) {
            if (unreachable) Text(stringResource(R.string.vision_model_unavailable), color = c.mediaOn.copy(alpha = 0.7f))
        } else {
            for (m in s.models.filter { it.kind == DETECTION_KIND }) {
                val text = if (m.loaded) stringResource(R.string.vision_model_active, m.id) else stringResource(R.string.vision_model_switch, m.id)
                Text(
                    text,
                    color = c.mediaOn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .combinedClickable(
                            // Switching restarts the engine, so it is a ground-only action.
                            enabled = !m.loaded && !layers.armed(),
                            onClick = {},
                            onLongClick = { layers.nodeId?.let { id -> scope.launch { layers.engine.setDetector(id, m.id) } } },
                        )
                        .padding(vertical = 12.dp),
                )
            }
        }

        val forced = layers.spec.forced(view)
        for (id in listOf(LayerSpec.DEPTH, LayerSpec.SURFACES)) {
            val sw = layerSwitch(id, flags, forced)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(layerLabel(id), color = c.mediaOn)
                    if (sw.forced) Text(stringResource(R.string.vision_layer_forced), color = c.mediaOn.copy(alpha = 0.7f))
                }
                Switch(checked = sw.checked, enabled = sw.enabled, onCheckedChange = { layers.toggle(id) })
            }
        }
        Text(stringResource(R.string.vision_depth_style), color = c.mediaOn)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(depth.style == CellStyle.Solid, { layers.setDepthOptions(depth.copy(style = CellStyle.Solid)) }, { Text(stringResource(R.string.vision_depth_solid)) })
            FilterChip(depth.style == CellStyle.Outline, { layers.setDepthOptions(depth.copy(style = CellStyle.Outline)) }, { Text(stringResource(R.string.vision_depth_outline)) })
        }
        Text(stringResource(R.string.vision_depth_range), color = c.mediaOn)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in DEPTH_RANGES) {
                val label = r?.let { stringResource(R.string.vision_depth_range_m, it.toInt()) } ?: stringResource(R.string.vision_depth_range_off)
                FilterChip(depth.rangeM == r, { layers.setDepthOptions(depth.copy(rangeM = r)) }, { Text(label) })
            }
        }

        Text(stringResource(R.string.vision_health), color = c.mediaOn)
        s?.let { st ->
            val fps = st.models.firstOrNull { it.loaded && it.kind == DETECTION_KIND }?.fps
            Text(
                if (fps != null) stringResource(R.string.vision_health_rate, st.state, fps) else stringResource(R.string.vision_health_state, st.state),
                color = c.mediaOn,
            )
        }
    }
}

/** How a layer switch shows: a layer the open view forces on reads on and cannot be switched here. */
internal data class LayerSwitch(val checked: Boolean, val enabled: Boolean, val forced: Boolean)

internal fun layerSwitch(id: String, flags: LayerFlags, forced: Set<String>): LayerSwitch =
    if (id in forced) LayerSwitch(checked = true, enabled = false, forced = true) else LayerSwitch(flags.isOn(id), enabled = true, forced = false)

private const val STATUS_POLL_MS = 5_000L
private const val DETECTION_KIND = "detection"
private const val MAX_ROWS = 12
private val DEPTH_RANGES = listOf(null, 2f, 5f)
