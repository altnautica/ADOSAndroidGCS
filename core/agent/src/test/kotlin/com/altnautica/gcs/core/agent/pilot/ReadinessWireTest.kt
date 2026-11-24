package com.altnautica.gcs.core.agent.pilot

import com.altnautica.gcs.core.model.ParamDiff
import com.altnautica.gcs.core.model.ReadinessFix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The readiness item's optional parameter rows and fix. */
class ReadinessWireTest {
    private fun item(json: String) = checkNotNull(PilotWire.readiness(Json.parseToJsonElement(json).jsonObject))

    @Test
    fun `params and an apply_profile fix ride along`() {
        val r = item(
            """{"id":"fc_profile","ok":false,"severity":"block","msg":"differ",
               "params":[{"name":"MAV_GCS_SYSID","have":255,"want":"1"},{"name":"FS_GCS_ENABLE","have":null,"want":"5"},{"have":1}],
               "fix":{"kind":"apply_profile"}}""",
        )
        assertEquals(listOf(ParamDiff("MAV_GCS_SYSID", 255.0, "1"), ParamDiff("FS_GCS_ENABLE", null, "5")), r.params)
        assertEquals(ReadinessFix.ApplyProfile, r.fix)
    }

    @Test
    fun `every fix kind parses and an unknown or incomplete one is dropped`() {
        fun fix(f: String) = item("""{"id":"x","ok":false,"severity":"warn","msg":"m","fix":$f}""").fix
        assertEquals(ReadinessFix.SetupStep("detect"), fix("""{"kind":"setup_step","step":"detect"}"""))
        assertEquals(ReadinessFix.Settings("sensors"), fix("""{"kind":"settings","section":"sensors"}"""))
        assertEquals(ReadinessFix.Parameters(listOf("FLOW_TYPE")), fix("""{"kind":"parameters","names":["FLOW_TYPE"]}"""))
        assertNull(fix("""{"kind":"teleport"}"""))
        assertNull(fix("""{"kind":"setup_step"}"""))
    }

    @Test
    fun `an item without params or fix reads as before`() {
        val r = item("""{"id":"position","ok":true,"severity":"warn","msg":""}""")
        assertTrue(r.params.isEmpty())
        assertNull(r.fix)
    }
}
