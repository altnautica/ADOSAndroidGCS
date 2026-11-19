package com.altnautica.gcs.core.link.fleet

import com.altnautica.gcs.core.link.fleet.FleetIdentityPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of the agent's system-id policy tests, against the phone's port. */
class FleetIdentityPolicyTest {

    @Test
    fun `a slot maps to the same number as its system id`() {
        assertEquals(1, FleetIdentityPolicy.systemIdForSlot(1))
        assertEquals(24, FleetIdentityPolicy.systemIdForSlot(24))
    }

    @Test
    fun `the unusable slots are refused rather than wrapped`() {
        assertNull(FleetIdentityPolicy.systemIdForSlot(0))
        assertNull(FleetIdentityPolicy.systemIdForSlot(255))
    }

    @Test
    fun `an aircraft already carrying its identity is left alone`() {
        assertEquals(Decision.AlreadyCorrect, FleetIdentityPolicy.decide(3, 3, armed = false))
    }

    @Test
    fun `an aircraft on the default identity adopts its slot`() {
        assertEquals(Decision.Adopt(2), FleetIdentityPolicy.decide(2, 1, armed = false))
    }

    @Test
    fun `an armed aircraft is never re-identified`() {
        assertEquals(Decision.DeferredArmed(2), FleetIdentityPolicy.decide(2, 1, armed = true))
    }

    @Test
    fun `an armed aircraft that is already correct reports correct`() {
        assertEquals(Decision.AlreadyCorrect, FleetIdentityPolicy.decide(2, 2, armed = true))
    }

    @Test
    fun `an aircraft with no slot is left alone`() {
        assertEquals(Decision.NoSlot, FleetIdentityPolicy.decide(null, 1, armed = false))
    }

    @Test
    fun `an unusable slot is reported`() {
        assertEquals(Decision.UnusableSlot(0), FleetIdentityPolicy.decide(0, 1, armed = false))
    }

    @Test
    fun `collisions name every shared identity`() {
        assertTrue(FleetIdentityPolicy.collidingIdentities(listOf(1 to 1, 2 to 2, 3 to 3)).isEmpty())
        assertEquals(listOf(1), FleetIdentityPolicy.collidingIdentities(listOf(1 to 1, 2 to 1, 3 to 3)))
        assertEquals(listOf(1, 7), FleetIdentityPolicy.collidingIdentities(listOf(1 to 1, 2 to 1, 3 to 7, 4 to 7, 5 to 5)))
        assertTrue(FleetIdentityPolicy.collidingIdentities(emptyList()).isEmpty())
    }

    @Test
    fun `a full fleet of slot-derived identities never collides`() {
        val fleet = (1..FleetRegistry.MAX_SLOTS).map { it to FleetIdentityPolicy.systemIdForSlot(it)!! }
        assertTrue(FleetIdentityPolicy.collidingIdentities(fleet).isEmpty())
    }
}
