package com.altnautica.gcs.feature.cockpit.autonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Navigation-stream and map payload parsing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AutonomyParseTest {
    @Test
    fun `follow frame carries phase, range, cues and prediction`() {
        val text = """{"kind":"follow","ts_ms":1,"data":{"phase":"predicting","track_id":12,"range_m":10.2,"range_sigma_m":0.3,
            "range_cues":[{"kind":"ground","range_m":10.1,"sigma_m":0.4},{"kind":"lidar","range_m":10.0,"sigma_m":0.05}],
            "agl_m":4.1,"agl_source":"rng","reacquire_mode":"smart","reacquire_remaining_s":18.4,"bearing_deg":64,
            "target_ned":[5.5,1.5],"predicted_bbox":{"camera_id":"main","frame_w":1280,"frame_h":720,"x":600,"y":300,"w":40,"h":98}}}"""
        val f = (AutonomyParse.navFrame(text, 5) as NavFrame.Follow).follow
        assertEquals("Predicting", f.phase.toString())
        assertEquals(12, f.trackId)
        assertEquals(setOf(RangeCue.Ground, RangeCue.Lidar), f.rangeCues)
        assertEquals("smart", f.reacquireMode)
        assertEquals(5.5f to 1.5f, f.targetNed)
        assertEquals(Box(600f, 300f, 40f, 98f), f.predicted!!.box)
    }

    @Test
    fun `the nav state carries the newest pose's age, null when none`() {
        fun nav(body: String) = AutonomyParse.nav(kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)
        assertEquals(180L, nav("""{"mode":"external_nav","pose_age_ms":180}""").poseAgeMs)
        assertNull(nav("""{"mode":"external_nav","pose_age_ms":null}""").poseAgeMs)
        assertNull(nav("""{"mode":"external_nav"}""").poseAgeMs)
        assertNull(nav("""{"mode":"external_nav","pose_age_ms":"180"}""").poseAgeMs)
        assertEquals("Pose age 180 ms", com.altnautica.gcs.feature.cockpit.band.navPoseAgeText(com.altnautica.gcs.feature.cockpit.testSpec.setupStrings, 180L))
    }

    @Test
    fun `follow event uses the top-level topic`() {
        val text = """{"kind":"follow_event","ts_ms":1,"topic":"follow.reacquired","data":{"track_id":15,"similarity":0.862,"gap_s":1.8}}"""
        val e = (AutonomyParse.navFrame(text, 7) as NavFrame.Event).event as FollowEvent.Reacquired
        assertEquals(15, e.trackId)
        assertEquals(7L, e.atMs)
    }

    @Test
    fun `every follow phase the agent sends parses, and an older follow's coasting and lost read as predicting and holding`() {
        fun follow(p: String) = (AutonomyParse.navFrame("""{"kind":"follow","ts_ms":1,"data":{"active":true,"phase":"$p","commanding":true}}""", 0) as? NavFrame.Follow)?.follow
        fun phase(p: String) = follow(p)?.phase?.toString()
        assertEquals("Repositioning", phase("repositioning"))
        assertEquals("Searching", phase("searching"))
        assertEquals("HoldingAwaitOperator", phase("holding-await-operator"))
        // Follow-me 0.4.9 and older: never a follow while the lock is lost, and never the waiting prompt.
        assertEquals("Predicting", phase("coasting"))
        assertEquals("Holding", phase("lost"))
        assertNull(FollowPrompts.of(follow("lost")!!))
        assertNull(phase("drifting"))
        val event = """{"kind":"follow_event","ts_ms":1,"topic":"follow.reacquired","data":{"track_id":15,"similarity":0.9,"phase":"coasting"}}"""
        assertEquals(FollowPhase.Predicting, ((AutonomyParse.navFrame(event, 0) as NavFrame.Event).event as FollowEvent.Reacquired).phase)
    }

    @Test
    fun `the follow state carries the recovery fields`() {
        val text = """{"kind":"follow","ts_ms":1,"data":{"phase":"searching","search_step":1,"reacquire_remaining_s":12.5,
            "search_budget_s":25,"search_area":{"center_ne":[1,2],"semi_major_m":3,"semi_minor_m":1,"heading_deg":40},
            "vantage_ne":[5,6],"vantage_state":"arrived","occluder":{"ne":[2,2],"kind":"narrow"},
            "predicted_path_ne":[[1,2],[1.5,2.2]],"hold_ne":null,"uncertain_mode":"hold","predictive_search":false,
            "operator_resume_seq":3,"outpaced":true,"speed_limit_mps":6}}"""
        val f = (AutonomyParse.navFrame(text, 0) as NavFrame.Follow).follow
        assertEquals(SearchArea(1f to 2f, 3f, 1f, 40f), f.searchArea)
        assertEquals(Occluder(2f to 2f, "narrow"), f.occluder)
        assertEquals(12.5f, f.reacquireRemainingS)
        assertEquals(25f, f.searchBudgetS)
        assertEquals(1, f.searchStep)
        assertEquals(5f to 6f, f.vantageNe)
        assertEquals("arrived", f.vantageState)
        assertEquals(listOf(1f to 2f, 1.5f to 2.2f), f.predictedPathNe)
        assertNull(f.holdNe)
        assertEquals("hold", f.uncertainMode)
        assertEquals(false, f.predictiveSearch)
        assertEquals(3, f.operatorResumeSeq)
        assertTrue(f.outpaced)
        assertEquals(6f, f.speedLimitMps)
    }

    @Test
    fun `the follow state carries the height lowered under leaves and why`() {
        fun follow(data: String) = (AutonomyParse.navFrame("""{"kind":"follow","ts_ms":1,"data":{"phase":"following",$data}}""", 0) as NavFrame.Follow).follow
        val under = follow(""""height_setpoint_m":3.87,"height_lowered":true,"height_reason":"canopy_floor"""")
        assertEquals(3.87f, under.heightSetpointM)
        assertTrue(under.heightLowered)
        assertEquals("canopy_floor", under.heightReason)
        val set = follow(""""height_setpoint_m":6,"height_reason":"fog"""")
        assertFalse(set.heightLowered)
        assertNull(set.heightReason)
    }

    @Test
    fun `malformed recovery fields are dropped, never guessed`() {
        val text = """{"kind":"follow","ts_ms":1,"data":{"phase":"holding-await-operator",
            "search_area":{"center_ne":[1],"semi_major_m":3,"semi_minor_m":1,"heading_deg":40},
            "occluder":{"ne":[2,2],"kind":"tall"},"vantage_state":"parked","predicted_path_ne":[[1,2],["x",3],[4,5]],"hold_ne":[7.9,2.4]}}"""
        val f = (AutonomyParse.navFrame(text, 0) as NavFrame.Follow).follow
        assertNull(f.searchArea)
        assertNull(f.occluder)
        assertNull(f.vantageState)
        assertEquals(listOf(1f to 2f, 4f to 5f), f.predictedPathNe)
        assertEquals(7.9f to 2.4f, f.holdNe)
        assertNull(f.searchStep)
        assertEquals(0, f.operatorResumeSeq)
    }

    @Test
    fun `the recovery events parse`() {
        fun event(topic: String, data: String) =
            (AutonomyParse.navFrame("""{"kind":"follow_event","ts_ms":1,"topic":"$topic","data":$data}""", 9) as NavFrame.Event).event
        assertEquals(
            FollowEvent.Reacquired(9, 0.81f, 4.2f, 9, mahalanobis = 1.3f, phase = FollowPhase.Searching),
            event("follow.reacquired", """{"track_id":9,"similarity":0.81,"gap_s":4.2,"mahalanobis":1.3,"phase":"searching"}"""),
        )
        assertEquals(
            FollowEvent.Lost(9, "search_exhausted", 9, awaitingOperator = true, holdNe = 7.9f to 2.4f),
            event("follow.lost", """{"track_id":9,"reason":"search_exhausted","awaiting_operator":true,"hold_ne":[7.9,2.4]}"""),
        )
        assertEquals(FollowEvent.Stopped("lease-revoked", 9), event("follow.stopped", """{"reason":"lease-revoked"}"""))
    }

    @Test
    fun `pose yaw comes from the rotation`() {
        val text = """{"kind":"pose","ts_ms":1,"data":{"pose":{"R":[0,-1,0,1,0,0,0,0,1],"t":[1,2,0]},"health":"degraded","frame":"map_ned"}}"""
        val p = (AutonomyParse.navFrame(text, 0) as NavFrame.Pose).pose
        assertEquals(Math.PI.toFloat() / 2, p.yawRad, 1e-5f)
        assertEquals(PoseHealth.Degraded, p.health)
        assertEquals(1f, p.northM)
    }

    @Test
    fun `keepalive and unknown kinds are ignored`() {
        assertNull(AutonomyParse.navFrame("""{"kind":"keepalive","ts_ms":1}""", 0))
        assertNull(AutonomyParse.navFrame("""{"kind":"other","ts_ms":1,"data":{}}""", 0))
        assertNull(AutonomyParse.navFrame("not json", 0))
    }

    @Test
    fun `trail keeps spacing and marks health changes`() {
        var trail = emptyList<TrailPoint>()
        trail = AutonomyFeed.appendTrail(trail, PoseFrame(0f, 0f, 0f, PoseHealth.Good, 0), 0)
        trail = AutonomyFeed.appendTrail(trail, PoseFrame(0.1f, 0f, 0f, PoseHealth.Good, 10), 10)
        trail = AutonomyFeed.appendTrail(trail, PoseFrame(0.1f, 0f, 0f, PoseHealth.Lost, 20), 20)
        assertEquals(2, trail.size)
        assertTrue(trail.last().health == PoseHealth.Lost)
    }

    @Test
    fun `the view lease becomes the status route's view query`() {
        assertEquals("main:targets,depth", AutonomyFeed.viewQuery("""{"type":"view","camera_id":"main","layers":["targets","depth"]}"""))
        assertNull("a release names no layers", AutonomyFeed.viewQuery("""{"type":"view","camera_id":"main","layers":[]}"""))
        assertNull(AutonomyFeed.viewQuery("not json"))
    }

    @Test
    fun `the status body yields stream frames only for fresh kinds`() {
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"pilot":{"ts_ms":7,"age_ms":300,"data":{"owner":"idle"}},"pose":{"ts_ms":1,"age_ms":5000,"data":{}},"obstacles":null}""",
        ) as kotlinx.serialization.json.JsonObject
        val frames = AutonomyFeed.statusFrames(body)
        assertEquals(1, frames.size)
        assertEquals(300L, frames.single().ageMs)
        assertEquals(7L, frames.single().tsMs)
        val f = kotlinx.serialization.json.Json.parseToJsonElement(frames.single().text) as kotlinx.serialization.json.JsonObject
        assertEquals("\"pilot\"", f["kind"].toString())
        assertEquals("7", f["ts_ms"].toString())
    }

    @Test
    fun `a compute node's live occupancy frame reads as a map tile delta for its drone only`() {
        val delta = AutonomyParse.worldOccupancy(hex(OCCUPANCY_FRAME), "drone-1")!!
        assertEquals(7L, delta.seq)
        assertEquals(0.05f, delta.resM)
        assertEquals(64, delta.tileCells)
        val tile = delta.tiles.single()
        assertEquals(-1 to 2, tile.ix to tile.iy)
        assertEquals("iVBORw0KGgo=", android.util.Base64.encodeToString(tile.png, android.util.Base64.NO_WRAP))
        // Another drone's frame, the point-cloud topic and a truncated frame are not tiles.
        assertNull(AutonomyParse.worldOccupancy(hex(OCCUPANCY_FRAME), "drone-2"))
        assertNull(AutonomyParse.worldOccupancy(hex(POINTCLOUD_FRAME), "drone-1"))
        assertNull(AutonomyParse.worldOccupancy(hex(OCCUPANCY_FRAME).copyOf(40), "drone-1"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        /** `NodeEvent::encode` of a `LiveOccupancy` (seq 7, one tile at (-1, 2)) for `drone-1`, as the node sends it. */
        const val OCCUPANCY_FRAME =
            "84a17602a5746f706963ba7265636f6e7374727563742e6c6976652e6f63637570616e6379a96465766963655f6964a764726f6e652d31" +
                "a77061796c6f6164dc005acc86cca46b696e64cca46c697665cca373657107cca56672616d65cca76d61705f6e6564cca57265735f6d" +
                "ccca3d4ccccccccdccaa74696c655f63656c6c7340cca574696c6573cc91cc83cca26978ccffcca2697902cca7706e675f623634ccac" +
                "6956424f5277304b47676f3d"

        /** `NodeEvent::encode` of a `LivePointCloud` for `drone-1`. */
        const val POINTCLOUD_FRAME =
            "84a17602a5746f706963bb7265636f6e7374727563742e6c6976652e706f696e74636c6f7564a96465766963655f6964a764726f6e652d31" +
                "a77061796c6f6164dc005fcc86cca46b696e64cca46c697665cca373657103cca56672616d65cca76d61705f6e6564cca7766f78656c5f6d" +
                "ccca3d4ccccccccdcca5636f756e7402ccaa706f696e74735f623634ccd9204141434150774141414541414141432f41414341514141416f" +
                "454141414d6841"
    }
}
