package com.altnautica.gcs.core.demo.node

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * The demo's scripted follow target: a person walks across the front camera while the
 * follow skill cycles through acquiring, following, predicting, moving to look, searching,
 * re-acquired and holding for the operator ([STEP_MS] each). Tracks and locks, the `follow`
 * state and the follow events exist only while the pilot reports the follow skill flying
 * ([DemoPilotView.followRunning]); otherwise the detector reports untracked candidates.
 */
internal class DemoFollowFeed {
    @Volatile var reacquireMode = "smart"

    /** The resume sequence the waiting prompt's search again writes; echoed in the state. */
    @Volatile var operatorResumeSeq = 0

    /**
     * 0 candidate, 1 tracked, 2 acquiring, 3 following, 4 predicting, 5 moving to look,
     * 6 searching, 7 re-acquired, 8 holding for the operator.
     */
    fun step(now: Long) = ((now / STEP_MS) % STEPS).toInt()

    /** The follow phase degrades the position estimate while it searches. */
    fun searching(step: Int) = step == 6

    private fun personX(now: Long) = 560f + 220f * sin(now / 3_000.0).toFloat()

    /** One detection batch of the front camera; the walker is tracked and locked only while [following]. */
    fun detections(now: Long, frameId: Long, following: Boolean): JsonObject {
        val s = step(now)
        val x = personX(now)
        val items = buildJsonArray {
            // A second walker that never gets a track: always a candidate.
            add(det(980f, 330f, 40f, 104f, 0.64f, null, null))
            when {
                !following -> add(det(x, 280f, 60f, 150f, 0.82f, null, null))
                s == 0 -> add(det(x, 280f, 60f, 150f, 0.82f, null, null))
                s in 1..3 -> add(det(x, 280f, 60f, 150f, 0.97f, 12, "locked"))
                s == 7 -> add(det(x, 280f, 60f, 150f, 0.91f, 15, "locked"))
                else -> Unit
            }
        }
        return buildJsonObject {
            put("v", 2)
            put("camera_id", CAMERA)
            put("frame_id", frameId)
            put("ts_ms", now)
            put("frame_width", FRAME_W)
            put("frame_height", FRAME_H)
            put("detections", items)
        }
    }

    private fun det(x: Float, y: Float, w: Float, h: Float, conf: Float, track: Int?, lock: String?) = buildJsonObject {
        putJsonObject("bbox") {
            put("x", x)
            put("y", y)
            put("width", w)
            put("height", h)
        }
        put("class_label", "person")
        put("confidence", conf)
        put("track_id", track?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lock_state", lock?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /**
     * The follow event (topic and data) due at [now]: re-acquired or lost on entering that step
     * while [following]. [previousStep] is the step the caller last saw while following (null
     * while not), so there is none while the follow skill is not flying, nor on the tick it starts.
     */
    fun event(previousStep: Int?, now: Long, following: Boolean): Pair<String, JsonObject>? {
        val s = step(now)
        if (!following || previousStep == null || s == previousStep) return null
        return when (s) {
            7 -> "follow.reacquired" to buildJsonObject {
                put("track_id", 15); put("similarity", 0.862); put("gap_s", 13.8); put("mahalanobis", 1.2); put("phase", "searching")
            }
            8 -> "follow.lost" to buildJsonObject {
                put("track_id", 15); put("reason", "search_exhausted"); put("awaiting_operator", true)
                putJsonArray("hold_ne") { add(JsonPrimitive(1.0)); add(JsonPrimitive(0.5)) }
            }
            else -> null
        }
    }

    /** The `follow` state at [step]; [aglM] is the pilot's height above ground. */
    fun follow(step: Int, now: Long, yaw: Float, aglM: Double) = buildJsonObject {
        val phase = when (step) {
            0, 1 -> "idle"
            2 -> "acquiring"
            3, 7 -> "following"
            4 -> "predicting"
            5 -> "repositioning"
            6 -> "searching"
            else -> "holding-await-operator"
        }
        val recovering = step in 4..6
        val inPhase = (now % STEP_MS) / 1000.0
        put("phase", phase)
        put("track_id", if (step >= 7) 15 else 12)
        put("confidence", 0.97)
        put("range_m", 10.2)
        put("range_sigma_m", 0.3)
        putJsonArray("range_cues") {
            add(buildJsonObject { put("kind", "ground"); put("range_m", 10.1); put("sigma_m", 0.4) })
            add(buildJsonObject { put("kind", "box_height"); put("range_m", 10.4); put("sigma_m", 1.5) })
        }
        put("target_speed_mps", 1.4)
        put("agl_m", aglM)
        put("agl_source", "rng")
        put("reacquire_mode", reacquireMode)
        put("reacquire_remaining_s", if (recovering) SEARCH_BUDGET_S - (step - 4) * STEP_MS / 1000.0 - inPhase else 0.0)
        put("search_budget_s", if (recovering) JsonPrimitive(SEARCH_BUDGET_S) else JsonNull)
        put("search_step", if (step == 6) JsonPrimitive(minOf(2, (inPhase / 1.5).toInt())) else JsonNull)
        if (recovering) {
            putJsonObject("search_area") {
                putJsonArray("center_ne") { add(JsonPrimitive(5.5)); add(JsonPrimitive(1.5)) }
                put("semi_major_m", 2 + inPhase); put("semi_minor_m", 1 + inPhase / 3); put("heading_deg", 30.0)
            }
            putJsonArray("predicted_path_ne") { for (k in 0..3) add(buildJsonArray { add(JsonPrimitive(5.5 + 0.6 * k)); add(JsonPrimitive(1.5 + 0.35 * k)) }) }
        }
        if (step == 5 || step == 6) {
            putJsonArray("vantage_ne") { add(JsonPrimitive(2.5)); add(JsonPrimitive(3.0)) }
            put("vantage_state", if (step == 5) "enroute" else "arrived")
        }
        if (step == 6) putJsonObject("occluder") { putJsonArray("ne") { add(JsonPrimitive(4.5)); add(JsonPrimitive(1.0)) }; put("kind", "narrow") }
        if (step == 8) putJsonArray("hold_ne") { add(JsonPrimitive(1.0)); add(JsonPrimitive(0.5)) }
        put("uncertain_mode", "follow")
        put("predictive_search", true)
        put("operator_resume_seq", operatorResumeSeq)
        put("pipeline_latency_ms", 85)
        put("hold_reason", if (step == 8) JsonPrimitive("search-exhausted") else JsonNull)
        put("bearing_deg", 64.0)
        put("depression_deg", 22.0)
        putJsonArray("target_ned") { add(JsonPrimitive(5.5)); add(JsonPrimitive(1.5)) }
        putJsonArray("target_vel_ned") { add(JsonPrimitive(1.2 * cos(yaw))); add(JsonPrimitive(1.2 * sin(yaw))) }
        if (step == 4 || step == 6) {
            putJsonObject("predicted_bbox") {
                put("camera_id", CAMERA)
                put("frame_w", FRAME_W)
                put("frame_h", FRAME_H)
                put("x", personX(now) + 40f)
                put("y", 280f)
                put("w", 60f)
                put("h", 150f)
            }
        } else {
            put("predicted_bbox", JsonNull)
        }
    }

    companion object {
        const val CAMERA = "main"
        const val FRAME_W = 1280
        const val FRAME_H = 720
        const val STEP_MS = 4_000L
        const val STEPS = 9
        const val SEARCH_BUDGET_S = 25.0
    }
}
