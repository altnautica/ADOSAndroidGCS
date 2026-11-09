package com.altnautica.gcs.feature.cockpit.autonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
        val text = """{"kind":"follow","ts_ms":1,"data":{"phase":"coasting","track_id":12,"range_m":10.2,"range_sigma_m":0.3,
            "range_cues":[{"kind":"ground","range_m":10.1,"sigma_m":0.4},{"kind":"lidar","range_m":10.0,"sigma_m":0.05}],
            "agl_m":4.1,"agl_source":"rng","reacquire_mode":"smart","coast_remaining_s":1.4,"bearing_deg":64,
            "target_ned":[5.5,1.5],"predicted_bbox":{"camera_id":"main","frame_w":1280,"frame_h":720,"x":600,"y":300,"w":40,"h":98}}}"""
        val f = (AutonomyParse.navFrame(text, 5) as NavFrame.Follow).follow
        assertEquals(FollowPhase.Coasting, f.phase)
        assertEquals(12, f.trackId)
        assertEquals(setOf(RangeCue.Ground, RangeCue.Lidar), f.rangeCues)
        assertEquals("smart", f.reacquireMode)
        assertEquals(5.5f to 1.5f, f.targetNed)
        assertEquals(Box(600f, 300f, 40f, 98f), f.predicted!!.box)
    }

    @Test
    fun `follow event uses the top-level topic`() {
        val text = """{"kind":"follow_event","ts_ms":1,"topic":"follow.reacquired","data":{"track_id":15,"similarity":0.862,"gap_s":1.8}}"""
        val e = (AutonomyParse.navFrame(text, 7) as NavFrame.Event).event as FollowEvent.Reacquired
        assertEquals(15, e.trackId)
        assertEquals(7L, e.atMs)
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
        val f = kotlinx.serialization.json.Json.parseToJsonElement(frames.single()) as kotlinx.serialization.json.JsonObject
        assertEquals("\"pilot\"", f["kind"].toString())
        assertEquals("7", f["ts_ms"].toString())
    }
}
