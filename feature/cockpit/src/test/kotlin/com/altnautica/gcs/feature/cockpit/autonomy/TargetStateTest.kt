package com.altnautica.gcs.feature.cockpit.autonomy

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** HUD v2 target resolver test vectors (T1-T18); frame 1280×720, camera `main`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TargetStateTest {
    private val text = TargetText(testSpec.setupStrings)
    private val now = 10_000L
    private val box12 = Box(600f, 280f, 60f, 150f)

    private fun det(track: Int?, conf: Float, lock: LockState? = null, cls: String = "person", b: Box = box12) =
        Detection(b, cls, conf, track, lock)

    private fun batch(vararg items: Detection, camera: String = "main") = DetectionBatch(camera, 1, 1280f, 720f, now - 50, items.toList())

    private fun follow(
        phase: FollowPhase,
        track: Int? = 12,
        range: Float? = null,
        sigma: Float? = null,
        hold: String? = null,
        coast: Float? = null,
        predicted: PredictedBox? = null,
        bearing: Float? = null,
    ) = FollowState(
        phase, track, null, range, sigma, emptySet(), null, null, null, null, coast, null, hold, bearing, null, null, null, predicted,
    )

    private fun resolve(
        b: DetectionBatch?,
        des: Designation? = null,
        f: FollowState? = null,
        age: Long = 100,
        events: List<FollowEvent> = emptyList(),
        opts: ResolveOptions = ResolveOptions(),
    ) = TargetState.resolveTargets(b, "main", des, f, age, events, now, opts)

    private val designated = Designation("main", 12, null)

    @Test
    fun `T1 candidate above the confidence floor`() {
        val items = resolve(batch(det(null, 0.82f)))
        assertEquals(TargetKind.Candidate, items.single().kind)
        assertEquals("PERSON 82%", text.tag(items.single().top!!))
    }

    @Test
    fun `T2 low-confidence candidate is not drawn`() {
        assertTrue(resolve(batch(det(null, 0.30f))).isEmpty())
    }

    @Test
    fun `T3 tracked target`() {
        val item = resolve(batch(det(12, 0.9f, LockState.Locked))).single()
        assertEquals(TargetKind.Tracked, item.kind)
        assertEquals("PERSON #12", text.tag(item.top!!))
    }

    @Test
    fun `T4 lost track is not drawn`() {
        assertTrue(resolve(batch(det(12, 0.9f, LockState.Lost))).isEmpty())
    }

    @Test
    fun `T5 designated without follow is locked`() {
        val item = resolve(batch(det(12, 0.97f, LockState.Locked)), designated).single()
        assertEquals(TargetKind.Locked, item.kind)
        assertEquals("LOCK #12 97%", text.tag(item.top!!))
        assertTrue(item.bottom.isEmpty())
    }

    @Test
    fun `T6 following draws track, range and two sigma`() {
        val item = resolve(batch(det(12, 0.97f, LockState.Locked)), designated, follow(FollowPhase.Following, range = 10.2f, sigma = 0.3f)).single()
        assertEquals(TargetKind.Following, item.kind)
        assertEquals("TRACK 97%", text.tag(item.top!!))
        assertEquals(listOf("10.2 m", "±0.6 m"), item.bottom.map(text::tag))
    }

    @Test
    fun `T7 stale follow falls back to locked with FOLLOW STALE`() {
        val item = resolve(batch(det(12, 0.97f, LockState.Locked)), designated, follow(FollowPhase.Following, range = 10.2f, sigma = 0.3f), age = 2_500).single()
        assertEquals(TargetKind.Locked, item.kind)
        assertTrue(TargetModifier.FollowStale in item.modifiers)
        assertEquals(listOf("FOLLOW STALE"), item.bottom.map(text::tag))
    }

    @Test
    fun `T8 uncertain following appends a question mark`() {
        val item = resolve(batch(det(12, 0.61f, LockState.Uncertain)), designated, follow(FollowPhase.Following, range = 10.2f, sigma = 0.3f)).single()
        assertEquals(TargetKind.Following, item.kind)
        assertTrue(TargetModifier.Uncertain in item.modifiers)
        assertEquals("TRACK 61% ?", text.tag(item.top!!))
    }

    @Test
    fun `T9 holding shows the shortened reason`() {
        val item = resolve(batch(det(12, 0.97f, LockState.Locked)), designated, follow(FollowPhase.Holding, hold = "HOLD_NO_RANGE")).single()
        assertEquals(TargetKind.Holding, item.kind)
        assertEquals("HOLD · NO RANGE", text.tag(item.top!!))
    }

    @Test
    fun `T10 coasting draws at the predicted box`() {
        val p = PredictedBox("main", 1280f, 720f, Box(600f, 300f, 40f, 98f))
        val item = resolve(batch(), designated, follow(FollowPhase.Coasting, coast = 1.43f, predicted = p)).single()
        assertEquals(TargetKind.Coasting, item.kind)
        assertEquals(p.box, item.rect)
        assertEquals("COAST 1.4 s", text.tag(item.top!!))
    }

    @Test
    fun `T11 searching off frame points at 54 degrees from the top edge`() {
        val item = resolve(batch(), designated, follow(FollowPhase.Searching, bearing = 64f), opts = ResolveOptions(vehicleYawDeg = 10f)).single()
        assertEquals(TargetKind.SearchingOffFrame, item.kind)
        assertEquals(54f, item.relBearingDeg!!, 1e-3f)
        val (x, y) = HudGeometry.offFrameAnchor(item.relBearingDeg!!, 1280f, 720f, 16f)
        assertEquals(1113f, x, 1f)
        assertEquals(16f, y, 1e-3f)
        assertEquals("SEARCHING · 064°", text.tag(item.top!!))
    }

    @Test
    fun `T12 re-acquired snaps the new track`() {
        val ev = FollowEvent.Reacquired(15, 0.862f, 1.8f, now - 100)
        val item = resolve(batch(det(15, 0.9f, LockState.Locked)), events = listOf(ev)).single()
        assertTrue(TargetModifier.Reacquired in item.modifiers)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val toast = ctx.getString(com.altnautica.gcs.feature.cockpit.R.string.hud_toast_reacquired, 15, "0.86", "1.8")
        assertEquals("Re-acquired #15 · 0.86 · 1.8 s gap", toast)
    }

    @Test
    fun `T13 lost shows at the last rect for 900 ms`() {
        val opts = ResolveOptions(lastFollowedRect = box12, lostAtMs = now - 100)
        val item = resolve(batch(), null, follow(FollowPhase.Lost), opts = opts).single()
        assertEquals(TargetKind.Lost, item.kind)
        assertEquals(box12, item.rect)
        assertEquals("LOST", text.tag(item.top!!))
        val later = TargetState.resolveTargets(batch(), "main", null, follow(FollowPhase.Lost), 100, emptyList(), now + 900, opts)
        assertTrue(later.isEmpty())
    }

    @Test
    fun `T14 a batch from another camera draws nothing`() {
        assertTrue(resolve(batch(det(12, 0.97f, LockState.Locked), camera = "belly")).isEmpty())
    }

    @Test
    fun `T15 video loss removes every item`() {
        val items = resolve(batch(det(12, 0.97f)), designated, follow(FollowPhase.Following, range = 10.2f, sigma = 0.3f), opts = ResolveOptions(videoLost = true))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `T16 bracket arm clamps`() {
        assertEquals(17.6f, HudGeometry.bracketArm(80f, 120f), 1e-4f)
        assertEquals(10f, HudGeometry.bracketArm(20f, 30f), 1e-4f)
        assertEquals(28f, HudGeometry.bracketArm(300f, 400f), 1e-4f)
    }

    @Test
    fun `T17 range interval`() {
        assertEquals("9.6 – 10.8 m", HudGeometry.rangeInterval(10.2f, 0.3f))
    }

    @Test
    fun `T18 range formatting`() {
        assertEquals("10.0 m", HudGeometry.formatRange(9.96f))
        assertEquals("123 m", HudGeometry.formatRange(123.4f))
    }

    @Test
    fun `stale batch draws nothing`() {
        val old = DetectionBatch("main", 1, 1280f, 720f, now - 2_500, listOf(det(12, 0.9f, LockState.Locked)))
        assertTrue(resolve(old).isEmpty())
    }

    private fun followingAt(bearing: Float, depression: Float?) = FollowState(
        FollowPhase.Following, 12, null, 10.2f, 0.3f, emptySet(), null, null, null, null, null, null, null, bearing, depression, null, null, null,
    )

    private val fov87 = ResolveOptions(
        vehicleYawDeg = 10f,
        cameraHfovDeg = 87f,
        cameraVfovDeg = HudGeometry.verticalFov(87f, 1280f, 720f),
        cameraPitchDeg = -15f,
    )

    @Test
    fun `following outside the horizontal FOV draws the T11 edge arrow`() {
        // rel = 64 - 10 = 54°, beyond the 43.5° half FOV: the arrow sits where T11 puts it.
        val item = resolve(batch(), designated, followingAt(64f, 10f), opts = fov87).single()
        assertEquals(TargetKind.SearchingOffFrame, item.kind)
        val (x, y) = HudGeometry.offFrameAnchor(item.relBearingDeg!!, 1280f, 720f, 16f)
        assertEquals(1113f, x, 1f)
        assertEquals(16f, y, 1e-3f)
        assertEquals("SEARCHING · 064°", text.tag(item.top!!))
    }

    @Test
    fun `following inside the FOV draws no arrow`() {
        assertTrue(resolve(batch(), designated, followingAt(30f, 15f), opts = fov87).isEmpty())
    }

    @Test
    fun `following below the vertical FOV draws an arrow`() {
        // Vertical FOV ≈ 53.4°; axis 15° down, so a 60° depression is 45° below the axis.
        val item = resolve(batch(), designated, followingAt(30f, 60f), opts = fov87).single()
        assertEquals(TargetKind.SearchingOffFrame, item.kind)
    }

    @Test
    fun `following with an unknown FOV draws no arrow`() {
        assertTrue(resolve(batch(), designated, followingAt(64f, 10f), opts = ResolveOptions(vehicleYawDeg = 10f)).isEmpty())
    }

    @Test
    fun `at most eight candidate tags`() {
        val many = (0 until 12).map { det(null, 0.5f + it / 100f, b = Box(it * 100f, 0f, 40f, 100f)) }
        val items = resolve(batch(*many.toTypedArray()))
        assertEquals(12, items.size)
        assertEquals(8, items.count { it.top != null })
    }
}
