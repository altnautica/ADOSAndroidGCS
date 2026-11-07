package com.altnautica.gcs.core.agent.fcsetup

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentJson
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.ws.AgentSockets
import com.altnautica.gcs.core.agent.ws.WsFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Flight-controller setup routes (`/api/v1/fc-setup/...`): the agent runs the
 * calibration and tuning wizards and every surface renders their steps.
 */
class FcSetupApi(private val client: AgentClient) {

    /** `GET /api/v1/fc-setup`: wizards, their steps and state, and the setup report. */
    suspend fun overview(): AgentResult<FcSetupOverview> = client.get(ROOT, FcSetupOverview.serializer())

    /** `POST /api/v1/fc-setup/{wizard}/start`. */
    suspend fun start(wizard: String): AgentResult<WizardReply> =
        client.sendJson("POST", "$ROOT/${AgentClient.segment(wizard)}/start", EMPTY, WizardReply.serializer())

    /** `POST /api/v1/fc-setup/{wizard}/steps/{step}/actions/{action}` with optional `{args}`. */
    suspend fun action(
        wizard: String,
        step: String,
        action: String,
        args: JsonObject? = null,
    ): AgentResult<StepReply> = client.sendJson(
        "POST",
        "${stepPath(wizard, step)}/actions/${AgentClient.segment(action)}",
        args?.let { buildJsonObject { put("args", it) } } ?: EMPTY,
        StepReply.serializer(),
        slow = true,
    )

    /** `POST /api/v1/fc-setup/{wizard}/steps/{step}/run`: run one step on its own. */
    suspend fun runStep(wizard: String, step: String): AgentResult<StepReply> =
        client.sendJson("POST", "${stepPath(wizard, step)}/run", EMPTY, StepReply.serializer(), slow = true)

    /** `POST /api/v1/fc-setup/{wizard}/abort`. */
    suspend fun abort(wizard: String): AgentResult<WizardReply> =
        client.sendJson("POST", "$ROOT/${AgentClient.segment(wizard)}/abort", EMPTY, WizardReply.serializer())

    /** `POST /api/v1/fc-setup/revert {snapshot}`: restore a parameter snapshot. */
    suspend fun revert(snapshot: String): AgentResult<RevertReply> = client.sendJson(
        "POST",
        "$ROOT/revert",
        buildJsonObject { put("snapshot", JsonPrimitive(snapshot)) },
        RevertReply.serializer(),
        slow = true,
    )

    /** Step and wizard updates from `WS /api/v1/fc-setup/stream`; keepalives are dropped. */
    fun stream(sockets: AgentSockets): Flow<FcSetupEvent> =
        sockets.open(client, STREAM_WS_PATH, STREAM_TICKET_SCOPE).mapNotNull { frame ->
            (frame as? WsFrame.Text)?.let { parseEvent(it.text) }
        }

    private fun stepPath(wizard: String, step: String) =
        "$ROOT/${AgentClient.segment(wizard)}/steps/${AgentClient.segment(step)}"

    companion object {
        const val ROOT = "api/v1/fc-setup"
        const val STREAM_WS_PATH = "api/v1/fc-setup/stream"
        const val STREAM_TICKET_SCOPE = "fc.setup"
        const val WIZARD_GROUND = "ground"
        const val WIZARD_AIR = "air"

        private val EMPTY = JsonObject(emptyMap())

        /** One stream text frame, or null for keepalives and unreadable frames. */
        fun parseEvent(text: String): FcSetupEvent? = try {
            val obj = AgentJson.json.parseToJsonElement(text).jsonObject
            val ts = obj["ts_ms"]?.jsonPrimitive?.longOrNull ?: 0L
            val data: JsonElement? = obj["data"]
            when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
                "step" -> data?.let { FcSetupEvent.Step(ts, AgentJson.json.decodeFromJsonElement(SetupStep.serializer(), it)) }
                "wizard" -> data?.let { FcSetupEvent.Wizard(ts, AgentJson.json.decodeFromJsonElement(SetupWizard.serializer(), it)) }
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
