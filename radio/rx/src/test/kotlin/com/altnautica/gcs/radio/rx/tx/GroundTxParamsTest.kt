package com.altnautica.gcs.radio.rx.tx

import com.altnautica.gcs.radio.rx.invite.sampleBundle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's two transmitters must carry the box's parameters exactly
 * (`gs_tx_control_args` and `gs_aux_tx_args` in the agent's ground link:
 * `-p 1` / `-p 3`, `-i link_id(fleet, 0)`, `-k 1 -n 2 -B 20 -M mcs`, no epoch).
 */
class GroundTxParamsTest {

    @Test
    fun `control and aux uplink use the box's ports, ground link id and light FEC`() {
        val (control, aux) = GroundTxParams.of(sampleBundle())
        // Fleet 3, slot 0.
        assertEquals(GroundTxParams(linkId = 0x300, radioPort = 1, k = 1, n = 2, mcsIndex = 1, bandwidthMhz = 20), control)
        assertEquals(GroundTxParams(linkId = 0x300, radioPort = 3, k = 1, n = 2, mcsIndex = 1, bandwidthMhz = 20), aux)
    }

    @Test
    fun `channel ids are the ones wfb_tx computes from -i and -p`() {
        val (control, aux) = GroundTxParams.of(sampleBundle())
        assertEquals((0x300L shl 8) + 1, control.channelId)
        assertEquals((0x300L shl 8) + 3, aux.channelId)
    }

    @Test
    fun `the fleet's MCS applies to both, and the transmit width stays 20 MHz`() {
        val (control, aux) = GroundTxParams.of(sampleBundle().copy(mcs = 3, bandwidthMhz = 40))
        assertEquals(3, control.mcsIndex)
        assertEquals(3, aux.mcsIndex)
        assertEquals(20, control.bandwidthMhz)
        assertEquals(20, aux.bandwidthMhz)
    }
}
