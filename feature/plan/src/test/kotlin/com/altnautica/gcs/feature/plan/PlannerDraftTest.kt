package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.map.GeoPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerDraftTest {

    private val h = PlannerHarness()
    private val a = GeoPoint(12.0, 77.0)
    private val b = GeoPoint(12.001, 77.001)
    private val c = GeoPoint(12.002, 77.002)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a plan discarded on leave does not come back as a draft`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            testScheduler.advanceTimeBy(PlannerViewModel.DRAFT_DEBOUNCE_MS + 1)
            runCurrent()
            assertNotNull(h.drafts.draft)

            vm.requestDiscard(DiscardAction.Leave)
            vm.confirmDiscard()
            runCurrent()
            assertTrue(vm.state.value.leaveApproved)
        }
        assertNull(h.drafts.draft)

        h.withViewModel { vm ->
            runCurrent()
            assertTrue(vm.state.value.plan.isEmpty)
            assertFalse(vm.state.value.dirty)
        }
    }

    @Test
    fun `leaving right after a save leaves no draft`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            testScheduler.advanceTimeBy(PlannerViewModel.DRAFT_DEBOUNCE_MS + 1)
            runCurrent()
            assertNotNull(h.drafts.draft)

            vm.save("field")
            runCurrent()
        }
        assertNull(h.drafts.draft)
    }

    @Test
    fun `a plan discarded through New leaves no draft when the screen closes at once`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            testScheduler.advanceTimeBy(PlannerViewModel.DRAFT_DEBOUNCE_MS + 1)
            runCurrent()
            assertNotNull(h.drafts.draft)

            vm.requestDiscard(DiscardAction.New)
            vm.confirmDiscard()
            runCurrent()
            // The plan is clean now, so Back closes the screen well inside the debounce.
            assertFalse(vm.state.value.dirty)
        }
        assertNull(h.drafts.draft)
    }

    @Test
    fun `a plan discarded through Load leaves no draft when the screen closes at once`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            vm.save("field")
            runCurrent()
            val id = h.dao.rows.value.single().id
            vm.onMapTap(c)
            testScheduler.advanceTimeBy(PlannerViewModel.DRAFT_DEBOUNCE_MS + 1)
            runCurrent()
            assertNotNull(h.drafts.draft)

            vm.requestDiscard(DiscardAction.Load(id))
            vm.confirmDiscard()
            runCurrent()
            assertFalse(vm.state.value.dirty)
        }
        assertNull(h.drafts.draft)
    }

    @Test
    fun `a draft write under way when the plan is discarded cannot bring it back`() = runTest {
        val disk = CompletableDeferred<Unit>()
        h.drafts.gate = disk
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            testScheduler.advanceTimeBy(PlannerViewModel.DRAFT_DEBOUNCE_MS + 1)
            runCurrent()
            // The debounced write is still on its way to disk.
            assertNull(h.drafts.draft)

            // A further edit queues the next debounced write behind it.
            vm.onMapTap(c)
            vm.requestDiscard(DiscardAction.Leave)
            vm.confirmDiscard()
            runCurrent()

            // The slow write lands first; the clear must follow it, not precede it.
            disk.complete(Unit)
            runCurrent()
            assertNull(h.drafts.draft)

            // The queued write's debounce runs out while the screen is still closing.
            testScheduler.advanceTimeBy(PlannerViewModel.DRAFT_DEBOUNCE_MS + 1)
            runCurrent()
            assertNull(h.drafts.draft)
        }
        assertNull(h.drafts.draft)
    }
}
