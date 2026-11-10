package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin refusal text matches every shared refusal case. */
class RefusalCasesTest {
    private val text = SpecText(testSpec.setupStrings)

    @Test
    fun `every refusal case reads as the shared text`() {
        val cases = goldenCases("refusal-cases.json")
        for (c in cases) {
            assertEquals(c.name, c.expected["text"].str(), text.refusalText(c.input["code"].str()))
        }
    }
}
