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
    fun exploreScroll() = measureExplore(lowPerformance = false)

    @Test
    fun exploreScrollLowPerformance() = measureExplore(lowPerformance = true)

    @Test
    fun homeScroll() = measureJourney(lowPerformance = false) { scrollCurrentPage() }

    @Test
    fun homeScrollLowPerformance() = measureJourney(lowPerformance = true) { scrollCurrentPage() }

    @Test
    fun detailsOpenClose() = measureJourney(lowPerformance = false) { openDetailsAndReturn() }

    @Test
    fun detailsOpenCloseLowPerformance() =
        measureJourney(lowPerformance = true) { openDetailsAndReturn() }

    @Test
    fun primaryNavigation() = measureJourney(lowPerformance = false) { navigatePrimaryTabs() }

    @Test
    fun primaryNavigationLowPerformance() =
        measureJourney(lowPerformance = true) { navigatePrimaryTabs() }

    private fun measureExplore(lowPerformance: Boolean) = measureJourney(lowPerformance) {
        openExploreAndScroll()
    }

    private fun measureJourney(
        lowPerformance: Boolean,
        journey: UiDevice.() -> Unit,
    ) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        setupBlock = {
            pressHome()
            startFixtureActivity(lowPerformance)
        },
    ) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).journey()
    }
}
