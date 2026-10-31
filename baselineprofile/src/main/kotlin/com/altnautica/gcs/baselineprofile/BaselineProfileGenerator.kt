package com.altnautica.gcs.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the startup-to-cockpit journey: launch on Home, switch on the demo
 * drone when Home offers it (no vehicles yet), then fly it and let the cockpit draw.
 *
 * Run with `./gradlew :app:generateBaselineProfile` against a connected device
 * or emulator (API 33+, or a rooted device); the plugin writes the baseline and
 * startup profiles to `app/src/main/generated/baselineProfiles/`, which are committed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun launchToCockpit() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        // An empty Home offers the demo drone; with a vehicle already listed it does not.
        device.wait(Until.findObject(By.text(TRY_DEMO)), SHORT_WAIT_MS)?.let { tryDemo ->
            tryDemo.click()
            // Switching the demo on asks once for the link notification.
            device.wait(Until.findObject(By.text(NOT_NOW)), SHORT_WAIT_MS)?.click()
        }

        device.wait(Until.findObject(By.text(FLY)), LONG_WAIT_MS)?.click()
            ?: error("Home never showed the Fly card")
        // The cockpit hides the system bars; the first time, Android explains how to bring them back.
        device.wait(Until.findObject(By.text(SYSTEM_IMMERSIVE_OK)), SHORT_WAIT_MS)?.click()
        // Let the HUD, video test pattern and Skill Bar render for a few seconds.
        device.waitForIdle()
        Thread.sleep(COCKPIT_DWELL_MS)
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.altnautica.gcs"
        const val FLY = "Fly"
        const val TRY_DEMO = "Try the demo drone"
        const val NOT_NOW = "Not now"
        const val SYSTEM_IMMERSIVE_OK = "Got it"
        const val SHORT_WAIT_MS = 3_000L
        const val LONG_WAIT_MS = 10_000L
        const val COCKPIT_DWELL_MS = 4_000L
    }
}
