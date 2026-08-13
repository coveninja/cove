package com.coveninja.cove.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold-cache network, decode, and total request timing on the physical performance device. */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MobileImageBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldExploreImages() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(
            TraceSectionMetric(
                sectionName = "Cove image request",
                mode = TraceSectionMetric.Mode.First,
                label = "firstImageRequest",
            ),
            TraceSectionMetric(
                sectionName = "Cove image fetch",
                mode = TraceSectionMetric.Mode.Sum,
                label = "imageFetch",
            ),
            TraceSectionMetric(
                sectionName = "Cove image decode",
                mode = TraceSectionMetric.Mode.Sum,
                label = "imageDecode",
            ),
        ),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            pressHome()
            startFixtureActivity()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply {
                waitForIdle()
                clearBenchmarkImageCache()
            }
        },
    ) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply {
            openExplore()
            waitForBenchmarkImages()
        }
    }
}
