package com.altnautica.gcs.core.link.fleet

import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fleet this phone bound over the air and is the ground of: the 64-byte
 * ground key (`gs.key`, which both receives and transmits), the 32-byte
 * hop/presence pair key, the fleet's addressing and its slot registry.
 */
class BoundFleet(
    val gsKey: ByteArray,
    val pairKey: ByteArray,
    val fleetId: Int,
    val channel: Int,
    /** Normal-profile traffic has decoded under this key; a proven key is never replaced by a bind. */
    val keyProven: Boolean,
    /** Blake2b-8 of the key's peer-public half, as the agent fingerprints a key file. */
    val keyFingerprint: String,
    val slots: List<FleetSlot>,
)

/** The keys a bind sends and keeps: `drone.key` goes to the drone, `gs.key` stays. */
class FleetKeys(val droneKey: ByteArray, val gsKey: ByteArray, val pairKey: ByteArray, val fingerprint: String)

/**
 * Keeps the phone's bound fleet sealed in the credential vault, as one JSON
 * document under the vault's phone-ground entry, and serialises every change
 * to it: the keys a bind stores, the drones enrolled from their presence
 * beacons, and the key latch.
 */
@Singleton
class FleetGroundStore @Inject constructor(private val vault: CredentialVault) {

    private val lock = Mutex()
    private var loaded = false
    private var record: Record? = null
    private val _fleet = MutableStateFlow<BoundFleet?>(null)

    /** The bound fleet; call [load] once before trusting a null. */
    val fleet: StateFlow<BoundFleet?> = _fleet.asStateFlow()

    /** The bound fleet, read from the vault on the first call. Throws [VaultUnreadableException] while the vault cannot be read. */
    suspend fun load(): BoundFleet? = lock.withLock {
        ensureLoaded()
        _fleet.value
    }

    /**
     * The keys a bind should send: the fleet's own when its key is proven or a
     * drone is enrolled under it (a fleet is one trust domain, so every drone
     * gets the same `drone.key`); null when a bind should mint a fresh pair.
     */
    suspend fun reusableKeys(): FleetKeys? = lock.withLock {
        ensureLoaded()
        val r = record ?: return@withLock null
        if (!r.keyProven && r.slots.isEmpty()) return@withLock null
        FleetKeys(b64(r.droneKeyB64), b64(r.gsKeyB64), b64(r.pairKeyB64), r.keyFingerprint)
    }

    /**
     * Stores the keys of a completed bind. The same keys keep the registry and
     * the latch; new keys start a new fleet (drones registered under the old
     * key cannot hear this one), with an empty registry and an open latch.
     */
    suspend fun adopt(keys: FleetKeys) = lock.withLock {
        ensureLoaded()
        val prior = record
        val same = prior != null && prior.droneKeyB64 == enc(keys.droneKey)
        write(
            Record(
                droneKeyB64 = enc(keys.droneKey),
                gsKeyB64 = enc(keys.gsKey),
                pairKeyB64 = enc(keys.pairKey),
                keyFingerprint = keys.fingerprint,
                keyProven = same && prior!!.keyProven,
                fleetId = FLEET_ID,
                channel = HOME_CHANNEL,
                slots = if (same) prior!!.slots else emptyList(),
            ),
        )
    }

    /**
     * Enrols a drone heard on the air by its decoded presence beacon (decoding
     * it proves it holds the fleet key, the same evidence a pair accepts).
     * Callers pass drone beacons only; a ground never takes a slot. Returns its
     * slot (the held one when already enrolled), null for an id that cannot
     * name an aircraft, a full fleet, or no bound fleet.
     */
    suspend fun enrol(deviceId: String): Int? = lock.withLock {
        ensureLoaded()
        val r = record ?: return@withLock null
        if (!FleetRegistry.isValidDeviceId(deviceId)) return@withLock null
        val registry = FleetRegistry(r.slots)
        registry.slotOf(deviceId)?.let { return@withLock it }
        val slot = registry.allocate(deviceId) ?: return@withLock null
        write(r.copy(slots = registry.slots))
        slot
    }

    /** Latches the key as proven: normal-profile traffic decoded under it. Idempotent. */
    suspend fun markKeyProven() = lock.withLock {
        ensureLoaded()
        val r = record ?: return@withLock
        if (!r.keyProven) write(r.copy(keyProven = true))
    }

    /** Forgets the bound fleet (keys and registry). Throws [VaultUnreadableException] when the vault refuses the wipe. */
    suspend fun forget() = lock.withLock {
        vault.putPhoneGround(null)
        record = null
        loaded = true
        _fleet.value = null
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        // A lenient read of an unreadable vault returns null: that must not pass for "no fleet".
        vault.requireReadable()
        record = vault.phoneGround()?.let { blob -> runCatching { json.decodeFromString(Record.serializer(), blob.decodeToString()) }.getOrNull() }
        _fleet.value = record?.toFleet()
        loaded = true
    }

    private suspend fun write(next: Record) {
        vault.putPhoneGround(json.encodeToString(Record.serializer(), next).encodeToByteArray())
        record = next
        _fleet.value = next.toFleet()
    }

    @Serializable
    private data class Record(
        @SerialName("drone_key_b64") val droneKeyB64: String,
        @SerialName("gs_key_b64") val gsKeyB64: String,
        @SerialName("pair_key_b64") val pairKeyB64: String,
        @SerialName("key_fingerprint") val keyFingerprint: String,
        @SerialName("key_proven") val keyProven: Boolean = false,
        @SerialName("fleet_id") val fleetId: Int,
        val channel: Int,
        val slots: List<FleetSlot> = emptyList(),
    ) {
        fun toFleet() = BoundFleet(b64(gsKeyB64), b64(pairKeyB64), fleetId, channel, keyProven, keyFingerprint, slots)
    }

    companion object {
        /** The fleet an unconfigured drone sits in (the agent's default `video.wfb.fleet_id`). */
        const val FLEET_ID = 1

        /** The agent's home channel, where a bound pair meets after the bind. */
        const val HOME_CHANNEL = 149

        private val json = Json { ignoreUnknownKeys = true }
        private fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}
