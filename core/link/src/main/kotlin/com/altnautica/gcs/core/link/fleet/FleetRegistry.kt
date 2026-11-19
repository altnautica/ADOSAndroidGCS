package com.altnautica.gcs.core.link.fleet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.TreeMap

/**
 * One registered drone: its issued slot, the device it was issued to, when
 * (integer unix milliseconds, so the stored value round-trips exactly), and
 * its per-pair relay secret (hex), null for a registration made without one.
 */
@Serializable
data class FleetSlot(
    val slot: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("paired_at_ms") val pairedAtMs: Long,
    @SerialName("relay_secret") val relaySecret: String? = null,
)

/**
 * The phone's fleet slot registry while it is the fleet's ground: which drone
 * owns which slot. A slot is the low 8 bits of the drone's wfb `link_id`, so
 * two drones on one slot share a `channel_id` and thrash each other's FEC
 * decoder; slots are therefore issued here, centrally, never negotiated.
 *
 * [allocate] is idempotent by device id: a re-pair returns the same slot (and
 * keeps the same relay secret and issue time), because renumbering a drone
 * that may be airborne silently retunes its transmitters. A new device takes
 * the lowest free slot in 1..[MAX_SLOTS]; slot 0 is the ground.
 *
 * Not thread-safe; [FleetGroundStore] serialises access.
 */
class FleetRegistry(
    slots: Collection<FleetSlot> = emptyList(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val secrets: () -> String? = ::generateRelaySecret,
) {
    private val bySlot = TreeMap<Int, FleetSlot>().apply { slots.forEach { put(it.slot, it) } }

    /** Every registered slot, in ascending slot order. */
    val slots: List<FleetSlot> get() = bySlot.values.toList()

    val size: Int get() = bySlot.size

    fun isEmpty(): Boolean = bySlot.isEmpty()

    /**
     * Issues [deviceId] a slot, or returns the one it holds. Null when every
     * slot is taken: the caller says the fleet is full rather than evicting a
     * registered drone.
     */
    fun allocate(deviceId: String): Int? {
        slotOf(deviceId)?.let { return it }
        val slot = (1..MAX_SLOTS).firstOrNull { it !in bySlot } ?: return null
        bySlot[slot] = FleetSlot(slot, deviceId, clockMs(), secrets())
        return slot
    }

    /** Drops [deviceId]'s registration; true when one was removed. */
    fun release(deviceId: String): Boolean {
        val slot = slotOf(deviceId) ?: return false
        return bySlot.remove(slot) != null
    }

    /** The slot [deviceId] holds. Exact match only: an id sharing a prefix is a different aircraft; a blank id matches nothing. */
    fun slotOf(deviceId: String): Int? {
        val needle = deviceId.trim()
        if (needle.isEmpty()) return null
        return bySlot.values.firstOrNull { it.deviceId == needle }?.slot
    }

    companion object {
        /** Slots 1..24 are issuable; the ceiling is an airtime budget. */
        const val MAX_SLOTS = 24

        /** The ground's own slot, never issued to a drone. */
        const val SLOT_GROUND = 0

        const val DEVICE_ID_MIN_LEN = 8
        const val DEVICE_ID_MAX_LEN = 32
        const val RELAY_SECRET_BYTES = 32

        /** Whether [id] can name an aircraft: 8 to 32 ASCII letters, digits, `-`, `_` or `.`. */
        fun isValidDeviceId(id: String): Boolean =
            id.length in DEVICE_ID_MIN_LEN..DEVICE_ID_MAX_LEN &&
                id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }

        private val random = SecureRandom()

        /** A fresh per-pair relay secret, lowercase hex of [RELAY_SECRET_BYTES] random bytes. */
        fun generateRelaySecret(): String? = runCatching {
            val b = ByteArray(RELAY_SECRET_BYTES).also(random::nextBytes)
            b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.getOrNull()
    }
}
