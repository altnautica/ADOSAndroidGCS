package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The default controller table from `ds4.json`, driven through the dispatcher in 20 ms ticks. */
class Ds4ActionDispatcherTest {
    private val ds4 = testSpec.ds4
    private val rows = ds4.bindings
    private val bits = ds4.stream.buttonsBitfield
    private val dispatcher = Ds4ActionDispatcher(ds4)
    private var now = 0L

    private val ground = ControllerContext(armed = false, landed = true)
    private val air = ControllerContext(armed = true, landed = false)

    private fun pad(controls: Set<String>, lx: Float = 0f, triggers: Float = 0f) = PadSnapshot(
        deviceId = 7,
        vendorId = 0x054c,
        productId = 0x05c4,
        raw = GamepadAxes(lx, 0f, 0f, 0f, triggers, triggers),
        axes = GamepadAxes(lx, 0f, 0f, 0f, triggers, triggers),
        buttons = controls.fold(0) { m, c -> m or (1 shl bits.getValue(c)) },
        atMs = now,
        touches = emptyList(),
    )

    /** Holds [controls] for [ms], then releases them for 100 ms; every action fired, in order. */
    private fun press(vararg controls: String, ms: Long = 60, ctx: ControllerContext = ground, lx: Float = 0f): List<CockpitControllerAction> {
        val out = ArrayList<CockpitControllerAction>()
        val held = controls.toSet()
        val triggers = if ("l2" in held || "r2" in held) 1f else 0f
        var t = 0L
        while (t < ms) {
            out += dispatcher.update(now, pad(held, lx, triggers), rows, ctx)
            now += 20
            t += 20
        }
        repeat(5) {
            out += dispatcher.update(now, pad(emptySet()), rows, ctx)
            now += 20
        }
        return out.filterNot { it is CockpitControllerAction.Buzz || it is CockpitControllerAction.GimbalPitchRate }
    }

    private fun builtin(id: String, held: Boolean = true) = CockpitControllerAction.Builtin(id, held)

    @Test
    fun `L1 plus R1 held 2 s with the left stick centred arms, and the zoom taps they cover do not fire`() {
        val out = press("l1", "r1", ms = 2_100)
        assertTrue(out.toString(), builtin("arm") in out || builtin("arm", false) in out)
        assertFalse(out.toString(), out.any { it is CockpitControllerAction.Zoom })
    }

    @Test
    fun `the arm combo refuses an off-centre left stick`() {
        val out = press("l1", "r1", ms = 2_100, lx = 0.6f)
        assertFalse(out.toString(), out.any { it is CockpitControllerAction.Builtin && it.skillId == "arm" })
    }

    @Test
    fun `PS brakes on button-down and escalates to RTH after 2 s`() {
        val first = dispatcher.update(now, pad(setOf("ps")), rows, air)
        assertTrue(first.toString(), first.any { it is CockpitControllerAction.Builtin && it.skillId == "pause" })
        now += 20
        val rest = press("ps", ms = 2_100, ctx = air)
        assertTrue(rest.toString(), rest.any { it is CockpitControllerAction.Builtin && it.skillId == "rth" })
    }

    @Test
    fun `PS during RTH cancels the return instead of braking`() {
        val out = press("ps", ctx = air.copy(rthActive = true))
        assertTrue(out.toString(), out.any { it is CockpitControllerAction.Pilot && it.action.name == "rth_cancel" })
        assertFalse(out.toString(), out.any { it is CockpitControllerAction.Builtin && it.skillId == "pause" })
    }

    @Test
    fun `Options plus Circle stands in for PS, without opening quick settings or stopping a skill`() {
        val out = press("options", "circle", ms = 2_100, ctx = air)
        assertTrue(out.toString(), out.any { it is CockpitControllerAction.Builtin && it.skillId == "pause" })
        assertTrue(out.toString(), out.any { it is CockpitControllerAction.Builtin && it.skillId == "rth" })
        assertFalse(out.toString(), CockpitControllerAction.QuickSettings in out || CockpitControllerAction.SkillStop in out)
    }

    @Test
    fun `L2 plus R2 plus Circle kills only after the full 3 s hold`() {
        val early = press("l2", "r2", "circle", ms = 2_500, ctx = air)
        assertFalse(early.toString(), early.any { it is CockpitControllerAction.Builtin && it.skillId == "kill" })
        val full = press("l2", "r2", "circle", ms = 3_100, ctx = air)
        assertTrue(full.toString(), full.any { it is CockpitControllerAction.Builtin && it.skillId == "kill" })
    }

    @Test
    fun `Triangle held takes off on the ground and lands in the air, and a tap cycles the view`() {
        assertTrue(press("triangle", ms = 1_600, ctx = ground).any { it is CockpitControllerAction.Builtin && it.skillId == "takeoff" })
        assertTrue(press("triangle", ms = 1_600, ctx = air).any { it is CockpitControllerAction.Builtin && it.skillId == "land" })
        assertEquals(listOf(CockpitControllerAction.ViewNext), press("triangle", ctx = air))
    }

    @Test
    fun `a take-off hold, its release, then a land hold in the same session lands`() {
        assertTrue(builtin("takeoff") in press("triangle", ms = 1_600, ctx = ground))
        val land = press("triangle", ms = 1_600, ctx = air)
        assertTrue(land.toString(), builtin("land") in land)
        assertFalse(land.toString(), builtin("takeoff") in land)
    }

    @Test
    fun `a Triangle never released after the take-off hold starts no land hold`() {
        // Holds start on a press edge: a button still down from the take-off hold is not a new hold.
        val out = ArrayList<CockpitControllerAction>()
        repeat(80) {
            out += dispatcher.update(now, pad(setOf("triangle")), rows, ground)
            now += 20
        }
        assertTrue(out.toString(), builtin("takeoff") in out)
        out.clear()
        repeat(100) {
            out += dispatcher.update(now, pad(setOf("triangle")), rows, air)
            now += 20
        }
        assertFalse(out.toString(), builtin("land") in out)
    }

    @Test
    fun `D-pad up and down adjust the parameter with the picker open and the layer level otherwise`() {
        val open = air.copy(pickerOpen = true)
        assertEquals(listOf(CockpitControllerAction.ParamAdjust(1)), press("dpad_up", ctx = open))
        assertEquals(listOf(CockpitControllerAction.ParamAdjust(-1)), press("dpad_down", ctx = open))
        assertEquals(listOf(CockpitControllerAction.LayerLevel(1)), press("dpad_up", ctx = air))
    }

    @Test
    fun `touchpad click designates`() {
        assertEquals(listOf(CockpitControllerAction.Designate), press("touchpad", ctx = air))
    }
}
