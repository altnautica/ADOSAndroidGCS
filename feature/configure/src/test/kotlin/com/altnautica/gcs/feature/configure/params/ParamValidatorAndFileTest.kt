package com.altnautica.gcs.feature.configure.params

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamValidatorAndFileTest {
    private val ranged = ParamMeta("RATE", "Rate", "", min = 1.0, max = 10.0, group = "RATE")
    private val enumOnly = ParamMeta(
        "MODE", "Mode", "", options = listOf(ParamOption(0.0, "A"), ParamOption(3.0, "B")), group = "MODE",
    )
    private val enumRanged = enumOnly.copy(min = 0.0, max = 5.0)
    private val mask = ParamMeta("OPT", "Opt", "", bits = listOf(ParamBit(0, "a"), ParamBit(2, "c")), group = "OPT")

    private fun reason(meta: ParamMeta?, input: String) =
        (ParamValidator.validate(meta, input) as? ParamValidation.Invalid)?.reason

    @Test
    fun rangeIsEnforced() {
        assertEquals(ParamValidation.Valid(5.5), ParamValidator.validate(ranged, " 5.5 "))
        assertEquals(ParamInvalidReason.BelowMin, reason(ranged, "0.5"))
        assertEquals(ParamInvalidReason.AboveMax, reason(ranged, "11"))
        assertEquals(ParamInvalidReason.NotANumber, reason(ranged, "abc"))
        assertEquals(ParamInvalidReason.NotANumber, reason(ranged, "NaN"))
    }

    @Test
    fun enumWithoutRangeOnlyAcceptsListedValues() {
        assertEquals(ParamValidation.Valid(3.0), ParamValidator.validate(enumOnly, "3"))
        assertEquals(ParamInvalidReason.NotAnOption, reason(enumOnly, "2"))
        assertEquals(ParamInvalidReason.NotInteger, reason(enumOnly, "0.5"))
        assertEquals(ParamValidation.Valid(2.0), ParamValidator.validate(enumRanged, "2"))
        assertEquals(ParamInvalidReason.AboveMax, reason(enumRanged, "6"))
    }

    @Test
    fun bitmaskRejectsUndocumentedBits() {
        assertEquals(ParamValidation.Valid(5.0), ParamValidator.validate(mask, "5"))
        assertEquals(ParamInvalidReason.UnknownBits, reason(mask, "2"))
        assertEquals(ParamInvalidReason.UnknownBits, reason(mask, "-1"))
        assertEquals(4.0, ParamValidator.toggleBit(5.0, 0), 0.0)
        assertTrue(ParamValidator.isBitSet(5.0, 2))
        assertFalse(ParamValidator.isBitSet(5.0, 1))
    }

    @Test
    fun readOnlyAndUndocumented() {
        assertEquals(ParamInvalidReason.ReadOnly, reason(ranged.copy(readOnly = true), "5"))
        assertEquals(ParamValidation.Valid(1e6), ParamValidator.validate(null, "1000000"))
    }

    @Test
    fun qgcFileRoundTrips() {
        val entries = listOf(
            ParamFileEntry("BATT_CAPACITY", 5200.0, 6),
            ParamFileEntry("ATC_RAT_RLL_P", 0.135, 9),
            ParamFileEntry("ARMING_CHECK", -1.0, 6),
        )
        val text = ParamFile.write(entries, ParamFileFormat.Qgc, sysId = 1, compId = 1)
        assertTrue(text.contains("1\t1\tATC_RAT_RLL_P\t0.135\t9"))
        val back = ParamFile.parse(text)
        assertEquals(entries.sortedBy { it.name }, back)
    }

    @Test
    fun missionPlannerFileRoundTripsAndCompares() {
        val entries = listOf(ParamFileEntry("A", 1.0), ParamFileEntry("B", 0.25))
        val text = ParamFile.write(entries, ParamFileFormat.MissionPlanner)
        assertEquals("A,1\nB,0.25\n", text)
        assertEquals(entries, ParamFile.parse("# comment\n$text\n\nGARBAGE\n"))
        val cmp = ParamFile.compare(mapOf("A" to 1.0, "B" to 0.5), entries + ParamFileEntry("C", 3.0))
        assertEquals(listOf(ParamDiff("B", 0.5, 0.25)), cmp.changed)
        assertEquals(listOf("C"), cmp.unknownNames)
        assertTrue(ParamFile.sameValue(0.1f.toDouble(), 0.1))
    }
}
