package com.altnautica.gcs.feature.cockpit.autonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.model.SpeedCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PilotSpeedCapTest {
    @Test fun `pilot state carries the gnss-denied cap`() {
        val text = """{"kind":"pilot","ts_ms":1,"data":{"owner":"skill","speed_cap":{"reason":"gnss-denied","v_h_mps":2.0}}}"""
        val p = (AutonomyParse.navFrame(text, 1) as NavFrame.Pilot).state
        assertEquals(SpeedCap("gnss-denied", 2.0f), p.speedCap)
    }

    @Test fun `pilot state without a cap reads none`() {
        val text = """{"kind":"pilot","ts_ms":1,"data":{"owner":"skill"}}"""
        val p = (AutonomyParse.navFrame(text, 1) as NavFrame.Pilot).state
        assertNull(p.speedCap)
    }
}
