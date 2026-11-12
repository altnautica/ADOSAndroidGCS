package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoFc

/** A flight controller that follows commands at once and climbs straight to the take-off altitude. */
internal class FakeDemoFc : DemoFc {
    override var armed = false
    override var altitudeM = 0.0
    override var mode = STABILIZE
    override var gnssFix = true
    val sent = mutableListOf<Int>()
    val repositions = mutableListOf<Reposition>()

    data class Reposition(val latDeg: Double, val lonDeg: Double, val altM: Float)

    override fun reposition(latDeg: Double, lonDeg: Double, altM: Float) {
        repositions += Reposition(latDeg, lonDeg, altM)
    }

    override fun command(command: Int, p1: Float, p2: Float, p7: Float) {
        sent += command
        when (command) {
            ARM_DISARM -> if (p1 == 1f && mode in setOf(STABILIZE, GUIDED, LOITER)) armed = true else if (p1 == 0f) armed = false
            SET_MODE -> mode = p2.toInt()
            TAKEOFF -> if (armed && mode == GUIDED) altitudeM = p7.toDouble()
            LAND -> mode = LAND_MODE
        }
    }

    companion object {
        const val STABILIZE = 0
        const val GUIDED = 4
        const val AUTO = 3
        const val LOITER = 5
        const val LAND_MODE = 9
        const val ARM_DISARM = 400
        const val SET_MODE = 176
        const val TAKEOFF = 22
        const val LAND = 21
    }
}
