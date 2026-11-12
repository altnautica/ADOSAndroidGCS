package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.map.GeoPoint
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
}
