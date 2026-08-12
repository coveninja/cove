package com.coveninja.cove.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun criticalUserJourney() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startFixtureActivity()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).openExploreAndScroll()
    }
}
