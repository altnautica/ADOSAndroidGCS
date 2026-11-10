package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitDividerTest {
    private val divider = splitDivider(testSpec.views)

    @Test
    fun `the split divider reads its range and default from the spec`() {
        assertEquals(0.5f, divider.default)
        assertEquals(0.3f, divider.min)
        assertEquals(0.7f, divider.max)
    }

    @Test
    fun `a drag or a saved ratio outside the range is held inside it`() {
        assertEquals(0.3f, divider.clamp(0.2f))
        assertEquals(0.7f, divider.clamp(0.8f))
        assertEquals(0.55f, divider.clamp(0.55f))
        assertEquals(0.5f, divider.clamp(Float.NaN))
    }
}
