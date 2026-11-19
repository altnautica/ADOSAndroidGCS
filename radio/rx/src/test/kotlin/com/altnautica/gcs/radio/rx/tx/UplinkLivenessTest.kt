package com.altnautica.gcs.radio.rx.tx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UplinkLivenessTest {

    private fun window() = TxLivenessWindow(0L)

    @Test
    fun `offered bytes that reach the radio are healthy`() {
        val w = window()
        w.observe(TxSample(0, 0, 0), 0)
        w.observe(TxSample(1000, 1200, 2400), 29_000)
        assertNull(w.evaluate(29_000))
        assertEquals(TxVerdict.Healthy, w.evaluate(30_000))
    }

    @Test
    fun `nothing offered is idle, not a stall`() {
        val w = window()
        w.observe(TxSample(5, 5, 5), 0)
        w.observe(TxSample(5, 5, 5), 29_000)
        assertEquals(TxVerdict.Idle, w.evaluate(30_000))
    }

    @Test
    fun `offered bytes that never leave the radio are a stall even when the pipe takes them`() {
        val w = window()
        w.observe(TxSample(0, 0, 100), 0)
        w.observe(TxSample(1000, 1200, 100), 29_000)
        assertEquals(TxVerdict.StalledNotInjecting, w.evaluate(30_000))
    }

    @Test
    fun `offered bytes the pipe drops are a stall`() {
        val w = window()
        w.observe(TxSample(0, 0, 0), 0)
        w.observe(TxSample(1000, 0, 5000), 29_000)
        assertEquals(TxVerdict.StalledNotInjecting, w.evaluate(30_000))
    }

    @Test
    fun `no sample for the window is a silent stall`() {
        val w = window()
        w.observe(TxSample(0, 0, 0), 0)
        assertEquals(TxVerdict.StalledSilent, w.evaluate(30_000))
    }

    @Test
    fun `a stall keeps judging the same window until bytes move`() {
        val w = window()
        w.observe(TxSample(0, 0, 0), 0)
        w.observe(TxSample(1000, 0, 0), 30_000)
        assertEquals(TxVerdict.StalledNotInjecting, w.evaluate(30_000))
        w.observe(TxSample(2000, 1200, 1200), 35_000)
        assertEquals(TxVerdict.Healthy, w.evaluate(35_000))
    }

    @Test
    fun `the uplink is active only with drone-side evidence in the window`() {
        val judge = UplinkJudge()
        judge.verdicts(TxVerdict.Healthy, TxVerdict.Idle)
        assertEquals(UplinkState.Unverified, judge.state(1_000))
        judge.evidence(1_000)
        assertEquals(UplinkState.Active, judge.state(31_000))
        assertEquals(UplinkState.Unverified, judge.state(31_001))
    }

    @Test
    fun `a stall or a lost radio is down whatever the evidence`() {
        val judge = UplinkJudge()
        judge.evidence(0)
        judge.verdicts(TxVerdict.Healthy, TxVerdict.StalledSilent)
        assertEquals(UplinkState.Down, judge.state(1))
        judge.verdicts(null, null)
        assertEquals(UplinkState.Down, judge.state(1))
        judge.verdicts(TxVerdict.Healthy, TxVerdict.Healthy)
        assertEquals(UplinkState.Active, judge.state(1))
        judge.radioLost()
        assertEquals(UplinkState.Down, judge.state(1))
    }
}
