package com.altnautica.gcs.feature.configure.params

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.configure.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Parameter editor touch targets and the file-import review sheet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ParamsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private val rate = ParamMeta("RATE", "Rate", "", min = 1.0, max = 10.0, group = "ATC")

    @Test
    fun valueSuggestionsAreFullTouchTargets() {
        val meta = rate.copy(options = listOf(ParamOption(2.0, "Slow"), ParamOption(8.0, "Fast")))
        compose.setContent {
            AdosTheme { NumberEditor(ParamRow("RATE", 5.0, 9, meta), meta, writing = null, onSubmit = {}) }
        }
        val rows = compose.onAllNodesWithTag("param-suggestion")
        rows.fetchSemanticsNodes().indices.forEach { rows[it].assertHeightIsAtLeast(48.dp) }
        assertEquals(2, rows.fetchSemanticsNodes().size)
    }

    private fun review(vararg rows: Pair<String, Double>, invalid: Map<String, ParamInvalidReason> = emptyMap()) = ImportReview(
        ParamComparison(rows.map { (n, v) -> ParamDiff(n, 1.0, v) }, emptyList()),
        invalid = invalid,
    )

    @Test
    fun applyAsksFirstWithACountedTitle() {
        var applied = 0
        compose.setContent {
            AdosTheme {
                // As in the app: the confirm sheet opens in the overlay host, above the review sheet.
                ProvideOverlayHost {
                    ImportReviewSheet(review("RATE" to 2.0, "ADV" to 3.0), armed = false, metaFor = { null }, onToggle = {}, onApply = { applied++ }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText(res.getQuantityString(R.plurals.cfg_import_apply, 2, 2)).performClick()
        assertEquals("nothing is written before the operator confirms", 0, applied)
        compose.onNodeWithText("Write 2 parameters to the flight controller?").assertExists()
        compose.onNodeWithText(res.getString(R.string.cfg_import_confirm)).performClick()
        assertEquals(1, applied)
    }

    @Test
    fun oneRowReadsSingular() {
        compose.setContent {
            AdosTheme {
                ImportReviewSheet(review("RATE" to 2.0), armed = false, metaFor = { null }, onToggle = {}, onApply = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText(res.getQuantityString(R.plurals.cfg_import_apply, 1, 1)).performClick()
        compose.onNodeWithText("Write 1 parameter to the flight controller?").assertExists()
    }

    @Test
    fun aRefusedRowShowsWhy() {
        compose.setContent {
            AdosTheme {
                ImportReviewSheet(
                    review("RATE" to 20.0, "ADV" to 3.0, invalid = mapOf("RATE" to ParamInvalidReason.AboveMax)),
                    armed = false,
                    metaFor = { if (it == "RATE") rate else null },
                    onToggle = {},
                    onApply = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(res.getString(R.string.cfg_param_invalid_max, "10")).assertExists()
        compose.onNodeWithText(res.getString(R.string.cfg_import_selected, 1, 2)).assertExists()
    }
}
