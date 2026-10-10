package com.altnautica.gcs.radio.rx.hop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HopFollowerTest {

    private val key = HopAnnounce.derivePairKey(ByteArray(64) { 3 })

    @Test
    fun `retunes after the announced delay`() = runTest {
        val tuned = mutableListOf<Int>()
        val follower = HopFollower(key, 149, backgroundScope) { tuned += it; true }
        follower.onControlPacket(HopAnnounce.build(3000, 157, HopTrigger.PERIODIC, key))
        advanceTimeBy(2999)
        runCurrent()
        assertEquals(emptyList<Int>(), tuned)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(157), tuned)
        assertEquals(157, follower.currentChannel)
        assertEquals(1, follower.hopsFollowed)
        assertNull(follower.pendingChannel)
    }

    @Test
    fun `repeated copies of one announcement retune once`() = runTest {
        val tuned = mutableListOf<Int>()
        val follower = HopFollower(key, 149, backgroundScope) { tuned += it; true }
        for (left in 3000L downTo 100L step 100L) {
            follower.onControlPacket(HopAnnounce.build(left, 157, HopTrigger.PERIODIC, key))
            advanceTimeBy(100)
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(157), tuned)
    }

    @Test
    fun `a forged announcement is ignored`() = runTest {
        val tuned = mutableListOf<Int>()
        val follower = HopFollower(key, 149, backgroundScope) { tuned += it; true }
        val forged = HopAnnounce.build(10, 36, HopTrigger.PERIODIC, HopAnnounce.derivePairKey(null))
        assertNull(follower.onControlPacket(forged))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList<Int>(), tuned)
        assertEquals(149, follower.currentChannel)
    }

    @Test
    fun `a new target replaces the pending plan`() = runTest {
        val tuned = mutableListOf<Int>()
        val follower = HopFollower(key, 149, backgroundScope) { tuned += it; true }
        follower.onControlPacket(HopAnnounce.build(3000, 157, HopTrigger.PERIODIC, key))
        advanceTimeBy(1000)
        follower.onControlPacket(HopAnnounce.build(500, 36, HopTrigger.MANUAL, key))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(36), tuned)
    }

    @Test
    fun `an announcement for the current channel does nothing`() = runTest {
        val tuned = mutableListOf<Int>()
        val follower = HopFollower(key, 149, backgroundScope) { tuned += it; true }
        follower.onControlPacket(HopAnnounce.build(10, 149, HopTrigger.PERIODIC, key))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList<Int>(), tuned)
    }

    @Test
    fun `a failed retune keeps the old channel`() = runTest {
        val follower = HopFollower(key, 149, backgroundScope) { false }
        follower.onControlPacket(HopAnnounce.build(10, 157, HopTrigger.PERIODIC, key))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(149, follower.currentChannel)
        assertEquals(0, follower.hopsFollowed)
    }
}
