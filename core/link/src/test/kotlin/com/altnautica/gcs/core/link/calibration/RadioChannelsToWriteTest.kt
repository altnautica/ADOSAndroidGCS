package com.altnautica.gcs.core.link.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioChannelsToWriteTest {
    private fun ch(n: Int, min: Int, max: Int) = RcChannelRange(n, (min + max) / 2, min, max)
    private val sticks = listOf(1, 2, 3, 4)

    @Test
    fun untouchedChannelsAreNotWritten() {
        val channels = listOf(ch(1, 1000, 2000), ch(2, 1000, 2000), ch(3, 1000, 2000), ch(4, 1000, 2000), ch(5, 1500, 1500), ch(6, 1490, 1510))
        assertEquals(listOf(1, 2, 3, 4), MavlinkCalibrationService.radioChannelsToWrite(channels, sticks)!!.map { it.channel })
    }

    @Test
    fun aStickThatBarelyMovedRefusesTheSave() {
        val channels = listOf(ch(1, 1000, 2000), ch(2, 1000, 2000), ch(3, 1400, 1550), ch(4, 1000, 2000))
        assertNull(MavlinkCalibrationService.radioChannelsToWrite(channels, sticks))
    }

    @Test
    fun aMissingStickChannelRefusesTheSave() {
        assertNull(MavlinkCalibrationService.radioChannelsToWrite(listOf(ch(1, 1000, 2000)), sticks))
    }

    @Test
    fun remappedSticksAreChecked() {
        val channels = listOf(ch(1, 1500, 1500), ch(5, 1000, 2000), ch(6, 1000, 2000), ch(7, 1000, 2000), ch(8, 1000, 2000))
        assertEquals(listOf(5, 6, 7, 8), MavlinkCalibrationService.radioChannelsToWrite(channels, listOf(5, 6, 7, 8))!!.map { it.channel })
    }
}
