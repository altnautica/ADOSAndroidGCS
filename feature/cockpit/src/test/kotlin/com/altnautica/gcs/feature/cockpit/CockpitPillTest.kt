package com.altnautica.gcs.feature.cockpit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status pill lists failing pre-flight checks only on a vehicle known to be on the ground, disarmed. */
class CockpitPillTest {
    @Test
    fun `pre-flight items show only when disarmed is known`() {
        assertTrue(CockpitPill.preflightShown(armed = false))
        assertFalse(CockpitPill.preflightShown(armed = true))
        assertFalse("unknown is not on the ground", CockpitPill.preflightShown(armed = null))
    }
}
