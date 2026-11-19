package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.radio.rx.WfbReceiver
import com.altnautica.gcs.radio.rx.link.LinkParams
import com.altnautica.gcs.radio.rx.link.RadioPorts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/**
 * What a ground station's operator hands a phone once they approve it: the
 * fleet's receive key, the hop key that authenticates channel-hop
 * announcements, the link layout, and (version 2) the ground role a phone
 * needs to take the fleet's uplink from that ground station.
 */
@Serializable
data class InviteBundle(
    val v: Int,
    @SerialName("rx_key_b64") val rxKeyB64: String,
    @SerialName("hop_key_b64") val hopKeyB64: String,
    @SerialName("fleet_id") val fleetId: Int,
    val slots: List<InviteSlot>,
    val channel: Int,
    @SerialName("rendezvous_channel") val rendezvousChannel: Int,
    @SerialName("bandwidth_mhz") val bandwidthMhz: Int = 20,
    val mcs: Int? = null,
    @SerialName("video_fec") val videoFec: InviteFec? = null,
    @SerialName("aux_fec") val auxFec: InviteFec? = null,
    val ports: InvitePorts,
    /** The ground role; present from version 2. */
    val ground: InviteGround? = null,
) {
    val rxKey: ByteArray get() = Base64.getDecoder().decode(rxKeyB64)
    val hopKey: ByteArray get() = Base64.getDecoder().decode(hopKeyB64)
    val radioPorts: RadioPorts get() = RadioPorts(ports.video, ports.control, ports.auxDown)

    /** The drones this invite covers, with their link ids. */
    fun links(): List<DroneLinkSpec> = slots.map { DroneLinkSpec(it.deviceId, it.slot, LinkParams.linkId(fleetId, it.slot)) }

    /** Throws [InvalidInviteException] when the bundle cannot drive a receiver. */
    fun validate(): InviteBundle {
        fun fail(reason: String): Nothing = throw InvalidInviteException(reason)
        if (v !in 1..VERSION) fail("unsupported invite version $v")
        if (v >= 2 && ground == null) fail("version $v invite carries no ground role")
        val rx = runCatching { rxKey }.getOrNull() ?: fail("receive key is not base64")
        if (rx.size != WfbReceiver.RX_KEY_BYTES) fail("receive key must be ${WfbReceiver.RX_KEY_BYTES} bytes")
        val hop = runCatching { hopKey }.getOrNull() ?: fail("hop key is not base64")
        if (hop.size != HOP_KEY_BYTES) fail("hop key must be $HOP_KEY_BYTES bytes")
        if (fleetId !in 1..0xFFFF) fail("fleet id $fleetId out of range")
        if (slots.isEmpty()) fail("invite lists no drones")
        if (slots.any { it.slot !in 1..LinkParams.FLEET_MAX_SLOTS }) fail("drone slot out of range")
        if (slots.map { it.slot }.toSet().size != slots.size) fail("duplicate drone slot")
        if (channel !in 1..196 || rendezvousChannel !in 1..196) fail("channel out of range")
        val p = listOf(ports.video, ports.control, ports.auxDown)
        if (p.any { it !in 0..255 } || p.toSet().size != 3) fail("radio ports invalid")
        return this
    }

    companion object {
        const val VERSION = 2
        const val HOP_KEY_BYTES = 32

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(plaintext: ByteArray): InviteBundle =
            try {
                json.decodeFromString(serializer(), plaintext.decodeToString()).validate()
            } catch (e: InvalidInviteException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw InvalidInviteException("unreadable invite: ${e.message}")
            }
    }
}

@Serializable
data class InviteSlot(val slot: Int, @SerialName("device_id") val deviceId: String)

/** One drone of the ground role: its system id and the relay secret its ground issued it. */
@Serializable
data class InviteGroundDrone(
    val slot: Int,
    @SerialName("device_id") val deviceId: String,
    val sysid: Int? = null,
    @SerialName("relay_secret_hex") val relaySecretHex: String? = null,
)

@Serializable
data class InviteRegulatory(val domain: String? = null, @SerialName("dfs_allowed") val dfsAllowed: Boolean = false)

/**
 * The ground station a phone may take the uplink from, and what the phone
 * needs to stand in for it: its transmit parameters, every drone's relay
 * secret, and the uplink key that authenticates the handover both ways.
 */
@Serializable
data class InviteGround(
    @SerialName("box_device_id") val boxDeviceId: String,
    @SerialName("box_name") val boxName: String,
    @SerialName("box_lan_address") val boxLanAddress: String? = null,
    @SerialName("mcs_index") val mcsIndex: Int,
    @SerialName("aux_tx_port") val auxTxPort: Int,
    @SerialName("tx_power_dbm") val txPowerDbm: Int,
    val regulatory: InviteRegulatory = InviteRegulatory(),
    val drones: List<InviteGroundDrone> = emptyList(),
    @SerialName("uplink_key_b64") val uplinkKeyB64: String,
) {
    val uplinkKey: ByteArray get() = Base64.getDecoder().decode(uplinkKeyB64)
}

@Serializable
data class InviteFec(val k: Int, val n: Int)

@Serializable
data class InvitePorts(val video: Int, val control: Int, @SerialName("aux_down") val auxDown: Int)

data class DroneLinkSpec(val deviceId: String, val slot: Int, val linkId: Int)

class InvalidInviteException(message: String) : Exception(message)

/**
 * The bytes the ground station binds the session key to:
 * `"ados/wfb/phone-invite/v1" || invite_id`, no separator.
 */
object InviteContext {
    private val PREFIX = "ados/wfb/phone-invite/v1".toByteArray(Charsets.US_ASCII)

    fun of(inviteId: String): ByteArray = PREFIX + inviteId.toByteArray(Charsets.US_ASCII)
}

/**
 * The fingerprint both ends show so the operator can match the phone to
 * the pending request: the first 8 bytes of SHA-256 over the phone's raw
 * public key, uppercase hex in groups of four (`1A2B-3C4D-5E6F-7081`).
 */
object InviteFingerprint {
    fun of(phonePublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(phonePublicKey)
        val hex = digest.take(8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return hex.chunked(4).joinToString("-")
    }
}
