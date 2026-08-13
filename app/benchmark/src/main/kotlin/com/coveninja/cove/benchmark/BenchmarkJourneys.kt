package com.coveninja.cove.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val PACKAGE_NAME = "com.coveninja.cove"
private const val MAIN_ACTIVITY = "$PACKAGE_NAME.MainActivity"
private const val BENCHMARK_FIXTURE_EXTRA = "$PACKAGE_NAME.BENCHMARK_FIXTURE"
private const val BENCHMARK_LOW_PERFORMANCE_EXTRA =
    "$PACKAGE_NAME.BENCHMARK_LOW_PERFORMANCE"

internal fun MacrobenchmarkScope.startFixtureActivity(lowPerformance: Boolean = false) {
    startActivityAndWait(
        Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PACKAGE_NAME, MAIN_ACTIVITY)
            putExtra(BENCHMARK_FIXTURE_EXTRA, true)
            putExtra(BENCHMARK_LOW_PERFORMANCE_EXTRA, lowPerformance)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        },
    )
}

internal fun UiDevice.scrollCurrentPage() {
    repeat(3) {
        swipe(
            displayWidth / 2,
            displayHeight * 3 / 4,
            displayWidth / 2,
            displayHeight / 4,
            12,
        )
        waitForIdle()
    }
}

internal fun UiDevice.openExploreAndScroll() {
    clickStable(By.desc("Explore"))
    waitForIdle()
    scrollCurrentPage()
}

internal fun UiDevice.openDetailsAndReturn() {
    clickStable(By.descContains("poster"))
    waitForIdle()
    pressBack()
    waitForIdle()
}

internal fun UiDevice.navigatePrimaryTabs() {
    val destinations = listOf("Explore", "My List", "Profile", "Home")
    // Cache positions before navigation starts. Profile's continuously changing settings page can
    // invalidate every navigation semantics node, but the floating bar itself does not move.
    val centers = destinations.associateWith { destination ->
        stableCenter(By.desc(destination))
    }
    destinations.forEach { destination ->
        val center = centers.getValue(destination)
        check(click(center.first, center.second)) { "Unable to tap $destination" }
        waitForIdle()
    }
}

/**
 * Compose can replace a semantics node between lookup and UiObject2.click(). Resolve only its
 * bounds from the node and perform the tap through UiDevice, retrying if that lookup races a
 * recomposition. This keeps the benchmark measuring Cove instead of accessibility-node churn.
 */
private fun UiDevice.clickStable(selector: BySelector) {
    val center = stableCenter(selector)
    check(click(center.first, center.second)) { "Unable to tap $selector" }
}

private fun UiDevice.stableCenter(selector: BySelector): Pair<Int, Int> {
    check(wait(Until.hasObject(selector), 5_000) == true) { "UI target did not appear: $selector" }
    repeat(5) {
        try {
            val bounds = findObject(selector)?.visibleBounds ?: return@repeat
            return bounds.centerX() to bounds.centerY()
        } catch (_: StaleObjectException) {
            // Re-resolve the node on the next attempt.
        }
    }
    error("UI target did not remain stable long enough to locate: $selector")
}
