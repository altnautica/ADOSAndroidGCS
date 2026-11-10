package com.altnautica.gcs.core.skills.controls

import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor, add-row and import paths keep every emergency-stop row a hold of at least the stop hold. */
class StopHoldRowTest {
    private val ds4 = testSpec.ds4
    private val stopHoldMs = Ds4Bindings.stopHoldMs(testSpec.layout)
    private val shipped = Ds4Bindings.resolve(ds4, null, emptyList()).rows

    private fun applied(result: EditResult) = (result as EditResult.Applied).rows

    @Test
    fun `a stop row added as a press, tap, double tap or short hold is added as the stop hold`() {
        for (gesture in Gesture.EDITABLE) {
            val added = applied(addRow(shipped, "options", gesture, Ds4Bindings.STOP_ACTION, stopHoldMs)).last()
            assertEquals(gesture, Gesture.HOLD, added.gesture)
            assertEquals(gesture, stopHoldMs, added.holdMs)
        }
    }

    @Test
    fun `binding the stop to an existing tap row turns it into the stop hold`() {
        val square = shipped.first { it.control == "square" && it.gesture == Gesture.TAP && it.with.isEmpty() }
        val edited = applied(setRowAction(shipped, square, Ds4Bindings.STOP_ACTION, stopHoldMs))
        val row = edited.first { it.control == "square" && it.action == Ds4Bindings.STOP_ACTION }
        assertEquals(Gesture.HOLD, row.gesture)
        assertEquals(stopHoldMs, row.holdMs)
    }

    @Test
    fun `an imported stop row bound to a press or a short hold reads as the stop hold`() {
        val kill = shipped.first { it.action == Ds4Bindings.STOP_ACTION }
        for (shape in listOf(kill.copy(gesture = Gesture.PRESS, holdMs = null), kill.copy(holdMs = 1_500))) {
            val file = ControlsExport(
                pad = "ds4",
                stickMode = StickMode.Mode2.wire,
                deadzone = ds4.conditioning.radialDeadzone,
                profile = "custom",
                bindings = shipped.map { if (it === kill) shape else it },
            )
            val result = ds4.readExport(ControlsJson.encodeToString(ControlsExport.serializer(), file), stopHoldMs)
            assertTrue(result.toString(), result is ImportResult.Imported)
            val read = (result as ImportResult.Imported).export.bindings.first { it.action == Ds4Bindings.STOP_ACTION }
            assertEquals(shape.toString(), Gesture.HOLD, read.gesture)
            assertEquals(shape.toString(), stopHoldMs, read.holdMs)
        }
    }

    @Test
    fun `every other row and a stop hold already long enough are left as they are`() {
        for (row in shipped) assertSame(row, Ds4Bindings.stopHoldRow(row, stopHoldMs))
    }
}
