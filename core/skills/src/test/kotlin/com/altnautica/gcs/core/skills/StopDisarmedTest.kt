package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** STOP is always enabled and always asks for the 3 s hold; disarmed, the kill it sends is harmless. */
class StopDisarmedTest {
    private val kill = BuiltinSkills.from(testSpec.builtins, testSpec.ds4).first { it.id == "kill" }

    @Test
    fun `STOP stays enabled while disarmed`() {
        val ctx = SkillContext(
            FakeVehicle(), armed = false, mode = FlightMode(0, "STABILIZE"), linkFresh = true, checklistReady = true,
            autonomousNav = true, fcLanded = true,
        )
        assertTrue(kill.state(ctx).enabled)
        assertEquals(ArmRequirement.Any, kill.armRequirement)
        assertEquals(null, SkillActivator.armRefusal(kill.armRequirement, false))
    }

    @Test
    fun `STOP opens the hold-to-confirm from the spec`() {
        val confirm = kill.confirm
        assertNotNull(confirm)
        assertEquals(testSpec.layout.holdsMs.getValue("stop"), confirm!!.holdMs)
    }
}
