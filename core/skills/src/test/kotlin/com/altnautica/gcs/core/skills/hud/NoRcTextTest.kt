package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.RcReceiver
import com.altnautica.gcs.core.skills.adosPilot
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The no-RC posture's chip, strip and warnings, and the declaration's readiness and refusal words. */
class NoRcTextTest {
    private val spec = SpecText(testSpec.setupStrings)
    private val text = NoRcText(spec)
    private val noRc = adosPilot().copy(rcReceiver = RcReceiver.Absent)

    @Test
    fun `the chip and strip show only with no RC declared`() {
        assertEquals("No RC", text.chip(noRc))
        assertNull(text.chip(adosPilot()))
        assertNull(text.chip(null))
        assertNull(text.strip(adosPilot()))
    }

    @Test
    fun `the strip names the pilot's link-loss action and window, a return after 10 s by default`() {
        assertEquals(
            "No RC · Pause, Land and Return stop the drone · link lost → Return home in 10 s",
            text.strip(noRc.copy(linkLossAction = null, linkLossS = null)),
        )
        assertEquals(
            "No RC · Pause, Land and Return stop the drone · link lost → Land in 8 s",
            text.strip(noRc.copy(linkLossAction = "land", linkLossS = 7.2f)),
        )
    }

    @Test
    fun `leaving warns while armed or with an unknown arm state, never while disarmed or with RC`() {
        assertEquals("Leaving ends in Return home in 10 s", text.leave(noRc.copy(armed = true, linkLossAction = "rth")))
        assertEquals("Leaving ends in Return home in 10 s", text.leave(noRc.copy(armed = null, linkLossAction = "rth")))
        assertNull(text.leave(noRc.copy(armed = false)))
        assertNull(text.leave(adosPilot().copy(armed = true)))
    }

    @Test
    fun `the lock-screen warning shows only with no RC and the notification hidden`() {
        assertEquals(
            "Notifications are hidden on the lock screen: with the phone locked, the drone will Return home after 10 s",
            text.lockScreenHidden(noRc.copy(linkLossAction = "rth"), hidden = true),
        )
        assertNull(text.lockScreenHidden(noRc, hidden = false))
        assertNull(text.lockScreenHidden(adosPilot(), hidden = true))
    }

    @Test
    fun `the declaration's readiness items and refusals read through the generic spec keys`() {
        assertEquals("Not ready: RC receiver", spec.refusalText("not_ready:rc_seen"))
        assertEquals("Not ready: Link-loss action", spec.refusalText("not_ready:link_loss_posture"))
        assertEquals("Not ready: No RC", spec.refusalText("not_ready:rc_receiver"))
        assertEquals("Not while flying with no RC", spec.refusalText("rc_receiver_absent"))
        assertEquals("Needs ArduCopter", spec.refusalText("firmware"))
        assertEquals("Not in a confined place", spec.refusalText("confined"))
        assertEquals("A surface's link was lost: reconnect it or land first", spec.refusalText("link_loss_unrecovered"))
    }
}
