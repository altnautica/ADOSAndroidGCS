package com.altnautica.gcs.feature.plan.io

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MavFrame
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.PlanHome
import com.altnautica.gcs.feature.plan.model.PlanItem
import com.altnautica.gcs.feature.plan.model.PlanWarning
import com.altnautica.gcs.feature.plan.model.RallyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanFilesTest {

    private var next = 1L
    private val nextId: () -> Long = { next++ }

    private fun plan(): MissionPlan {
        val takeoff = PlanItem(1, MavCmd.NAV_TAKEOFF, MavFrame.GLOBAL_RELATIVE_ALT, x = 12.9716, y = 77.5946, z = 20f)
        val wp1 = PlanItem(2, MavCmd.NAV_WAYPOINT, MavFrame.GLOBAL_RELATIVE_ALT, param1 = 5f, x = 12.9721, y = 77.5951, z = 30.5f, speedMs = 8f)
        val roi = PlanItem(3, MavCmd.DO_SET_ROI_LOCATION, MavFrame.GLOBAL_RELATIVE_ALT, x = 12.973, y = 77.596, z = 0f)
        val wp2 = PlanItem(4, MavCmd.NAV_WAYPOINT, MavFrame.GLOBAL, x = 12.9725, y = 77.5958, z = 950.25f)
        val photo = PlanItem(5, MavCmd.DO_DIGICAM_CONTROL, MavFrame.MISSION, x = 1.0)
        // param1 holds the file row the jump targets (row 2: the speed change that opens item 2).
        val jump = PlanItem(6, MavCmd.DO_JUMP, MavFrame.MISSION, param1 = 2f, param2 = 3f, jumpTarget = 2)
        val rtl = PlanItem(7, MavCmd.NAV_RETURN_TO_LAUNCH, MavFrame.GLOBAL_RELATIVE_ALT)
        return MissionPlan(
            items = listOf(takeoff, wp1, roi, wp2, photo, jump, rtl),
            // A .plan keeps circles and polygons in separate lists, circles first.
            fence = listOf(
                FenceZone.Circle(11, false, GeoPoint(12.975, 77.595), 40.0),
                FenceZone.Polygon(10, true, listOf(GeoPoint(12.97, 77.59), GeoPoint(12.98, 77.59), GeoPoint(12.98, 77.60))),
            ),
            rally = listOf(RallyPoint(12, GeoPoint(12.971, 77.594), 25f)),
            home = PlanHome(GeoPoint(12.9715, 77.5945), 920f),
            cruiseSpeedMs = 12f,
        )
    }

    /** Ids are regenerated on import; compare everything else. */
    private fun MissionPlan.withoutIds() = copy(
        items = items.map { it.copy(id = 0, jumpTarget = it.jumpTarget?.let { t -> items.indexOfFirst { i -> i.id == t }.toLong() }) },
        fence = fence.map {
            when (it) {
                is FenceZone.Polygon -> it.copy(id = 0)
                is FenceZone.Circle -> it.copy(id = 0)
            }
        },
        rally = rally.map { it.copy(id = 0) },
    )

    @Test
    fun `plan file round trips mission, fence, rally and home`() {
        val original = plan()

        val text = PlanFiles.exportPlan(original)
        val imported = PlanFiles.parsePlan(text, nextId)

        assertEquals(emptyList<PlanWarning>(), imported.warnings)
        assertEquals(original.withoutIds(), imported.plan.withoutIds())
    }

    @Test
    fun `waypoints file round trips the mission and home`() {
        val original = plan()

        val text = PlanFiles.exportWaypoints(original)
        val imported = PlanFiles.parseWaypoints(text, nextId)

        val expected = original.copy(fence = emptyList(), rally = emptyList(), cruiseSpeedMs = null)
        assertEquals(expected.withoutIds(), imported.plan.withoutIds())
    }

    @Test
    fun `waypoints export numbers the home row 0 and expands speed and jump targets`() {
        val lines = PlanFiles.exportWaypoints(plan()).trim().lines()

        assertEquals("QGC WPL 110", lines[0])
        val home = lines[1].split('\t')
        assertEquals(listOf("0", "1", "0", "16"), home.take(4))
        assertEquals(listOf("12.9715", "77.5945", "920"), home.subList(8, 11))
        // Row 2 is the DO_CHANGE_SPEED written before the first waypoint.
        val speed = lines[3].split('\t')
        assertEquals(listOf("2", "0", "2", "178", "1", "8", "-1"), speed.take(7))
        // The jump returns to the speed row that starts its target item.
        val jump = lines.map { it.split('\t') }.first { it.size > 4 && it[3] == "177" }
        assertEquals("2", jump[4])
        assertEquals(10, lines.size)
    }

    @Test
    fun `plan file without a home position still exports one from the first item`() {
        val noHome = plan().copy(home = null)

        val imported = PlanFiles.parsePlan(PlanFiles.exportPlan(noHome), nextId)

        assertEquals(GeoPoint(12.9716, 77.5946), imported.plan.home?.position)
    }

    @Test
    fun `transect items expand from embedded items or visual points`() {
        val text = """
            {
              "fileType": "Plan",
              "mission": {
                "plannedHomePosition": [12.0, 77.0, 900],
                "items": [
                  {"type": "SimpleItem", "command": 22, "frame": 3, "doJumpId": 1, "params": [0, 0, 0, null, 12.0, 77.0, 15]},
                  {"type": "ComplexItem", "complexItemType": "survey", "doJumpId": 2,
                   "TransectStyleComplexItem": {
                     "CameraCalc": {"DistanceToSurface": 60, "DistanceMode": 1},
                     "VisualTransectPoints": [[12.001, 77.001], [12.002, 77.001], [12.002, 77.002]]
                   }},
                  {"type": "SimpleItem", "command": 177, "frame": 2, "doJumpId": 5, "params": [2, 1, 0, 0, 0, 0, 0]},
                  {"type": "SimpleItem", "command": 177, "frame": 2, "doJumpId": 6, "params": [42, 1, 0, 0, 0, 0, 0]}
                ]
              }
            }
        """.trimIndent()

        val imported = PlanFiles.parsePlan(text, nextId)
        val items = imported.plan.items

        assertEquals(5, items.size)
        assertTrue(items[0].param4.isNaN())
        assertEquals(listOf(60f, 60f, 60f), items.subList(1, 4).map { it.z })
        assertEquals(items[1].id, items[4].jumpTarget)
        assertEquals(listOf(PlanWarning.DroppedJump(6, 42)), imported.warnings)
    }

    @Test
    fun `survey without altitude is refused instead of flown at zero`() {
        val text = """
            {"fileType": "Plan", "mission": {"items": [
              {"type": "ComplexItem", "TransectStyleComplexItem": {"VisualTransectPoints": [[1, 2]]}}
            ]}}
        """.trimIndent()

        val e = assertThrows(PlanFormatException::class.java) { PlanFiles.parsePlan(text, nextId) }
        assertEquals(PlanFormatException.Reason.ComplexWithoutAltitude, e.reason)
    }

    @Test
    fun `wrong files are rejected with a reason`() {
        assertEquals(
            PlanFormatException.Reason.NotWaypoints,
            assertThrows(PlanFormatException::class.java) { PlanFiles.parseWaypoints("hello", nextId) }.reason,
        )
        assertEquals(
            PlanFormatException.Reason.NotPlan,
            assertThrows(PlanFormatException::class.java) { PlanFiles.parsePlan("{\"fileType\":\"Other\"}", nextId) }.reason,
        )
        assertEquals(
            PlanFormatException.Reason.Malformed,
            assertThrows(PlanFormatException::class.java) { PlanFiles.parsePlan("{not json", nextId) }.reason,
        )
    }

    @Test
    fun `waypoints rows with a non-numeric position are skipped`() {
        val text = "QGC WPL 110\n" +
            "0\t1\t0\t16\t0\t0\t0\t0\t12.0\t77.0\t900\t1\n" +
            "1\t0\t3\t16\t0\t0\t0\t0\tabc\t77.0\t30\t1\n" +
            "2\t0\t3\t16\t0\t0\t0\t0\t12.1\t77.1\t30\t1\n"

        val imported = PlanFiles.parseWaypoints(text, nextId)

        assertEquals(1, imported.plan.items.size)
        assertEquals(GeoPoint(12.1, 77.1), imported.plan.items.single().position)
        assertEquals(PlanHome(GeoPoint(12.0, 77.0), 900f), imported.plan.home)
    }

    @Test
    fun `format is chosen from the file extension`() {
        assertEquals(PlanFileFormat.QgcPlan, PlanFileFormat.fromFileName("field.PLAN"))
        assertEquals(PlanFileFormat.Waypoints, PlanFileFormat.fromFileName("a.b.waypoints"))
        assertEquals(null, PlanFileFormat.fromFileName("notes.txt"))
    }
}
