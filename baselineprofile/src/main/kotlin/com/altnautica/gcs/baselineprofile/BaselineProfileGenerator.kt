package com.altnautica.gcs.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the startup-to-cockpit journey: launch, Fleet, switch on the demo
 * drone when it is not already listed, then fly it and let the cockpit draw.
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

        if (!device.wait(Until.hasObject(By.text(FLY)), SHORT_WAIT_MS)) {
            device.findObject(By.text(CONNECT))?.click()
            device.wait(Until.findObject(By.text(DEMO)), LONG_WAIT_MS)?.click()
            // Switching the demo on asks once for the link notification.
            device.wait(Until.findObject(By.text(NOT_NOW)), SHORT_WAIT_MS)?.click()
            device.wait(Until.hasObject(By.text(FLY)), SHORT_WAIT_MS) || device.pressBack()
        }

        device.wait(Until.findObject(By.text(FLY)), LONG_WAIT_MS)?.click()
            ?: error("The demo drone never appeared in the fleet")
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
        const val CONNECT = "Connect"
        const val DEMO = "Demo"
        const val NOT_NOW = "Not now"
        const val SYSTEM_IMMERSIVE_OK = "Got it"
        const val SHORT_WAIT_MS = 3_000L
        const val LONG_WAIT_MS = 10_000L
        const val COCKPIT_DWELL_MS = 4_000L
    }
}
