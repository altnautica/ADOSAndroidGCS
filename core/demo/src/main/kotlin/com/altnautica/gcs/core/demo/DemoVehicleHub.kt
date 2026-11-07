package com.altnautica.gcs.core.demo

import com.altnautica.gcs.core.link.transport.DemoTransport
import javax.inject.Inject
import javax.inject.Singleton

/** The demo drone's flight controller as its onboard pilot sees it: state read back, commands sent on its bus. */
interface DemoFc {
    val armed: Boolean

    /** Altitude above home, metres. */
    val altitudeM: Double

    /** ArduCopter custom mode number. */
    val mode: Int

    /** Sends COMMAND_LONG [command] from the onboard computer; the outcome shows in the state above. */
    fun command(command: Int, p1: Float = 0f, p2: Float = 0f, p7: Float = 0f)
}

/**
 * Joins the demo drone's two halves: the simulated flight controller behind its MAVLink
 * link, and the simulated onboard pilot behind its pilot channel. The pilot commands the
 * flight controller the way the vehicle's own pilot does, so arming or taking off through
 * the pilot shows on the flight controller's telemetry too. Without an open link the
 * flight controller reads disarmed on the ground and drops commands.
 */
@Singleton
class DemoVehicleHub @Inject constructor() : DemoFc {
    @Volatile private var transport: DemoTransport? = null

    /** The link most recently opened to the demo drone. */
    fun attach(link: DemoTransport) {
        transport = link
    }

    override val armed: Boolean get() = transport?.simulator?.isArmed == true
    override val altitudeM: Double get() = transport?.simulator?.altitudeM ?: 0.0
    override val mode: Int get() = transport?.simulator?.modeNumber ?: STABILIZE

    override fun command(command: Int, p1: Float, p2: Float, p7: Float) {
        transport?.companionCommand(command, p1, p2, p7)
    }

    private companion object {
        const val STABILIZE = 0
    }
}
