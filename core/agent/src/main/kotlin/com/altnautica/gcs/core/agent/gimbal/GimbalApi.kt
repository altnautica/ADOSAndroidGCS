package com.altnautica.gcs.core.agent.gimbal

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Whether the gimbal takes rate commands, and why not when it does not. */
@Serializable
data class GimbalRateStatus(val available: Boolean = false, val reason: String? = null)

/** Gimbal rate control (`/api/v1/gimbal/rate`). */
interface GimbalApi {
    /** `GET /api/v1/gimbal/rate`. */
    suspend fun rateStatus(): AgentResult<GimbalRateStatus>

    /**
     * `POST /api/v1/gimbal/rate {pitch_dps, yaw_dps, hold_ms}`: the agent holds the rate for
     * [holdMs] (100..2000) and stops the gimbal when no newer command arrives.
     */
    suspend fun setRate(pitchDps: Float, yawDps: Float, holdMs: Int): AgentResult<JsonElement>

    companion object {
        const val RATE_PATH = "api/v1/gimbal/rate"
        const val HOLD_MIN_MS = 100
        const val HOLD_MAX_MS = 2000
    }
}

internal class AgentGimbalApi(private val client: AgentClient) : GimbalApi {
    override suspend fun rateStatus(): AgentResult<GimbalRateStatus> =
        client.get(GimbalApi.RATE_PATH, GimbalRateStatus.serializer())

    override suspend fun setRate(pitchDps: Float, yawDps: Float, holdMs: Int): AgentResult<JsonElement> =
        client.sendJson("POST", GimbalApi.RATE_PATH, rateBody(pitchDps, yawDps, holdMs), JsonElement.serializer())
}

/** The rate body, with the hold clamped to the agent's accepted range. */
fun rateBody(pitchDps: Float, yawDps: Float, holdMs: Int): JsonElement = buildJsonObject {
    put("pitch_dps", JsonPrimitive(pitchDps))
    put("yaw_dps", JsonPrimitive(yawDps))
    put("hold_ms", JsonPrimitive(holdMs.coerceIn(GimbalApi.HOLD_MIN_MS, GimbalApi.HOLD_MAX_MS)))
}
