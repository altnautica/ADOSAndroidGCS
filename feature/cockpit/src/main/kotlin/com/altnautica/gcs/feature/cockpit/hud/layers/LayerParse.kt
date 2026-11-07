package com.altnautica.gcs.feature.cockpit.hud.layers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.exp
import kotlin.math.ln

/** Decodes the overlay kinds of the navigation stream. Malformed payloads yield null. */
object LayerParse {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** The `data` object of a nav-stream frame of [kind], or null for another kind or a bad payload. */
    fun frame(kind: String?, data: JsonObject): LayerFrame? = runCatching {
        when (kind) {
            "ar" -> LayerFrame.Ar(json.decodeFromJsonElement(ArFrame.serializer(), data))
            "obstacles" -> LayerFrame.Obstacles(json.decodeFromJsonElement(ObstaclesFrame.serializer(), data))
            "depth" -> LayerFrame.Depth(json.decodeFromJsonElement(DepthFrame.serializer(), data))
            "surfaces" -> LayerFrame.Surfaces(json.decodeFromJsonElement(SurfacesFrame.serializer(), data))
            else -> null
        }
    }.getOrNull()

    fun ar(text: String): ArFrame = json.decodeFromString(ArFrame.serializer(), text)
    fun obstacles(text: String): ObstaclesFrame = json.decodeFromString(ObstaclesFrame.serializer(), text)
    fun depth(text: String): DepthFrame = json.decodeFromString(DepthFrame.serializer(), text)
    fun surfaces(text: String): SurfacesFrame = json.decodeFromString(SurfacesFrame.serializer(), text)
}

/**
 * The range-grid cell code: 0 is unknown; 1..255 decode to
 * `d = 0.2·exp((v−1)·ln(300)/254)` m, covering 0.2–60 m.
 */
object GridCodec {
    private val lut = FloatArray(256) { v -> if (v == 0) Float.NaN else (0.2 * exp((v - 1) * ln(300.0) / 254.0)).toFloat() }

    /** Metres for cell code [v] (0..255), NaN when unknown. */
    fun metres(v: Int): Float = lut[v and 0xFF]

    /** The cell code nearest to [m] metres (1..255); for tests and the demo producer. */
    fun code(m: Float): Int {
        val v = 1 + Math.round(ln(m.coerceIn(0.2f, 60f) / 0.2) * 254.0 / ln(300.0)).toInt()
        return v.coerceIn(1, 255)
    }

    private val b64 = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
    }

    /** Decodes base64 [s] into [out] without allocating; returns the decoded byte count, or -1 when malformed. */
    fun decode(s: String, out: ByteArray): Int {
        var acc = 0
        var bits = 0
        var n = 0
        for (ch in s) {
            if (ch == '=') break
            val v = if (ch.code < 128) b64[ch.code] else -1
            if (v < 0) return -1
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (n == out.size) return n
                out[n++] = (acc ushr bits).toByte()
            }
        }
        return n
    }

    /** True when bit [i] of a little-endian-per-byte bitmask is set. */
    fun bit(mask: ByteArray, i: Int): Boolean = (mask[i ushr 3].toInt() ushr (i and 7)) and 1 == 1
}
