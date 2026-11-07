package com.altnautica.gcs.feature.cockpit.hud.layers

import com.altnautica.gcs.feature.cockpit.autonomy.AutonomyParse
import com.altnautica.gcs.feature.cockpit.autonomy.NavFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The overlay parsers on the agent's golden fixtures (byte copies of `crates/ados-control/tests/fixtures`). */
class LayerParseTest {
    private fun fixture(name: String): String? =
        javaClass.classLoader!!.getResource("fixtures/$name")?.let { File(it.toURI()).readText() }

    @Test
    fun `ar golden fixture parses every item type with keys and mission`() {
        val f = LayerParse.ar(fixture("ar-frame.json")!!)
        assertEquals(1, f.v)
        assertEquals(1793960000000L, f.tsMs)
        assertFalse(f.approx)
        assertNull(f.reason)
        assertEquals("active", f.mission!!.state)
        assertEquals(4, f.mission!!.seq)
        assertEquals(6, f.mission!!.total)
        val cam = f.cameras.getValue("main")
        assertEquals(1280f, cam.frameW)
        assertEquals(720f, cam.frameH)
        assertEquals(setOf("wp", "leg", "home", "fpv", "fpath", "fgoal", "orbit", "edge"), cam.items.map { it.t }.toSet())
        val next = cam.items.first { it.t == "wp" && it.role == "next" }
        assertEquals("wp:4", next.key)
        assertEquals(0.52f, next.x!!, 1e-6f)
        assertEquals(120f, next.distM!!, 1e-6f)
        assertEquals(42f, next.etaS!!, 1e-6f)
        val after = cam.items.first { it.role == "after" }
        assertNull(after.gx)
        assertNull(after.etaS)
        assertEquals("detour", cam.items.first { it.t == "fpath" }.state)
        assertEquals(2.6f, cam.items.first { it.t == "edge" }.ang!!, 1e-6f)
        assertEquals("edge:home", cam.items.first { it.t == "edge" }.key)
        // Every key is unique within a camera.
        assertEquals(cam.items.size, cam.items.map { it.key }.toSet().size)
    }

    @Test
    fun `depth golden fixture decodes to metric cells`() {
        val f = LayerParse.depth(fixture("depth-frame.json")!!)
        val g = f.cameras.getValue("main")
        assertEquals(32, g.cols)
        assertEquals(18, g.rows)
        assertEquals("lidar", g.scaleSource)
        val near = ByteArray(g.cols * g.rows)
        assertEquals(576, GridCodec.decode(g.near, near))
        val mask = ByteArray(72)
        assertEquals(72, GridCodec.decode(g.measured, mask))
        val metres = (0 until 576).map { GridCodec.metres(near[it].toInt() and 0xFF) }.filter { !it.isNaN() }
        assertTrue(metres.isNotEmpty())
        assertTrue(metres.all { it in 0.2f..60f })
    }

    @Test
    fun `surfaces golden fixture parses zones`() {
        val f = LayerParse.surfaces(fixture("surfaces-frame.json")!!)
        assertTrue(f.cameraId.isNotEmpty())
        assertTrue(f.zones.isNotEmpty())
        f.zones.forEach { z ->
            assertTrue(z.pts.size >= 3)
            assertTrue(z.score in 0f..1f)
        }
    }

    @Test
    fun `obstacles golden fixture parses sectors and grid when present`() {
        val text = fixture("obstacles-frame.json") ?: return
        val f = LayerParse.obstacles(text)
        assertEquals(72, f.sectorsM.size)
        f.cameras.values.forEach { g ->
            val near = ByteArray(g.cols * g.rows)
            assertEquals(g.cols * g.rows, GridCodec.decode(g.near, near))
        }
    }

    @Test
    fun `nav stream envelope routes overlay kinds to the layer store`() {
        val data = fixture("ar-frame.json")!!
        val frame = AutonomyParse.navFrame("""{"kind":"ar","ts_ms":1,"data":$data}""", 5L)
        assertTrue(frame is NavFrame.Layer)
        assertTrue((frame as NavFrame.Layer).frame is LayerFrame.Ar)
        assertNull(AutonomyParse.navFrame("""{"kind":"ar","ts_ms":1,"data":{"cameras":7}}""", 5L))
    }

    @Test
    fun `grid code round trips and zero is unknown`() {
        assertTrue(GridCodec.metres(0).isNaN())
        assertEquals(0.2f, GridCodec.metres(1), 1e-4f)
        assertEquals(60f, GridCodec.metres(255), 1e-2f)
        for (m in listOf(0.5f, 1.5f, 2f, 3.5f, 5f, 20f)) {
            assertEquals(m, GridCodec.metres(GridCodec.code(m)), m * 0.03f)
        }
    }

    @Test
    fun `base64 decodes into a reused buffer`() {
        val bytes = byteArrayOf(0, 1, 2, -1, 127, -128, 42)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val out = ByteArray(16)
        assertEquals(bytes.size, GridCodec.decode(b64, out))
        assertEquals(bytes.toList(), out.take(bytes.size))
        assertEquals(-1, GridCodec.decode("!!", out))
        // Bit i is byte i/8, LSB first.
        val mask = byteArrayOf(0b0000_0101, 0b1000_0000.toByte())
        assertTrue(GridCodec.bit(mask, 0))
        assertFalse(GridCodec.bit(mask, 1))
        assertTrue(GridCodec.bit(mask, 2))
        assertTrue(GridCodec.bit(mask, 15))
        assertNotNull(mask)
    }
}
