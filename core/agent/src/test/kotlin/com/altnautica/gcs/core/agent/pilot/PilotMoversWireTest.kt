package com.altnautica.gcs.core.agent.pilot

import com.altnautica.gcs.core.model.MoverKind
import com.altnautica.gcs.core.model.MoverSource
import com.altnautica.gcs.core.model.TrackedMover
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pilot frame's tracked movers, as the agent sends them. */
class PilotMoversWireTest {
    private fun state(json: String) = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(json) as JsonObject, 0L))

    @Test
    fun `every well-formed mover parses, and an unknown kind reads as unknown`() {
        val s = state(
            """{"owner":"pilot","movers":[
              {"id":3,"kind":"person","source":"vision","ne":[12.5,-1.2],"vel_ne":[0,1.4],"sigma_m":0.3,"age_ms":100},
              {"id":4,"kind":"drone","source":"scan","ne":[1,2],"vel_ne":[0.5,0],"sigma_m":0.2,"age_ms":1500},
              {"id":5,"kind":"vehicle","ne":[1],"vel_ne":[0,0]},
              {"kind":"person","ne":[1,1],"vel_ne":[0,0]},
              "junk"
            ]}""",
        )
        assertEquals(
            listOf(
                TrackedMover(3, MoverKind.Person, MoverSource.Vision, 12.5f, -1.2f, 0f, 1.4f, 0.3f, 100),
                TrackedMover(4, MoverKind.Unknown, MoverSource.Scan, 1f, 2f, 0.5f, 0f, 0.2f, 1500),
            ),
            s.movers,
        )
        assertFalse(s.movers[0].stale)
        assertTrue("not measured for over a second", s.movers[1].stale)
    }

    @Test
    fun `an older agent's frame, with no movers, tracks none`() {
        assertEquals(emptyList<TrackedMover>(), state("""{"owner":"pilot"}""").movers)
        assertEquals(emptyList<TrackedMover>(), state("""{"owner":"pilot","movers":null}""").movers)
    }

    @Test
    fun `the person separation limit rides on the command limits`() {
        assertEquals(listOf("person"), state("""{"owner":"goto","command":{"limits":["person"]}}""").commandLimits)
    }
}
