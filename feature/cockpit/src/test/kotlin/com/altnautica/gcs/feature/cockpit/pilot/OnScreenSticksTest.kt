package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.PadSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vectors: an on-screen pad pushed up flies forward (right pad, Mode 2) or climbs
 * (left pad, Mode 2), exactly as a controller stick pushed up does. The pilot's signs are
 * pitch + forward, throttle + climb, yaw + clockwise, roll + right.
 */
class OnScreenSticksTest {

    private val conditioning = StickConditioning(deadzone = 0f)

    /** [roll, pitch, thr, yaw] the pilot gets for these pad deflections (x right, y up). */
    private fun onScreen(left: Pair<Float, Float>, right: Pair<Float, Float>, mode: StickMode = StickMode.Mode2): List<Float> {
        val sticks = OnScreenSticks()
        sticks.touch(true)
        sticks.setLeft(left.first, left.second)
        sticks.setRight(right.first, right.second)
        val i = conditioning.copy(mode = mode).condition(sticks.snapshot(1_000L))
        return listOf(i.roll, i.pitch, i.throttle, i.yaw).map { it + 0f }
    }

    /** The same deflections from a controller, as the input router reports them (y up positive). */
    private fun controller(left: Pair<Float, Float>, right: Pair<Float, Float>, mode: StickMode = StickMode.Mode2): List<Float> {
        val a = GamepadAxes(left.first, left.second, right.first, right.second)
        val i = conditioning.copy(mode = mode).condition(PadSnapshot(3, 0x054c, 0x05c4, a, a, 0, 1_000L, emptyList()))
        return listOf(i.roll, i.pitch, i.throttle, i.yaw).map { it + 0f }
    }

    private val golden = listOf(
        // left (x, y), right (x, y) -> Mode 2 [roll, pitch, thr, yaw]
        Triple(0f to 0f, 0f to 1f, listOf(0f, 1f, 0f, 0f)),
        Triple(0f to 0f, 0f to -1f, listOf(0f, -1f, 0f, 0f)),
        Triple(0f to 1f, 0f to 0f, listOf(0f, 0f, 1f, 0f)),
        Triple(0f to -1f, 0f to 0f, listOf(0f, 0f, -1f, 0f)),
        Triple(0f to 0f, 1f to 0f, listOf(1f, 0f, 0f, 0f)),
        Triple(1f to 0f, 0f to 0f, listOf(0f, 0f, 0f, 1f)),
    )

    @Test
    fun `an on-screen pad pushed up flies forward or climbs`() {
        for ((left, right, want) in golden) {
            assertEquals("on-screen $left $right", want, onScreen(left, right))
        }
    }

    @Test
    fun `on-screen pads and a controller send the same axes in both stick modes`() {
        for (mode in StickMode.entries) {
            for ((left, right, _) in golden) {
                assertEquals("$mode $left $right", controller(left, right, mode), onScreen(left, right, mode))
            }
        }
    }

    @Test
    fun `the dead-zone ring grows with the dead zone and a knob on it sends nothing`() {
        assertEquals(0.32f, deadzoneRingRadius(1f, 0f), 1e-6f)
        assertEquals(0.32f + 0.68f * 0.08f, deadzoneRingRadius(1f, 0.08f), 1e-6f)
        assertEquals(50f * (0.32f + 0.68f * 0.2f), deadzoneRingRadius(50f, 0.2f), 1e-4f)
        val sticks = OnScreenSticks()
        sticks.touch(true)
        sticks.setRight(0.08f, 0f)
        val i = StickConditioning(deadzone = 0.08f).condition(sticks.snapshot(1_000L))
        assertEquals(0f, i.roll, 0f)
    }
}
