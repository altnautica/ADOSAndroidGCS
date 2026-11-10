package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller Back closes the overlay drawn on top, the one [SheetStack.visible] picks. */
class CockpitOverlaysTest {
    private val scope = TestScope()
    private val overlays = CockpitOverlays(ConfirmController(scope, MonotonicClock { 0L }, { emptyList() }, { false }))

    @Test
    fun `Back with the pre-flight gate over the Launch sheet closes the gate`() {
        overlays.launch.value = true
        overlays.preflight.value = true
        assertTrue(overlays.closeTop())
        assertFalse("the gate drawn on top closes", overlays.preflight.value)
        assertTrue("the Launch sheet under it stays", overlays.launch.value)
        assertTrue(overlays.closeTop())
        assertFalse(overlays.launch.value)
        assertFalse(overlays.closeTop())
    }

    @Test
    fun `Back with the alert list open closes the list, so it does not stop the skill`() {
        overlays.statusList.value = true
        // stopOrBack stops the running skill only when closeTop finds nothing to close.
        assertTrue(overlays.closeTop())
        assertFalse(overlays.statusList.value)
        assertFalse(overlays.closeTop())
    }
}
