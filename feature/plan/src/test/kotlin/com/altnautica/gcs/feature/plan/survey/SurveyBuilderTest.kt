package com.altnautica.gcs.feature.plan.survey

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MavFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyBuilderTest {

    private var next = 1L
    private val nextId: () -> Long = { next++ }

    /** About 200 m x 110 m near the equator. */
    private val field = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(0.0, 0.0018),
        GeoPoint(0.001, 0.0018),
        GeoPoint(0.001, 0.0),
    )

    private fun inside(p: GeoPoint, slack: Double = 1e-6) =
        p.latDeg in -slack..0.001 + slack && p.lonDeg in -slack..0.0018 + slack

    @Test
    fun `camera survey lays parallel lines inside the polygon with camera triggering`() {
        val settings = SurveySettings(altitudeM = 40f, speedMs = 6f, sidelapPct = 60f, overlapPct = 70f, cameraFovDeg = 73f)

        val plan = SurveyBuilder.build(field, settings, AltitudeRef.Relative, nextId)!!

        val nav = plan.items.filter { it.command == MavCmd.NAV_WAYPOINT }
        assertTrue(plan.lineCount >= 3)
        assertEquals(plan.lineCount * 2, nav.size)
        assertTrue(nav.all { it.z == 40f && it.frame == MavFrame.GLOBAL_RELATIVE_ALT })
        assertTrue(nav.all { inside(it.position!!) })
        assertEquals(6f, nav.first().speedMs)
        assertTrue(nav.drop(1).all { it.speedMs == null })

        val triggers = plan.items.filter { it.command == MavCmd.DO_SET_CAM_TRIGG_DIST }
        assertEquals(2, triggers.size)
        assertEquals(plan.photoIntervalM, triggers.first().param1)
        assertEquals(0f, triggers.last().param1)
        assertEquals(MavCmd.DO_SET_CAM_TRIGG_DIST, plan.items.last().command)
        assertTrue(plan.areaHa!! > 1.5f && plan.areaHa!! < 3f)
    }

    @Test
    fun `higher sidelap packs more lines`() {
        val loose = SurveyBuilder.build(field, SurveySettings(altitudeM = 40f, sidelapPct = 20f), AltitudeRef.Relative, nextId)!!
        val dense = SurveyBuilder.build(field, SurveySettings(altitudeM = 40f, sidelapPct = 80f), AltitudeRef.Relative, nextId)!!

        assertTrue(dense.lineCount > loose.lineCount)
    }

    @Test
    fun `spray preset spaces passes one swath apart`() {
        val plan = SurveyBuilder.build(field, SurveySettings(mode = SurveyMode.Spray, altitudeM = 3f, speedMs = 4f, swathM = 10f), AltitudeRef.Msl, nextId)!!

        assertTrue(plan.items.all { it.command == MavCmd.NAV_WAYPOINT && it.frame == MavFrame.GLOBAL && it.z == 3f })
        assertTrue(plan.lineCount >= 10)
        assertNull(plan.photoIntervalM)
    }

    @Test
    fun `fewer than three corners produce nothing`() {
        assertNull(SurveyBuilder.build(field.take(2), SurveySettings(), AltitudeRef.Relative, nextId))
    }

    @Test
    fun `item ids come from the supplied generator`() {
        val plan = SurveyBuilder.build(field, SurveySettings(), AltitudeRef.Relative, nextId)!!

        assertEquals(plan.items.map { it.id }.distinct().size, plan.items.size)
    }
}
