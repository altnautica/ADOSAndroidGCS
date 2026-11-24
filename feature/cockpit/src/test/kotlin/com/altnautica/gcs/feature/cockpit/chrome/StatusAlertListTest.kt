package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.ParamDiff
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.hud.AlertGroup
import com.altnautica.gcs.core.skills.hud.AlertGroups
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every item in one Other section, for list tests that do not exercise the grouping. */
internal fun otherSection(items: List<StatusItem>): StatusListUi =
    StatusListUi(listOf(StatusSection(AlertGroup.Other, "Other", items.map { StatusRow(it, it.severity, collapsed = false) }, 0)))

/** The grouped alert list: sections, parameter rows, fix actions, Hide and the blocking sheet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StatusAlertListTest {
    @get:Rule
    val compose = createComposeRule()

    private val groups = AlertGroups(testSpec.alertGroups)
    private val pill = specStatusPill()
    private val fcProfile = ReadinessItem(
        "fc_profile", false, ReadinessSeverity.Block, "FC parameters differ",
        (1..8).map { ParamDiff("PARAM_$it", it.toDouble(), "0") } + ParamDiff("MAV_GCS_SYSID", null, "1"),
        ReadinessFix.ApplyProfile,
    )
    private val sensors = ReadinessItem(
        "sensor_params", false, ReadinessSeverity.Warn, "Flow parameters differ",
        listOf(ParamDiff("FLOW_TYPE", 6.0, "5 or 7")), ReadinessFix.Parameters(listOf("FLOW_TYPE")),
    )

    private fun items(): List<StatusItem> = StatusModel.items(
        emptyList(),
        com.altnautica.gcs.feature.cockpit.preflight.PreflightResult(emptyList(), readiness = listOf(fcProfile, sensors)),
        listOf(com.altnautica.gcs.feature.cockpit.alerts.ReceivedText("EKF variance", 0L, severity = 4, relayed = true)),
        com.altnautica.gcs.feature.cockpit.pilot.PillFacts(),
        pill,
    )

    @Test
    fun `the list groups by section, snoozed warnings move to hidden and snoozed criticals stay`() {
        val items = items()
        val list = StatusModel.list(items, groups, emptySet())
        assertEquals(listOf(AlertGroup.Setup, AlertGroup.FlightController), list.sections.map { it.group })
        val ekf = items.first { it.text == "EKF variance" }
        val crit = items.first { it.key == "pilot:fc_profile" }
        val snoozed = setOf(ekf.snoozeKey, crit.snoozeKey)
        val hidden = StatusModel.list(items, groups, snoozed)
        assertEquals(listOf(ekf), hidden.hidden)
        assertEquals(true, hidden.sections[0].rows.first { it.item == crit }.collapsed)
        assertEquals(1, hidden.sections[1].hidden)
        // The pill counts the snoozed critical and drops the snoozed warning.
        assertEquals(2, StatusModel.unsnoozed(items, snoozed).size)
    }

    @Test
    fun `a row shows its parameter rows, its fix and Hide, each a 48 dp target`() {
        var applyFor: String? = null
        var snoozed: Pair<String, Boolean>? = null
        val list = StatusModel.list(items(), groups, emptySet())
        show(list, StatusListActions(onApplyRead = { applyFor = it }, onSnooze = { k, on -> snoozed = k to on }))
        // Six rows show, the rest behind Show all.
        compose.onNodeWithText("PARAM_6", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("PARAM_7", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Show all 9").performScrollTo().performClick()
        compose.onNodeWithText("MAV_GCS_SYSID", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("not reported", useUnmergedTree = true).assertExists()
        // Apply recommended values starts the inline review for this row, keyed by its snooze key.
        compose.onNodeWithTag("status-fix-pilot:fc_profile").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("pilot:fc_profile|FC parameters differ", applyFor)
        compose.onNodeWithText("Collapse").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("pilot:fc_profile|FC parameters differ" to true, snoozed)
    }

    @Test
    fun `the apply review lists the changes, the folded rows, the restart, a hold and Cancel`() {
        val list = StatusModel.list(items(), groups, emptySet())
        val review = com.altnautica.gcs.feature.cockpit.alerts.ProfileReview(
            actionId = "apply",
            changes = listOf(com.altnautica.gcs.feature.cockpit.alerts.ProfileRow("PRX1_TYPE", "2", 0.0)),
            same = listOf(com.altnautica.gcs.feature.cockpit.alerts.ProfileRow("FS_GCS_ENABLE", "1", 1.0)),
            reboots = true,
        )
        var cancelled = false
        show(
            list,
            StatusListActions(onApplyCancel = { cancelled = true }),
            apply = com.altnautica.gcs.feature.cockpit.alerts.ApplyState(
                "pilot:fc_profile|FC parameters differ",
                com.altnautica.gcs.feature.cockpit.alerts.ApplyPhase.Review(review),
            ),
        )
        compose.onNodeWithText("1 value changes", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("PRX1_TYPE", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("FS_GCS_ENABLE", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("alert-apply-same-toggle").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("FS_GCS_ENABLE", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("alert-apply-reboots", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("alert-apply-hold", useUnmergedTree = true).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("alert-apply-cancel").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(cancelled)
        // The wanted table gives way to the review, and the button to the hold.
        compose.onNodeWithTag("status-fix-pilot:fc_profile").assertDoesNotExist()
    }

    @Test
    fun `the blocking sheet lists only what blocks take-off and links to every warning`() {
        var all = false
        show(StatusModel.list(items(), groups, emptySet()), StatusListActions(onShowAll = { all = true }), blockingOnly = true)
        compose.onNodeWithText("What blocks take-off").assertExists()
        compose.onNodeWithText("FC parameters differ", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Flow parameters differ", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Warnings (2)").performScrollTo().performClick()
        assertEquals(true, all)
    }

    private fun show(
        list: StatusListUi,
        actions: StatusListActions,
        blockingOnly: Boolean = false,
        apply: com.altnautica.gcs.feature.cockpit.alerts.ApplyState = com.altnautica.gcs.feature.cockpit.alerts.ApplyState(),
    ) {
        val chrome = Chrome.from(testSpec.tokens.chrome)
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings, LocalChrome provides chrome) {
                    StatusList(list, onClose = {}, blockingOnly = blockingOnly, actions = actions, apply = apply)
                }
            }
        }
    }
}
