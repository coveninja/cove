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
    fun exploreShelvesVerticalScroll() = measurePreparedJourney(
        lowPerformance = false,
        prepare = { openExplore() },
        journey = { scrollCurrentPage() },
    )

    @Test
    fun exploreShelvesVerticalScrollLowPerformance() = measurePreparedJourney(
        lowPerformance = true,
        prepare = { openExplore() },
        journey = { scrollCurrentPage() },
    )

    @Test
    fun exploreRailHorizontalScroll() = measurePreparedJourney(
        lowPerformance = false,
        prepare = {
            openExplore()
            revealExploreRails()
        },
        journey = { scrollExploreRail() },
    )

    @Test
    fun exploreRailHorizontalScrollLowPerformance() = measurePreparedJourney(
        lowPerformance = true,
        prepare = {
            openExplore()
            revealExploreRails()
        },
        journey = { scrollExploreRail() },
    )

    @Test
    fun exploreGridScroll() = measurePreparedJourney(
        lowPerformance = false,
        prepare = { openExploreGrid() },
        journey = { scrollCurrentPage() },
    )

    @Test
    fun exploreGridScrollLowPerformance() = measurePreparedJourney(
        lowPerformance = true,
        prepare = { openExploreGrid() },
        journey = { scrollCurrentPage() },
    )

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

    private fun measurePreparedJourney(
        lowPerformance: Boolean,
        prepare: UiDevice.() -> Unit,
        journey: UiDevice.() -> Unit,
    ) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        setupBlock = {
            pressHome()
            startFixtureActivity(lowPerformance)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).prepare()
        },
    ) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).journey()
    }
}
