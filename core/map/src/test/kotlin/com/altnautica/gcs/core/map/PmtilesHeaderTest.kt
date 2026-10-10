package com.altnautica.gcs.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmtilesHeaderTest {

    private fun header(tileType: Int = 2, version: Int = 3): ByteArray {
        val buf = ByteBuffer.allocate(PmtilesHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PMTiles".toByteArray(Charsets.US_ASCII))
        buf.put(version.toByte())
        buf.position(99)
        buf.put(tileType.toByte())
        buf.put(5.toByte())
        buf.put(15.toByte())
        buf.putInt(102, 77_500_000) // min lon 7.75
        buf.putInt(106, 12_250_000) // min lat 1.225
        buf.putInt(110, 77_600_000) // max lon 7.76
        buf.putInt(114, 12_350_000) // max lat 1.235
        buf.put(118, 12.toByte())
        buf.putInt(119, 77_550_000)
        buf.putInt(123, 12_300_000)
        return buf.array()
    }

    @Test
    fun `parses zoom range, bounds, centre and tile type`() {
        val parsed = PmtilesHeader.parse(header())

        assertEquals(PmtilesTileType.Png, parsed.tileType)
        assertTrue(parsed.tileType.raster)
        assertEquals(5, parsed.minZoom)
        assertEquals(15, parsed.maxZoom)
        assertEquals(TileBounds(minLat = 1.225, maxLat = 1.235, minLon = 7.75, maxLon = 7.76), parsed.bounds)
        assertEquals(GeoPoint(1.23, 7.755), parsed.center)
        assertEquals(12, parsed.centerZoom)
    }

    @Test
    fun `vector archives are recognised as not raster`() {
        assertEquals(false, PmtilesHeader.parse(header(tileType = 1)).tileType.raster)
    }

    @Test
    fun `rejects other files and versions`() {
        assertThrows(IllegalArgumentException::class.java) { PmtilesHeader.parse(ByteArray(PmtilesHeader.SIZE)) }
        assertThrows(IllegalArgumentException::class.java) { PmtilesHeader.parse(header(version = 2)) }
        assertThrows(IllegalArgumentException::class.java) { PmtilesHeader.parse(header().copyOf(40)) }
    }
}
