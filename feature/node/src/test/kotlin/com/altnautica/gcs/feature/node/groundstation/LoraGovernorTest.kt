package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentErrorParser
import com.altnautica.gcs.core.agent.http.AgentJson
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.sensors.LoraRule
import com.altnautica.gcs.core.agent.sensors.LoraStatus
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoraGovernorTest {

    private fun status(json: String) = AgentJson.json.decodeFromString(LoraStatus.serializer(), json)

    /** The India example from the LoRa route. */
    private val india = status(
        """
        {"present":true,"transport":"e22","module_band":"850-930",
         "region":{"code":"IN","name":"India","plan_id":"IN865","source":"network.regulatory.region","band_mhz":[865.0,867.0],"citation":"G.S.R. 853(E), 2021"},
         "channel":{"freq_mhz":866.0,"bw_khz":125,"sf":9,"erp_mw":25,"max_erp_mw":25},
         "node_id":17,"tx_allowed":true,"blocked_reason":null,
         "governor":{"rules":[
           {"kind":"duty_cycle","window_s":3600,"limit_pct":1.0,"used_pct":0.4,"airtime_ms_window":14400,"budget_ms_window":36000,"exhausted":false,"sub_band":"865.0-867.0"},
           {"kind":"dwell_time","limit_ms":400,"per_s":20,"last_tx_ms":206,"ok":true},
           {"kind":"lbt","threshold_dbm":-80,"listen_us":5000,"last_rssi_dbm":-112,"busy_count":0},
           {"kind":"max_power","limit_mw":25,"configured_mw":25,"ok":true},
           {"kind":"future_rule"}],
          "next_tx_allowed_ms":0,"last_refusal":null},
         "tx":{"frames":120,"refused":0,"refused_by_rule":{"region_unset":0,"max_power":0,"lbt":0,"dwell_time":0,"duty_cycle":0}},
         "rx":{"frames":340,"crc_errors":2},
         "meshtastic":{"enabled":false,"connected":false,"port":null,"region_preset":"IN"}}
        """.trimIndent(),
    )

    @Test
    fun normalStateHasNoBannerAndOneMeterPerDeclaredRule() {
        val ui = LoraGovernor.map(india)
        assertNull(ui.block)
        assertTrue(ui.txAllowed)
        assertEquals("IN", ui.regionCode)
        assertTrue(ui.regionFromCountry)
        assertEquals("G.S.R. 853(E), 2021", ui.citation)
        // Unknown rule kinds are not drawn; plan order is kept.
        assertEquals(listOf(RuleMeter.Duty::class, RuleMeter.Dwell::class, RuleMeter.Lbt::class, RuleMeter.Power::class), ui.meters.map { it::class })

        val duty = ui.meters[0] as RuleMeter.Duty
        assertEquals(0.4f, duty.fraction!!, 0.0001f)
        assertEquals(MeterLevel.Normal, duty.level)
        assertEquals("865.0-867.0", duty.subBand)

        val dwell = ui.meters[1] as RuleMeter.Dwell
        assertEquals(206f / 400f, dwell.fraction!!, 0.0001f)

        val lbt = ui.meters[2] as RuleMeter.Lbt
        assertEquals(false, lbt.busy)

        // Power configured at the plan's clamp is normal, not a limit hit.
        assertEquals(MeterLevel.Normal, (ui.meters[3] as RuleMeter.Power).level)
    }

    @Test
    fun refusalsListInRuleOrder() {
        val ui = LoraGovernor.map(india)
        assertEquals(listOf("duty_cycle", "dwell_time", "lbt", "max_power", "region_unset"), ui.refusals.map { it.rule })
    }

    @Test
    fun meterBandsAt70PercentAndAtTheLimit() {
        fun duty(air: Long, exhausted: Boolean = false) =
            LoraGovernor.meter(LoraRule("duty_cycle", airtimeMsWindow = air, budgetMsWindow = 36_000, exhausted = exhausted))!!.level
        assertEquals(MeterLevel.Normal, duty(25_000))
        assertEquals(MeterLevel.Near, duty(25_200))
        assertEquals(MeterLevel.Near, duty(35_999))
        assertEquals(MeterLevel.AtLimit, duty(36_000))
        assertEquals(MeterLevel.AtLimit, duty(10, exhausted = true))

        val over = LoraGovernor.meter(LoraRule("max_power", limitMw = 25.0, configuredMw = 30.0))!!
        assertEquals(MeterLevel.AtLimit, over.level)
        val flagged = LoraGovernor.meter(LoraRule("dwell_time", limitMs = 400, lastTxMs = 100, ok = false))!!
        assertEquals(MeterLevel.AtLimit, flagged.level)
    }

    @Test
    fun lbtBusyWhenTheLastSampleReachesTheThreshold() {
        val busy = LoraGovernor.meter(LoraRule("lbt", thresholdDbm = -80.0, lastRssiDbm = -75.0, busyCount = 3)) as RuleMeter.Lbt
        assertEquals(true, busy.busy)
        assertEquals(MeterLevel.Near, busy.level)
        val unknown = LoraGovernor.meter(LoraRule("lbt", thresholdDbm = -80.0)) as RuleMeter.Lbt
        assertNull(unknown.busy)
    }

    @Test
    fun everyBlockedReasonHasItsFixAndCountdown() {
        fun b(reason: String) = LoraGovernor.block(reason, txAllowed = false)!!
        assertEquals(LoraBlockReason.RegionUnset, b("region_unset").reason)
        assertEquals(LoraFix.SetRegion, b("region_unset").fix)
        assertFalse(b("region_unset").paused)

        assertEquals(LoraBlockReason.ModuleBandMismatch, b("module_band_mismatch").reason)
        assertEquals(LoraFix.ChangeRegion, b("module_band_mismatch").fix)

        assertEquals(LoraBlockReason.DutyExhausted, b("duty_exhausted").reason)
        assertTrue(b("duty_exhausted").countdown)
        assertTrue(b("duty_exhausted").paused)
        assertEquals(LoraFix.None, b("duty_exhausted").fix)

        assertTrue(b("dwell_time").countdown)
        assertEquals(LoraBlockReason.LbtBusy, b("lbt_busy").reason)
        assertFalse(b("lbt_busy").countdown)
        assertTrue(b("lbt_busy").paused)

        assertEquals(LoraFix.PowerSettings, b("power_over_limit").fix)
        assertEquals(LoraBlockReason.Other, b("something_else").reason)
    }

    @Test
    fun transmitRefusedWithoutAReasonStillShowsABanner() {
        assertNull(LoraGovernor.block(null, txAllowed = true))
        assertEquals(LoraBlockReason.Other, LoraGovernor.block(null, txAllowed = false)!!.reason)
    }

    @Test
    fun receiveOnlyRegionHasBlockAndNoMeters() {
        val ui = LoraGovernor.map(
            status(
                """{"present":true,"transport":"e22","module_band":"850-930","region":null,"tx_allowed":false,
                   "blocked_reason":"region_unset","governor":{"rules":[],"next_tx_allowed_ms":null,"last_refusal":{"rule":"region_unset","at_ms":5}},
                   "tx":{"frames":0,"refused":4,"refused_by_rule":{"region_unset":4}}}""",
            ),
        )
        assertEquals(LoraBlockReason.RegionUnset, ui.block!!.reason)
        assertTrue(ui.meters.isEmpty())
        assertNull(ui.regionCode)
        assertEquals("region_unset", ui.lastRefusalRule)
        assertEquals(4L, ui.refusals.single { it.rule == "region_unset" }.count)
    }

    @Test
    fun absentServiceIsNotFittedNotZero() = runTest(UnconfinedTestDispatcher()) {
        val api = mockk<SensorsApi> { coEvery { lora() } returns AgentResult.Ok(LoraStatus(present = false)) }
        val lanes = StationLanes(backgroundScope, { api })
        lanes.loadLora(withBeacons = true)
        assertEquals(Load.Ready(null), lanes.state.value.lora)
        coVerify(exactly = 0) { api.loraBeacons() }
    }

    @Test
    fun regionChangeIsLockedWhileArmed() = runTest(UnconfinedTestDispatcher()) {
        val api = mockk<SensorsApi>()
        val lanes = StationLanes(backgroundScope, { api })
        lanes.setArmed(true)
        lanes.setRegion("EU")
        assertEquals(RegionWrite.Failed(RegionFailure.Armed, null), lanes.state.value.regionWrite)
        coVerify(exactly = 0) { api.putLoraRegion(any()) }
    }

    @Test
    fun regionWriteRefusalsMap() {
        assertEquals(RegionFailure.Armed, StationLanes.regionFailure(AgentError.Armed(canForce = true)).failure)
        assertEquals(
            RegionFailure.OutOfPlan,
            StationLanes.regionFailure(AgentErrorParser.parse(400, """{"error":"E_LORA_OUT_OF_PLAN","detail":"868.1 MHz is outside IN865"}""")).failure,
        )
        assertEquals(RegionFailure.NotAllowed, StationLanes.regionFailure(AgentError.Unauthorized).failure)
    }

    @Test
    fun followCountryWritesANullRegion() = runTest(UnconfinedTestDispatcher()) {
        val api = mockk<SensorsApi> { coEvery { putLoraRegion(null) } returns AgentResult.Ok(india) }
        val lanes = StationLanes(backgroundScope, { api })
        lanes.setRegion(null)
        assertEquals(RegionWrite.Done(null), lanes.state.value.regionWrite)
        assertEquals("IN", (lanes.state.value.lora as Load.Ready).value!!.regionCode)
    }
}
