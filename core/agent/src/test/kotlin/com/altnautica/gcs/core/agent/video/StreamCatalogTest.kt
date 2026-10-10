package com.altnautica.gcs.core.agent.video

import com.altnautica.gcs.core.agent.http.AgentJson
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.model.VideoBlock
import com.altnautica.gcs.core.agent.model.VideoStreamLeg
import com.altnautica.gcs.core.model.StreamSwitchKind
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCatalogTest {

    private fun row(json: String): JsonObject = AgentJson.json.parseToJsonElement(json).jsonObject

    private val roster = listOf(
        row("""{"id":"main","name":"Main","source":"/dev/video0","role":"primary","purpose":[],"enabled":true,"state":"assigned","live":true,"codec":"h264","width":1280,"calibration":null}"""),
        row("""{"id":"belly","name":"Belly","source":"/dev/video2","role":null,"purpose":["nadir"],"enabled":true,"state":"assigned","live":null}"""),
        row("""{"id":"usb-3","name":"USB cam","source":"/dev/video4","role":null,"purpose":[],"enabled":false,"state":"discovered_unassigned"}"""),
    )

    @Test
    fun `more than one concurrent leg is concurrent`() {
        val status = StatusFull(
            video = VideoBlock(
                streams = listOf(
                    VideoStreamLeg("main", "eo", "h265", live = true),
                    VideoStreamLeg("ir", "ir", "h264", live = null),
                ),
            ),
        )
        val streams = StreamCatalog.describe(status, roster)
        assertEquals(listOf("main", "ir"), streams.map { it.id })
        assertTrue(streams.all { it.switchKind == StreamSwitchKind.Concurrent })
        assertTrue(streams[1].live)
    }

    @Test
    fun `assigned roster cameras are switchable`() {
        val streams = StreamCatalog.describe(StatusFull(), roster)
        assertEquals(listOf("main", "belly"), streams.map { it.id })
        assertTrue(streams.all { it.switchKind == StreamSwitchKind.Switchable })
    }

    @Test
    fun `single camera offers no streams`() {
        assertTrue(StreamCatalog.describe(StatusFull(), roster.take(1)).isEmpty())
    }

    @Test
    fun `primary switch demotes and reslugs the reserved id`() {
        val legs = CameraLegs.withPrimary(roster, "belly")!!
        assertEquals(2, legs.size)
        val belly = legs.first { (it["source"] as JsonPrimitive).content == "/dev/video2" }
        assertEquals(JsonPrimitive("primary"), belly["role"])
        assertEquals(JsonPrimitive(true), belly["enabled"])
        val old = legs.first { (it["source"] as JsonPrimitive).content == "/dev/video0" }
        assertEquals(JsonNull, old["role"])
        assertEquals(JsonPrimitive("main-2"), old["id"])
        assertEquals(JsonPrimitive("h264"), old["codec"])
        assertFalse(old.containsKey("calibration"))
        assertFalse(old.containsKey("state"))
    }

    @Test
    fun `promoting a discovered camera appends it`() {
        val legs = CameraLegs.withPrimary(roster, "usb-3")!!
        assertEquals(3, legs.size)
        assertEquals(JsonPrimitive("primary"), legs.last()["role"])
    }
}
