package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_GUIDED
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_NAMES
import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.MODE_STABILIZE
import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.enums.MavSeverity
import com.altnautica.gcs.core.mavlink.messages.CommandAck
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.Statustext
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the simulated vehicle remembers, plus the outbound side every
 * part of the simulator writes through. One instance per simulator; touched
 * only from the simulator's scope.
 */
internal class SimState(
    val scope: CoroutineScope,
    val options: DemoOptions,
    private val emit: (ByteArray) -> Unit,
) {
    private val writer = MavlinkFrameWriter(sysId = ArduCopterSimulator.SYS_ID, compId = ArduCopterSimulator.COMP_ID)

    var bootMs = 0L
    var armed = false
    var mode = MODE_STABILIZE

    // Position relative to home: north/east metres, altitude above home (m).
    var north = 0.0
    var east = 0.0
    var alt = 0.0
    var vn = 0.0
    var ve = 0.0
    var vd = 0.0
    var yaw = 0.0
    var roll = 0.0
    var pitch = 0.0
    var batteryMah = 0.0
    var throttle = 0

    var guidedTarget: Target? = null
    var takeoffAlt: Double? = null
    var landing = false
    var rtlPhase = 0
    var paused = false
    var circleAngle = 0.0
    var waypointSpeed = 5.0

    /** Mission item the AUTO model flies next; 0 is home. */
    var currentSeq = 1
    var holdUntilMs: Long? = null

    class Target(val north: Double, val east: Double, val alt: Double)

    fun send(message: MavMessage) = emit(writer.encode(message))

    fun text(severity: Int, text: String) = send(Statustext(severity = severity, text = text))

    fun ack(frame: MavlinkFrame, command: Int, result: Int) =
        send(CommandAck(command = command, result = result, targetSystem = frame.sysId, targetComponent = frame.compId))

    fun switchMode(custom: Int) {
        if (custom == mode) return
        mode = custom
        guidedTarget = null
        landing = false
        rtlPhase = 0
        paused = false
        holdUntilMs = null
        if (custom != MODE_GUIDED) takeoffAlt = null
        text(MavSeverity.MAV_SEVERITY_INFO, "Mode ${MODE_NAMES[custom]}")
    }

    fun disarm() {
        armed = false
        takeoffAlt = null
        guidedTarget = null
        text(MavSeverity.MAV_SEVERITY_INFO, "Disarming motors")
    }
}
