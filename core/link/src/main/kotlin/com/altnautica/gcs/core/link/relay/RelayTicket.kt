package com.altnautica.gcs.core.link.relay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The per-pair relay ticket a drone demands with every relayed request: an
 * HMAC-SHA256, keyed from the pair's secret under its own label, over
 *
 * ```
 * v2|relay.http|{target}|{issued_at}|{expires_at}|{nonce}|{METHOD}|{path}|{sha256_hex(body)}
 * ```
 *
 * The token is that text followed by `|{hmac_hex}`. It binds the method, the
 * path (with query, as the drone will see it) and the body, lives
 * [LIFETIME_S] seconds, and carries a fresh 16-byte nonce the drone spends
 * once. [target] is the drone's device id.
 */
class RelayTicket(secret: ByteArray) {
    private val key: ByteArray = hmac(secret, LABEL)

    /** A ticket for one request to [target], issued at [nowS] (Unix seconds). */
    fun mint(target: String, method: String, path: String, body: ByteArray, nowS: Long, nonce: String = newNonce()): String {
        val payload = "v2|$SCOPE|$target|$nowS|${nowS + LIFETIME_S}|$nonce|$method|$path|${sha256Hex(body)}"
        return payload + "|" + hex(hmac(key, payload.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val SCOPE = "relay.http"
        const val LIFETIME_S = 30L

        /** The method a config-tunnel ticket binds; never an HTTP verb. */
        const val TUNNEL_METHOD = "TUNNEL"

        /** The path a config-tunnel ticket binds. */
        const val TUNNEL_PATH = "/api/config"
        private val LABEL = "ados-relay-ticket-v2".toByteArray(Charsets.US_ASCII)
        private val random = SecureRandom()

        /**
         * A ticket for the pair whose secret is [secretHex]. The drone keys
         * the HMAC with the secret's text as stored, so the hex string's
         * bytes are the key, not the bytes it encodes.
         */
        fun forSecret(secretHex: String): RelayTicket = RelayTicket(secretHex.toByteArray(Charsets.US_ASCII))

        /**
         * The bytes a config-tunnel ticket binds: the request's `op`, `key`
         * and `value` as compact JSON, newline-separated, an absent or null
         * field empty.
         */
        fun tunnelBindingBody(request: JsonObject): ByteArray {
            fun field(name: String): String = request[name].let { if (it == null || it is JsonNull) "" else compact(it) }
            return "${field("op")}\n${field("key")}\n${field("value")}".toByteArray(Charsets.UTF_8)
        }

        private fun compact(e: JsonElement): String = e.toString()

        fun newNonce(): String = hex(ByteArray(16).also(random::nextBytes))

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        private fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
