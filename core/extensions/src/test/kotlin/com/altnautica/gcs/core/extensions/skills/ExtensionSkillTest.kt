package com.altnautica.gcs.core.extensions.skills

import app.cash.turbine.test
import com.altnautica.gcs.core.extensions.FakeExtensionApi
import com.altnautica.gcs.core.extensions.FakeNodeSource
import com.altnautica.gcs.core.extensions.NODE
import com.altnautica.gcs.core.extensions.bridge.ExtensionEventBus
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.ExtensionStateFeed
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.fixture
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetailParser
import com.altnautica.gcs.core.extensions.repository
import com.altnautica.gcs.core.extensions.summary
import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSkillTest {

    private val ctx = SkillContext(null, armed = true, mode = null, linkFresh = true, checklistReady = true, autonomousNav = null)
    private val followMe = ExtensionDetailParser.parse(fixture("follow-me.detail.json"))!!
    private val contribution = followMe.contributions.skills.single()

    private fun skill(reach: ExtensionReach, payload: String? = null, writes: MutableList<Pair<String, JsonElement>> = mutableListOf()) =
        ExtensionSkill(
            node = NODE,
            pluginId = followMe.pluginId,
            contribution = contribution,
            reach = reach,
            reported = ReportedState.from(payload?.let { Json.parseToJsonElement(it) }),
            writer = { k, v -> writes += k to v; WriteOutcome.Done },
        )

    @Test
    fun identityBindingAndGates() {
        val s = skill(ExtensionReach.Lan)
        assertEquals("com.altnautica.follow-me:follow-me", s.id)
        assertEquals(SkillSource.Extension("com.altnautica.follow-me"), s.source)
        assertEquals(ArmRequirement.Armed, s.armRequirement)
        assertEquals(ConfirmGesture.Hold, s.confirm!!.gesture)
        assertEquals("shift+f", s.defaultBinding!!.keyChord)
        assertEquals("my_location", s.icon)
        assertTrue(s.toggle)
    }

    @Test
    fun activateAndDeactivateWriteTheConfigKey() = runTest {
        val writes = mutableListOf<Pair<String, JsonElement>>()
        val s = skill(ExtensionReach.Lan, writes = writes)
        assertEquals(CommandResult.Accepted, s.activate(ctx, emptyMap()))
        assertEquals(CommandResult.Accepted, s.deactivate(ctx))
        assertEquals(listOf("active" to JsonPrimitive(true), "active" to JsonPrimitive(false)), writes)
    }

    @Test
    fun cloudOnlyReachDisablesWithReason() {
        val st = skill(ExtensionReach.CloudOnly).state(ctx)
        assertFalse(st.enabled)
        assertEquals(ExtensionReach.REASON_LAN_OR_GROUND_REQUIRED, st.reasonKey)
        assertEquals(ExtensionReach.REASON_NO_AGENT, skill(ExtensionReach.Unreachable).state(ctx).reasonKey)
        assertEquals(ExtensionReach.CloudOnly, ExtensionReach.classify(listOf(Reach.CloudLive, Reach.DirectFc)))
        assertEquals(
            ExtensionReach.ViaGround(DeviceId("gs")),
            ExtensionReach.classify(listOf(Reach.CloudLive, Reach.ViaGround(DeviceId("gs")))),
        )
    }

    @Test
    fun cloudOnlyActivationIsDenied() = runTest {
        val s = ExtensionSkill(NODE, followMe.pluginId, contribution, ExtensionReach.CloudOnly, ReportedState.Idle) { _, _ ->
            WriteOutcome.NoPath(ExtensionReach.REASON_LAN_OR_GROUND_REQUIRED)
        }
        assertEquals(CommandResult.Denied(ExtensionReach.REASON_LAN_OR_GROUND_REQUIRED), s.activate(ctx, emptyMap()))
    }

    @Test
    fun reportedStateMapping() {
        assertTrue(skill(ExtensionReach.Lan, """{"active":true,"commanding":true}""").state(ctx).active)
        assertEquals("?", ReportedState.from(Json.parseToJsonElement("""{"active":true,"lock_state":"uncertain"}""")).badge)
        assertFalse(skill(ExtensionReach.Lan, """{"active":false}""").state(ctx).active)
        val disabled = skill(ExtensionReach.Lan, """{"state":"disabled","reason":"No session"}""").state(ctx)
        assertFalse(disabled.enabled)
        assertEquals("No session", disabled.reasonKey)
        assertEquals(REASON_EXTENSION_UNAVAILABLE, skill(ExtensionReach.Lan, """{"state":"disabled"}""").state(ctx).reasonKey)
    }

    @Test
    fun snapshotsDifferWhenStateChanges() {
        assertNotEquals(skill(ExtensionReach.Lan, """{"active":true}"""), skill(ExtensionReach.Lan, """{"active":false}"""))
        assertEquals(skill(ExtensionReach.Lan, """{"active":true}"""), skill(ExtensionReach.Lan, """{"active":true}"""))
    }

    @Test
    fun contributorPollsStateEvery750msAndReemits() = runTest {
        val api = FakeExtensionApi(linkedMapOf(followMe.pluginId to fixture("follow-me.detail.json")))
        val source = FakeNodeSource(listOf(summary(listOf(Reach.Lan))))
        val repo = repository(api, source, backgroundScope)
        val feed = ExtensionStateFeed(repo, ExtensionEventBus(), backgroundScope)
        val focus = ExtensionCockpitFocus(feed)
        val contributor = ExtensionSkillContributor(focus, repo, feed)

        contributor.skills.test {
            assertTrue(awaitItem().isEmpty())
            val release = focus.attach(NODE)
            runCurrent()
            var skills = awaitItem()
            while (skills.isEmpty()) skills = awaitItem()
            assertFalse(skills.single().state(ctx).active)
            val callsAfterFirst = api.stateCalls

            api.stateBodies[followMe.pluginId] = Json.parseToJsonElement(
                """{"follow.state":{"payload":{"active":true,"commanding":true},"ts_ms":1}}""",
            ) as JsonObject
            advanceTimeBy(ExtensionStateFeed.POLL_INTERVAL_MS + 1)
            runCurrent()
            assertTrue(awaitItem().single().state(ctx).active)
            assertTrue(api.stateCalls > callsAfterFirst)

            release()
            assertTrue(awaitItem().isEmpty())
            val calls = api.stateCalls
            advanceTimeBy(5_000)
            assertEquals(calls, api.stateCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun targetActionInheritsSkillGatesAndDesignatesFirst() = runTest {
        val api = FakeExtensionApi(linkedMapOf(followMe.pluginId to fixture("follow-me.detail.json")))
        val repo = repository(api, FakeNodeSource(listOf(summary(listOf(Reach.Lan)))), backgroundScope)
        val feed = ExtensionStateFeed(repo, ExtensionEventBus(), backgroundScope)
        val contributor = ExtensionSkillContributor(ExtensionCockpitFocus(feed), repo, feed)
        runCurrent()
        repo.refresh(NODE)
        val detail = (repo.extensions(NODE).value as com.altnautica.gcs.core.extensions.data.ExtensionsState.Loaded).extensions
        val actions = contributor.buildTargetActions(NODE, detail, ExtensionReach.Lan)
        val follow = actions.first { it.action.id == "follow" }
        val stop = actions.first { it.action.id == "stop-follow" }
        assertEquals(ArmRequirement.Armed, follow.armRequirement)
        assertEquals(ConfirmGesture.Hold, follow.confirm!!.gesture)
        assertEquals(ArmRequirement.Any, stop.armRequirement)

        val args = mapOf("camera_id" to "cam1", "bbox" to mapOf("x" to 10, "y" to 20, "width" to 30, "height" to 40))
        assertEquals(CommandResult.Accepted, follow.activate(ctx, args))
        assertEquals("cam1", (api.lastDesignate!!["camera_id"] as JsonPrimitive).content)
        assertEquals(Triple(followMe.pluginId, "active", JsonPrimitive(true) as JsonElement), api.writes.single())

        api.designated = false
        assertTrue(follow.activate(ctx, args) is CommandResult.Rejected)
        assertEquals(1, api.writes.size)
    }
}
