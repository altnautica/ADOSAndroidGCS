package com.altnautica.gcs.feature.cockpit.preflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstArmGateTest {
    private val blocked = PreflightResult(listOf(PreflightCheck(PreflightItem.Gps3d, passed = false, known = true)))
    private val ready = PreflightResult(listOf(PreflightCheck(PreflightItem.Gps3d, passed = true, known = true)))
    private val takeoff = ArmRequest.Skill("takeoff", mapOf("alt" to 10f))

    @Test
    fun `the first arm waits on the checklist and runs once it is cleared`() {
        val gate = FirstArmGate()
        assertFalse(gate.request(takeoff))
        assertEquals(takeoff, gate.pending)
        assertNull("a block left keeps the request parked", gate.proceed(blocked))
        assertEquals(takeoff, gate.pending)
        assertEquals(takeoff, gate.proceed(ready))
        assertNull(gate.pending)
        assertTrue("later arms skip the checklist", gate.request(ArmRequest.Launch(emptyMap())))
    }

    @Test
    fun `dismissing the checklist drops the parked request`() {
        val gate = FirstArmGate()
        gate.request(takeoff)
        gate.dismiss()
        assertNull(gate.pending)
        assertFalse(gate.cleared)
        assertFalse(gate.request(takeoff))
    }

    @Test
    fun `a vehicle that armed this session opens the gate`() {
        val gate = FirstArmGate()
        gate.request(takeoff)
        gate.onArmed()
        assertNull(gate.pending)
        assertTrue(gate.request(takeoff))
    }
}
