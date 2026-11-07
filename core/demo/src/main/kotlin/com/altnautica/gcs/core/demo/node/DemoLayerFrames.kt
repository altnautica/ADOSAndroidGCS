package com.altnautica.gcs.core.demo.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The demo drone's overlay kinds (`ar`, `obstacles`, `depth`, `surfaces`), in the agent's
 * wire shape, animated so the cockpit layers move: a mission that cycles through its
 * states, an orbit ring, a follow path that detours, and an obstacle wall that drifts
 * across the frame. Depth and surfaces are produced only while a demand lease holds them,
 * as on a real agent.
 */
internal class DemoLayerFrames {
    private val leases = HashMap<String, Long>()

    /** Records a `POST /api/vision/demand` body: `{kind?, camera_id, hold_s}`. */
    fun demand(body: JsonObject, now: Long) {
        val kind = (body["kind"] as? JsonPrimitive)?.content ?: "detection"
        val hold = (body["hold_s"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        if (hold <= 0.0) leases.remove(kind) else leases[kind] = now + (hold * 1000).toLong()
    }

    /** The detector the demo reports active. */
    var activeModel = "yolo-nano-coco"

    /** A `GET /api/vision/status` reply: two registered detectors and a depth model. */
    fun status(): JsonObject = buildJsonObject {
        putJsonArray("models") {
            for ((id, kind) in listOf("yolo-nano-coco" to "detector", "yolo-small-coco" to "detector", "midas-small" to "depth")) {
                add(buildJsonObject {
                    put("id", id)
                    put("kind", kind)
                    put("execution", "cpu")
                    put("backend_loaded", id == activeModel || kind == "depth")
                    put("fps", if (id == activeModel) 3.0 else null)
                    put("latency_ms", if (id == activeModel) 41.0 else null)
                    put("is_inference_capable", true)
                })
            }
        }
        put("model_count", 3)
        put("backend", "onnx")
        put("backend_state", "ready")
        put("backend_reason", JsonNull)
    }

    private fun held(kind: String, now: Long) = (leases[kind] ?: 0L) > now

    /** The nav-stream frames for this tick. */
    fun frames(now: Long, envelope: (String, JsonObject) -> String): List<String> = buildList {
        add(envelope("ar", ar(now)))
        add(envelope("obstacles", obstacles(now)))
        if (held("depth", now)) add(envelope("depth", depth(now)))
        if (held("surfaces", now) && (now / 1000) != lastSurfacesS) {
            lastSurfacesS = now / 1000
            add(envelope("surfaces", surfaces(now)))
        }
    }

    private var lastSurfacesS = -1L

    private fun pts(vararg p: Pair<Double, Double>): JsonArray = buildJsonArray {
        p.forEach { (x, y) -> add(buildJsonArray { add(JsonPrimitive(r3(x))); add(JsonPrimitive(r3(y))) }) }
    }

    private fun r3(v: Double) = (v * 1000).roundToInt() / 1000.0

    fun ar(now: Long): JsonObject {
        val phase = (now % CYCLE_MS) / CYCLE_MS.toDouble() * 2 * PI
        val sway = 0.04 * sin(phase * 3)
        val missionState = MISSION_STATES[((now / STATE_MS) % MISSION_STATES.size).toInt()]
        val seq = 4 + ((now / STATE_MS) % 2).toInt()
        val orbitCx = 0.5 + 0.05 * cos(phase)
        val orbitCy = 0.62
        val ring = (0 until 36).map { i ->
            val a = i / 36.0 * 2 * PI + phase
            (orbitCx + 0.18 * cos(a)) to (orbitCy + 0.07 * sin(a))
        }
        val detour = sin(phase * 2) > 0.3
        return buildJsonObject {
            put("v", 1)
            put("ts_ms", now)
            put("approx", false)
            put("reason", JsonNull)
            putJsonObject("mission") {
                put("state", missionState)
                put("seq", seq)
                put("total", 6)
                put("item_reached", seq - 1)
                put("id", JsonNull)
                put("source", "mission_state")
                put("age_ms", 120)
                put("landing_seq", false)
            }
            putJsonObject("cameras") {
                putJsonObject(CAMERA) {
                    put("frame_w", FRAME_W)
                    put("frame_h", FRAME_H)
                    put("reason", JsonNull)
                    putJsonArray("items") {
                        add(buildJsonObject {
                            put("t", "wp"); put("k", "wp:$seq"); put("seq", seq); put("role", "next")
                            put("x", r3(0.52 + sway)); put("y", 0.41); put("gx", r3(0.52 + sway)); put("gy", 0.63)
                            put("dist_m", r3(120.0 - 20 * sin(phase))); put("eta_s", r3(42.0 - 7 * sin(phase))); put("size", 0.25)
                        })
                        add(buildJsonObject {
                            put("t", "wp"); put("k", "wp:${seq + 1}"); put("seq", seq + 1); put("role", "after")
                            put("x", r3(0.68 + sway)); put("y", 0.38); put("gx", JsonNull); put("gy", JsonNull)
                            put("dist_m", 180.0); put("eta_s", JsonNull); put("size", 0.25)
                        })
                        add(buildJsonObject {
                            put("t", "wp"); put("k", "wp:${seq + 2}"); put("seq", seq + 2); put("role", "other")
                            put("x", r3(0.8 + sway)); put("y", 0.36); put("size", 0.25)
                        })
                        add(buildJsonObject { put("t", "leg"); put("k", "leg:active"); put("role", "active"); put("pts", pts(0.5 to 0.9, 0.52 + sway to 0.63)) })
                        add(buildJsonObject { put("t", "leg"); put("k", "leg:next"); put("role", "next"); put("pts", pts(0.52 + sway to 0.63, 0.68 + sway to 0.6)) })
                        add(buildJsonObject { put("t", "leg"); put("k", "leg:future"); put("role", "future"); put("pts", pts(0.68 + sway to 0.6, 0.8 + sway to 0.58)) })
                        add(buildJsonObject {
                            put("t", "home"); put("k", "home"); put("x", r3(0.1 + sway)); put("y", 0.8); put("gx", r3(0.1 + sway)); put("gy", 0.85); put("dist_m", 35.0)
                        })
                        add(buildJsonObject { put("t", "fpv"); put("k", "fpv"); put("x", r3(0.5 + sway / 2)); put("y", 0.55) })
                        add(buildJsonObject {
                            put("t", "fpath"); put("k", "fpath"); put("state", if (detour) "detour" else "clear")
                            put("pts", pts(0.5 to 0.7, (if (detour) 0.42 else 0.47) to 0.64, 0.45 + sway to 0.6))
                        })
                        add(buildJsonObject { put("t", "fgoal"); put("k", "fgoal"); put("x", r3(0.45 + sway)); put("y", 0.6) })
                        add(buildJsonObject {
                            put("t", "orbit"); put("k", "orbit"); put("dir", "cw"); put("cx", r3(orbitCx)); put("cy", orbitCy)
                            put("pts", pts(*ring.toTypedArray()))
                        })
                        add(buildJsonObject {
                            put("t", "edge"); put("k", "edge:home"); put("of", "home"); put("seq", JsonNull)
                            put("ang", r3(2.6 + 0.3 * sin(phase))); put("dist_m", 35.0)
                        })
                    }
                }
            }
        }
    }

    /** A wall of near cells that drifts across the lower frame; sectors match it ahead. */
    fun obstacles(now: Long): JsonObject {
        val phase = (now % CYCLE_MS) / CYCLE_MS.toDouble()
        val wallCol = (phase * (COLS + 8)).toInt() - 4
        val near = ByteArray(COLS * ROWS)
        val measured = ByteArray(COLS * ROWS / 8)
        for (r in 9 until ROWS) for (c in 0 until COLS) {
            val i = r * COLS + c
            val dc = kotlin.math.abs(c - wallCol)
            if (dc <= 3) {
                near[i] = code(1.2f + dc * 1.1f + (ROWS - r) * 0.05f).toByte()
                if (dc <= 1) measured[i ushr 3] = (measured[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
            } else if (r >= 15) {
                near[i] = code(8f).toByte()
            }
        }
        return buildJsonObject {
            put("v", 1)
            put("ts_ms", now)
            putJsonArray("sectors_m") {
                for (s in 0 until 72) {
                    val deg = if (s > 36) (s - 72) * 5 else s * 5
                    val wallDeg = (wallCol - COLS / 2) * 2.5
                    if (kotlin.math.abs(deg - wallDeg) <= 10) add(JsonPrimitive(r3(1.4 + kotlin.math.abs(deg - wallDeg) * 0.1))) else add(JsonPrimitive(12.0))
                }
            }
            putJsonObject("cameras") {
                putJsonObject(CAMERA) {
                    put("cols", COLS)
                    put("rows", ROWS)
                    put("near", b64(near))
                    put("measured", b64(measured))
                }
            }
        }
    }

    /** Depth falling off toward the horizon, scaled. */
    fun depth(now: Long): JsonObject {
        val near = ByteArray(COLS * ROWS)
        val wobble = sin(now / 1000.0) * 0.3
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val m = if (r < 7) 0f else (1.5 + (ROWS - r) * 0.35 + wobble + kotlin.math.abs(c - COLS / 2) * 0.05).toFloat()
            near[r * COLS + c] = if (m == 0f) 0 else code(m).toByte()
        }
        val measured = ByteArray(COLS * ROWS / 8)
        return buildJsonObject {
            put("v", 1)
            put("ts_ms", now)
            put("scale_source", "lidar")
            putJsonObject("cameras") {
                putJsonObject(CAMERA) {
                    put("cols", COLS)
                    put("rows", ROWS)
                    put("near", b64(near))
                    put("measured", b64(measured))
                }
            }
        }
    }

    fun surfaces(now: Long): JsonObject = buildJsonObject {
        put("v", 1)
        put("ts_ms", now)
        put("camera_id", CAMERA)
        putJsonArray("zones") {
            add(buildJsonObject {
                put("pts", pts(0.2 to 0.78, 0.34 to 0.76, 0.36 to 0.9, 0.18 to 0.92))
                put("score", 0.86)
                put("slope_deg", 3.2)
            })
            add(buildJsonObject {
                put("pts", pts(0.66 to 0.8, 0.78 to 0.79, 0.8 to 0.9, 0.64 to 0.91))
                put("score", 0.58)
                put("slope_deg", 7.5)
            })
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun code(m: Float): Int = (1 + (ln(m.coerceIn(0.2f, 60f) / 0.2) * 254.0 / ln(300.0)).roundToInt()).coerceIn(1, 255)

    @Suppress("unused")
    private fun metres(v: Int) = 0.2 * exp((v - 1) * ln(300.0) / 254.0)

    private companion object {
        const val CAMERA = "main"
        const val FRAME_W = 1280
        const val FRAME_H = 720
        const val COLS = 32
        const val ROWS = 18
        const val CYCLE_MS = 12_000L
        const val STATE_MS = 6_000L
        val MISSION_STATES = listOf("active", "active", "paused", "active", "returning", "loaded")
    }
}
