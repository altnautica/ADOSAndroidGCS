package com.altnautica.gcs.radio.rx.uplink

import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The ground station's uplink route, on its LAN address. */
data class BoxTarget(val url: HttpUrl, val apiKey: String?) {
    companion object {
        /** [host] as the invite carries it (`192.168.1.20`, `host:port`); null when it is not an address. */
        fun of(host: String, apiKey: String?): BoxTarget? =
            AgentEndpoint.forHost(host, apiKey)?.let { BoxTarget(it.url(ROUTE), apiKey) }

        const val ROUTE = "api/v1/ground-station/uplink"
    }
}

/** The box's answer to a take or give-back. */
sealed interface BoxReply {
    data class Ok(val state: String?) : BoxReply

    /** The box refused; [detail] is its one sentence. */
    data class Refused(val code: String, val detail: String) : BoxReply

    /** No answer over the LAN. */
    data object Unreachable : BoxReply
}

/** The box's uplink as its `GET` route reports it. */
data class BoxUplinkView(
    val state: String,
    val holderDeviceId: String?,
    val holderName: String?,
    val sinceMs: Long?,
    val lastBeaconMs: Long?,
)

/** What a read of the box's uplink found. */
sealed interface BoxStatus {
    /** The box answered; [uplink] is null while its radio service is not reporting. */
    data class Reached(val uplink: BoxUplinkView?) : BoxStatus
    data object Unreachable : BoxStatus
}

/** One signed request: the exact body bytes and the headers that authenticate them. */
class SignedRequest(val body: ByteArray, val headers: Map<String, String>) {
    companion object {
        /**
         * `take`: the phone's ground device id and name, and the port the box
         * calls back on before a take-back.
         */
        fun take(holderId: String, holderName: String, yieldPort: Int, auth: PhoneAuth, nowMs: Long) = sign(
            buildJsonObject {
                put("op", JsonPrimitive("take"))
                put("holder_device_id", JsonPrimitive(holderId))
                put("holder_name", JsonPrimitive(holderName))
                put("yield_port", JsonPrimitive(yieldPort))
            },
            auth,
            nowMs,
        )

        fun giveBack(holderId: String, auth: PhoneAuth, nowMs: Long) = sign(
            buildJsonObject {
                put("op", JsonPrimitive("give_back"))
                put("holder_device_id", JsonPrimitive(holderId))
            },
            auth,
            nowMs,
        )

        fun sign(body: JsonObject, auth: PhoneAuth, nowMs: Long): SignedRequest {
            val bytes = body.toString().encodeToByteArray()
            return SignedRequest(
                bytes,
                mapOf(
                    UplinkMac.PHONE_KEY_HEADER to Base64.getEncoder().encodeToString(auth.phonePublic),
                    UplinkMac.TS_HEADER to nowMs.toString(),
                    UplinkMac.MAC_HEADER to UplinkMac.macBase64(UplinkMac.Domain.Request, auth.handoverKey, nowMs, bytes),
                ),
            )
        }
    }
}

/** The phone's identity toward one box: its invite public key and the handover key the invite carried. */
class PhoneAuth(val phonePublic: ByteArray, val handoverKey: ByteArray)

interface BoxUplinkApi {
    suspend fun post(target: BoxTarget, request: SignedRequest): BoxReply
    suspend fun status(target: BoxTarget): BoxStatus
}

/**
 * The box's uplink route over the LAN only: a take is never negotiated over
 * the radio. The node key rides in `X-ADOS-Key` as on every LAN call.
 */
@Singleton
class HttpBoxUplinkApi @Inject constructor(base: OkHttpClient) : BoxUplinkApi {

    internal val client = base.newBuilder()
        .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
        // The box answers a take only after its operation lock (an in-flight
        // resume holds it up to 8 s) and then both of its transmitters exiting
        // (up to 8 s more).
        .readTimeout(READ_S, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_S, TimeUnit.SECONDS)
        .build()

    override suspend fun post(target: BoxTarget, request: SignedRequest): BoxReply {
        val http = Request.Builder().url(target.url).post(request.body.toRequestBody(JSON)).apply {
            target.apiKey?.let { header(AgentHttp.KEY_HEADER, it) }
            request.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val (code, text) = exchange(http) ?: return BoxReply.Unreachable
        return parseReply(code, text)
    }

    override suspend fun status(target: BoxTarget): BoxStatus {
        val http = Request.Builder().url(target.url).get().apply {
            target.apiKey?.let { header(AgentHttp.KEY_HEADER, it) }
        }.build()
        val (code, text) = exchange(http) ?: return BoxStatus.Unreachable
        if (code !in 200..299) return BoxStatus.Unreachable
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return BoxStatus.Unreachable
        return BoxStatus.Reached(parseView(obj["uplink"] as? JsonObject))
    }

    private suspend fun exchange(request: Request): Pair<Int, String>? = try {
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { it.code to it.body.string() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val CONNECT_S = 4L
        private const val READ_S = 20L

        fun parseReply(code: Int, text: String): BoxReply {
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            fun str(key: String) = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (code in 200..299 && (obj?.get("ok") as? JsonPrimitive)?.content == "true") return BoxReply.Ok(str("state"))
            return BoxReply.Refused(
                code = str("error") ?: "HTTP_$code",
                detail = str("detail") ?: "The ground station refused the request (HTTP $code).",
            )
        }

        fun parseView(o: JsonObject?): BoxUplinkView? {
            o ?: return null
            fun str(key: String, from: JsonObject = o) = (from[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun long(key: String, from: JsonObject = o) = (from[key] as? JsonPrimitive)?.longOrNull
            val state = str("state") ?: return null
            return BoxUplinkView(
                state = state,
                holderDeviceId = str("holder_device_id"),
                holderName = str("holder_name"),
                sinceMs = long("since_ms"),
                lastBeaconMs = (o["watcher"] as? JsonObject)?.let { long("last_beacon_ms", it) },
            )
        }
    }
}
