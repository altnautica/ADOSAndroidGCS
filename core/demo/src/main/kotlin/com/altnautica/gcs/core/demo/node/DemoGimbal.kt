package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.gimbal.GimbalApi
import com.altnautica.gcs.core.agent.gimbal.GimbalRateStatus
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The demo drone's gimbal: takes rate commands and answers like the agent route. */
internal class DemoGimbal : GimbalApi {
    override suspend fun rateStatus(): AgentResult<GimbalRateStatus> = AgentResult.Ok(GimbalRateStatus(available = true))

    override suspend fun setRate(pitchDps: Float, yawDps: Float, holdMs: Int): AgentResult<JsonElement> = AgentResult.Ok(
        buildJsonObject {
            put("sent", JsonPrimitive(true))
            put("accepted", kotlinx.serialization.json.JsonNull)
            put("hold_ms", JsonPrimitive(holdMs.coerceIn(GimbalApi.HOLD_MIN_MS, GimbalApi.HOLD_MAX_MS)))
        },
    )
}
