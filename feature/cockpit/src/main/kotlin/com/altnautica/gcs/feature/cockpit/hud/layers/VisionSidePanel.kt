package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.altnautica.gcs.feature.cockpit.CockpitView
import com.altnautica.gcs.feature.cockpit.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.feature.cockpit.CockpitViewModel
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText
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
 * The `vision` view's side panel. A header with a close control (back to the pilot view), then
 * the engine card (Turn on vision when `vision.enabled` is off, Restart vision when it stops
 * answering; both refused while armed), one row per layer (switch, name, live status; a layer
 * the view forces on notes it and offers "Keep on in other views"), the depth options under
 * the Depth row while depth is on, the on-screen camera's detections, and the detector picker
 * (detection models only; long-press switches while disarmed, the engine restarts). Reads the
 * engine status every 5 s while shown, every 2 s while a start is in flight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VisionSidePanel(vm: CockpitViewModel, cameraId: String, modifier: Modifier = Modifier) {
    val layers = vm.autonomy.layers
    val batches by vm.autonomy.feed.batches.collectAsStateWithLifecycle()
    val flags by layers.flags.collectAsStateWithLifecycle()
    val baseFlags by layers.baseFlags.collectAsStateWithLifecycle()
    val depth by layers.depth.collectAsStateWithLifecycle()
    val view by layers.view.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<EngineStatus?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var pendingSince by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var needsAgent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(layers) {
        while (true) {
            val node = layers.nodeId
            val reply = node?.let { runCatching { layers.engine.status(it) }.getOrNull() }
            status = reply?.let(EngineStatus::parse)
            unreachable = reply == null
            enabled = node?.let { runCatching { layers.engine.enabled(it) }.getOrNull() } ?: enabled
            if (status?.state?.let { it != "unavailable" } == true) pendingSince = null
            delay(if (pendingSince != null) START_POLL_MS else STATUS_POLL_MS)
        }
    }
    val now = layers.now()
    val cardState = engineCardState(enabled, status, unreachable, pendingSince, now)
    val engineUp = when (cardState) {
        EngineCardState.Running -> true
        EngineCardState.Checking, EngineCardState.Starting -> null
        else -> false
    }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    fun change(action: suspend (com.altnautica.gcs.core.model.DeviceId) -> VisionChange) {
        val node = layers.nodeId ?: return
        message = null
        pendingSince = layers.now()
        scope.launch {
            when (val r = action(node)) {
                VisionChange.Ok -> enabled = true
                VisionChange.Refused -> {
                    pendingSince = null
                    // This agent does not restart the vision unit alone: offer the agent restart.
                    needsAgent = true
                }
                is VisionChange.Failed -> {
                    pendingSince = null
                    message = res.getString(R.string.vision_engine_failed, r.message ?: res.getString(R.string.vision_engine_unreachable))
                }
            }
        }
    }
    val chrome = LocalChrome.current
    ChromePanel(modifier) {
        Column(Modifier.fillMaxWidth()) {
            // The header stays put; only the body scrolls, so the close control is never covered.
            Row(Modifier.fillMaxWidth().padding(start = chrome.pad), verticalAlignment = Alignment.CenterVertically) {
                Text(specText("vision.title"), color = chrome.text, style = chrome.chipText, modifier = Modifier.weight(1f))
                val closeLabel = stringResource(R.string.vision_close)
                IconButton(
                    onClick = { vm.setView(CockpitView.Pilot) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = closeLabel }.testTag("vision-close"),
                ) {
                    Text("✕", color = chrome.text, style = chrome.chipText)
                }
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = chrome.pad, end = chrome.pad, bottom = chrome.pad),
                verticalArrangement = Arrangement.spacedBy(chrome.gap),
            ) {
                VisionEngineCardView(
                    cardState, status, layers.armed(), message,
                    onTurnOn = { change { layers.engine.turnOn(it) } },
                    onRestart = { change { layers.engine.restart(it) } },
                )
                if (needsAgent) {
                    Text(stringResource(R.string.vision_engine_after_agent_restart), color = chrome.textDim)
                    OutlinedButton(
                        onClick = {
                            needsAgent = false
                            change { layers.engine.restartAgent(it) }
                        },
                        enabled = !layers.armed(),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("vision-restart-agent"),
                    ) { Text(stringResource(R.string.vision_engine_restart_agent), color = chrome.text) }
                }

                Text(specText("vision.layers"), color = chrome.textDim)
                val forced = layers.spec.forced(view)
                for (id in listOf(LayerSpec.DEPTH, LayerSpec.SURFACES, LayerSpec.TARGETS)) {
                    val sw = layerSwitch(id, flags, forced)
                    val live = layerLive(id, layers, batches[cameraId] != null, now, engineUp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(layerLabel(id), color = chrome.text)
                            if (sw.checked) {
                                Text(
                                    when (live) {
                                        LayerLive.Receiving -> stringResource(R.string.vision_layer_receiving)
                                        LayerLive.NoData -> stringResource(R.string.vision_layer_no_data)
                                        LayerLive.EngineOff -> stringResource(R.string.vision_layer_engine_off)
                                    },
                                    color = if (live == LayerLive.Receiving) chrome.ok else chrome.textDim,
                                )
                            }
                            if (sw.forced) Text(specText("vision.layer_forced"), color = chrome.textDim)
                        }
                        Switch(
                            checked = sw.checked,
                            enabled = sw.enabled,
                            onCheckedChange = { layers.toggle(id) },
                            // A layer the view forces on reads on, not as a greyed-out off switch.
                            colors = SwitchDefaults.colors(
                                disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = FORCED_TRACK_ALPHA),
                                disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.testTag("vision-switch-$id"),
                        )
                    }
                    if (sw.forced) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).toggleable(baseFlags.isOn(id), role = Role.Checkbox) { layers.toggle(id) },
                        ) {
                            Checkbox(checked = baseFlags.isOn(id), onCheckedChange = null)
                            Text(specText("vision.keep_on"), color = chrome.textDim)
                        }
                    }
                    if (id == LayerSpec.DEPTH && sw.checked) DepthOptionsRow(depth, layers::setDepthOptions)
                }

                val items = batches[cameraId]?.items.orEmpty()
                Text(specText("vision.detections") + if (items.isNotEmpty()) " · ${items.size}" else "", color = chrome.textDim)
                if (items.isEmpty()) Text(stringResource(R.string.vision_detections_empty), color = chrome.textDim)
                for (d in items.sortedByDescending { it.confidence }.take(MAX_ROWS)) {
                    val pct = Math.round(d.confidence * 100)
                    val row = d.trackId?.let { specText("vision.detection_row_track", "id" to it.toString(), "label" to d.classLabel, "pct" to pct.toString()) }
                        ?: specText("vision.detection_row", "label" to d.classLabel, "pct" to pct.toString())
                    Text(row, color = chrome.text)
                }

                Text(specText("vision.detector_model"), color = chrome.textDim)
                for (m in status?.models.orEmpty().filter { it.kind == DETECTION_KIND }) {
                    val text = if (m.loaded) specText("vision.model_active", "model" to m.id) else specText("vision.model_switch", "model" to m.id)
                    Text(
                        text,
                        color = chrome.text,
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
        }
    }
}

