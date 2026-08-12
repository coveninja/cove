package com.coveninja.cove.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun exploreScroll() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        setupBlock = {
            pressHome()
            startFixtureActivity()
        },
    ) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).openExploreAndScroll()
    }
}
