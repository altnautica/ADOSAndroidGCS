package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the vision panel's engine card shows. */
enum class EngineCardState { Running, Starting, Off, Unreachable, Unavailable, StartTimeout, Checking }

/** How long a start (or restart) of the vision unit may take. */
const val VISION_START_MS = 30_000L

/**
 * The engine card's state from the stored [enabled] flag (null while unknown), the last
 * [status] read (null when it failed, [unreachable]) and the start in flight since [pendingSince].
 */
internal fun engineCardState(enabled: Boolean?, status: EngineStatus?, unreachable: Boolean, pendingSince: Long?, now: Long): EngineCardState {
    if (status != null && status.state != "unavailable") return EngineCardState.Running
    if (pendingSince != null) return if (now - pendingSince < VISION_START_MS) EngineCardState.Starting else EngineCardState.StartTimeout
    if (enabled == false) return EngineCardState.Off
    if (status != null) return EngineCardState.Unavailable
    if (unreachable) return EngineCardState.Unreachable
    return EngineCardState.Checking
}

/** A vision unit restart's answer: an "Unknown service" message or a 403/404 is a refusal. */
internal fun visionRestart(r: AgentResult<JsonElement>?): VisionChange = when (r) {
    null -> VisionChange.Failed(null)
    is AgentResult.Ok -> {
        val o = r.value as? JsonObject
        val message = (o?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val status = (o?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            message?.startsWith("Unknown service") == true -> VisionChange.Refused
            status == null || status == "ok" -> VisionChange.Ok
            else -> VisionChange.Failed(message)
        }
    }
    is AgentResult.Err -> when (val e = r.error) {
        AgentError.FeatureAbsent -> VisionChange.Refused
        is AgentError.Server -> if (e.code == 403 || e.code == 404) VisionChange.Refused else VisionChange.Failed(visionError(e))
        else -> VisionChange.Failed(visionError(e))
    }
}

/** An agent error's own words, when it gave any. */
internal fun visionError(e: AgentError): String? = when (e) {
    is AgentError.Server -> e.message
    is AgentError.PeerApi -> e.message
    is AgentError.PinRequired -> e.message
    is AgentError.Network -> e.cause.message
    else -> null
}

/**
 * The engine card, first in the vision panel: a status dot and line, then one action. Off
 * offers Turn on vision, an engine that stopped answering Restart vision; both disable while
 * armed, with the reason. [message] is the last action's failure, worded.
 */
@Composable
internal fun VisionEngineCardView(
    state: EngineCardState,
    status: EngineStatus?,
    armed: Boolean,
    message: String?,
    onTurnOn: () -> Unit,
    onRestart: () -> Unit,
) {
    val c = LocalChrome.current
    val (dot, line) = when (state) {
        EngineCardState.Running -> c.ok to listOfNotNull(stringResource(R.string.vision_engine_running), status?.backend, status?.models?.firstOrNull { it.loaded }?.id).joinToString(" · ")
        EngineCardState.Starting, EngineCardState.Checking -> c.warning to stringResource(R.string.vision_engine_starting)
        EngineCardState.Off -> c.textDim to stringResource(R.string.vision_engine_off)
        EngineCardState.StartTimeout -> c.critical to stringResource(R.string.vision_engine_start_timeout)
        EngineCardState.Unreachable, EngineCardState.Unavailable -> c.critical to stringResource(R.string.vision_engine_unreachable)
    }
    Column(Modifier.fillMaxWidth().testTag("vision-engine-card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.vision_engine_title), color = c.textDim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == EngineCardState.Starting) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Box(Modifier.size(10.dp).background(dot, CircleShape))
            }
            Text(line, color = c.text)
        }
        if (state == EngineCardState.Off) Text(stringResource(R.string.vision_engine_off_hint), color = c.textDim)
        when (state) {
            EngineCardState.Off -> Button(onClick = onTurnOn, enabled = !armed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vision-turn-on")) {
                Text(stringResource(R.string.vision_engine_turn_on))
            }
            EngineCardState.Unreachable, EngineCardState.Unavailable, EngineCardState.StartTimeout ->
                OutlinedButton(onClick = onRestart, enabled = !armed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vision-restart")) {
                    Text(stringResource(R.string.vision_engine_restart), color = c.text)
                }
            else -> Unit
        }
        if (armed && state != EngineCardState.Running) Text(stringResource(R.string.vision_engine_armed), color = c.textDim)
        message?.let { Text(it, color = c.textCritical) }
    }
}