/** The depth options, inside the Depth row while depth is on: style and range. */
@Composable
private fun DepthOptionsRow(depth: DepthOptions, set: (DepthOptions) -> Unit) {
    val chrome = LocalChrome.current
    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(specText("vision.depth_style"), color = chrome.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(depth.style == CellStyle.Solid, { set(depth.copy(style = CellStyle.Solid)) }, { Text(specText("vision.depth_style.solid")) })
            FilterChip(depth.style == CellStyle.Outline, { set(depth.copy(style = CellStyle.Outline)) }, { Text(specText("vision.depth_style.outline")) })
        }
        Text(specText("vision.depth_range"), color = chrome.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in DEPTH_RANGES) {
                val label = r?.let { specText("cockpit.unit.m", "n" to it.toInt().toString()) } ?: specText("vision.off")
                FilterChip(depth.rangeM == r, { set(depth.copy(rangeM = r)) }, { Text(label) })
            }
        }
    }
}

/** A layer row's live state: frames arriving, none for 2 s, or the engine that makes them is off. */
internal enum class LayerLive { Receiving, NoData, EngineOff }

internal fun layerLive(id: String, layers: LayerController, targetsBatch: Boolean, now: Long, engineUp: Boolean?): LayerLive {
    if (engineUp == false) return LayerLive.EngineOff
    val store = layers.store
    val fresh = when (id) {
        LayerSpec.DEPTH -> store.depth.ageMs(now)?.let { it <= LayerStore.HIDE_MS } == true
        LayerSpec.SURFACES -> store.surfaces.ageMs(now)?.let { it <= SURFACES_LIVE_MS } == true
        else -> targetsBatch
    }
    return if (fresh) LayerLive.Receiving else LayerLive.NoData
}

private const val SURFACES_LIVE_MS = 3_000L
private const val START_POLL_MS = 2_000L

/** How a layer switch shows: a layer the open view forces on reads on and cannot be switched here. */
internal data class LayerSwitch(val checked: Boolean, val enabled: Boolean, val forced: Boolean)

internal fun layerSwitch(id: String, flags: LayerFlags, forced: Set<String>): LayerSwitch =
    if (id in forced) LayerSwitch(checked = true, enabled = false, forced = true) else LayerSwitch(flags.isOn(id), enabled = true, forced = false)

private const val STATUS_POLL_MS = 5_000L
private const val DETECTION_KIND = "detection"
private const val MAX_ROWS = 12

/** The track of a switch the view holds on: the on colour, dimmed because it cannot be turned off here. */
private const val FORCED_TRACK_ALPHA = 0.55f
private val DEPTH_RANGES = listOf(null, 2f, 5f)
