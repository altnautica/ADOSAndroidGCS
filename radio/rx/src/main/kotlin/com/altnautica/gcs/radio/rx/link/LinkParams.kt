package com.altnautica.gcs.radio.rx.link

/**
 * WFB addressing for a fleet. A fleet shares one channel; each transmitter
 * is told apart by its `link_id = fleet_id << 8 | slot` (slot 0 is the
 * ground station, drones hold 1..24), and each plane of that transmitter by
 * its radio port. The frame filter keys on
 * `channel_id = (link_id << 8) + port`.
 */
object LinkParams {
    const val SLOT_GROUND = 0
    const val FLEET_MAX_SLOTS = 24

    /** Downlink planes a drone transmits under its own link id. */
    const val PORT_VIDEO = 0
    const val PORT_CONTROL = 1
    const val PORT_AUX_DOWN = 2

    fun linkId(fleetId: Int, slot: Int): Int {
        require(fleetId in 1..0xFFFF) { "fleet id $fleetId out of range" }
        require(slot in 0..0xFF) { "slot $slot out of range" }
        return (fleetId shl 8) or slot
    }

    fun channelId(linkId: Int, port: Int): Long {
        require(port in 0..0xFF) { "port $port out of range" }
        return (linkId.toLong() shl 8) + port
    }
}

/** The radio ports one drone downlink carries, as an invite lists them. */
data class RadioPorts(val video: Int, val control: Int, val auxDown: Int) {
    fun asArray(): IntArray = intArrayOf(video, control, auxDown)
}
