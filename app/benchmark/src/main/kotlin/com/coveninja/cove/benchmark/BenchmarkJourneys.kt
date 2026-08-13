package com.coveninja.cove.benchmark

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlin.math.roundToInt

internal const val PACKAGE_NAME = "com.coveninja.cove"
private const val MAIN_ACTIVITY = "$PACKAGE_NAME.MainActivity"
private const val BENCHMARK_FIXTURE_EXTRA = "$PACKAGE_NAME.BENCHMARK_FIXTURE"
private const val BENCHMARK_LOW_PERFORMANCE_EXTRA =
    "$PACKAGE_NAME.BENCHMARK_LOW_PERFORMANCE"
private const val BENCHMARK_RECEIVER = "$PACKAGE_NAME/.BenchmarkControlReceiver"
private const val CLEAR_IMAGE_CACHE_ACTION =
    "$PACKAGE_NAME.benchmark.CLEAR_IMAGE_CACHE"
private const val IMAGE_STATUS_ACTION =
    "$PACKAGE_NAME.benchmark.IMAGE_STATUS"

internal fun MacrobenchmarkScope.startFixtureActivity(lowPerformance: Boolean = false) {
    startActivityAndWait(
        Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PACKAGE_NAME, MAIN_ACTIVITY)
            putExtra(BENCHMARK_FIXTURE_EXTRA, true)
            putExtra(BENCHMARK_LOW_PERFORMANCE_EXTRA, lowPerformance)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        },
    )
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForFixtureHomeReady()
}

/** Keeps Android 13's runtime notification prompt out of real-runtime startup measurements. */
internal fun UiDevice.grantStartupPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            PACKAGE_NAME,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

private fun UiDevice.waitForFixtureHomeReady() {
    check(wait(Until.hasObject(By.text("Details")), 10_000) == true) {
        "Fixture Home did not finish loading"
    }
}

internal fun UiDevice.scrollCurrentPage() {
    repeat(3) { scrollPageOnce() }
}

internal fun UiDevice.openExplore() {
    val gridSelector = By.desc("Grid")
    repeat(3) {
        clickStable(By.desc("Explore"))
        if (wait(Until.hasObject(gridSelector), 5_000) == true) return
    }
    error("Explore did not become ready after navigation")
}

internal fun UiDevice.openExploreAndScroll() {
    openExplore()
    scrollCurrentPage()
}

internal fun UiDevice.revealExploreRails() {
    scrollUntilVisible(By.desc("Grid"))
    // The toolbar is followed by the first rail. Moving it into the upper half of the viewport
    // gives the horizontal gesture a stable card row on phone and tablet layouts.
    scrollPageOnce()
}

internal fun UiDevice.scrollExploreRail() {
    val y = stableVisiblePosterRowY()
    repeat(3) {
        swipe(
            displayWidth * 4 / 5,
            y,
            displayWidth / 5,
            y,
            12,
        )
        waitForIdle()
    }
}

/** Resolves the card row itself instead of assuming the same vertical coordinate on every host. */
private fun UiDevice.stableVisiblePosterRowY(): Int {
    val selector = By.descContains(" poster")
    check(wait(Until.hasObject(selector), 5_000) == true) {
        "No visible poster was available for the horizontal rail gesture"
    }
    repeat(5) {
        try {
            val busiestRow = findObjects(selector)
                .map { it.visibleBounds.centerY() }
                .filter { centerY ->
                    centerY in (displayHeight / 8)..(displayHeight * 7 / 8)
                }
                .groupBy { centerY -> centerY / POSTER_ROW_BUCKET_PX }
                .maxByOrNull { (_, centers) -> centers.size }
                ?.value
            if (!busiestRow.isNullOrEmpty()) return busiestRow.average().roundToInt()
        } catch (_: StaleObjectException) {
            // Re-resolve the visible poster nodes after recomposition.
        }
    }
    error("Visible poster nodes did not remain stable long enough for a rail gesture")
}

internal fun UiDevice.openExploreGrid() {
    openExplore()
    scrollUntilVisible(By.desc("Grid"))
    clickStable(By.desc("Grid"))
    waitForIdle()
}

internal fun UiDevice.openDetailsAndReturn() {
    // The phone home layout opens on a full-height hero, so poster cards can start below the
    // viewport. Its explicit Details action is visible and stable on both phone and tablet.
    clickStable(By.text("Details"))
    waitForIdle()
    // Reduced motion can make open/close complete without a frame-timing slice. The same short
    // content scroll in both modes guarantees measurable work and covers sheet rendering too.
    swipe(
        displayWidth / 2,
        (displayHeight * 3) / 4,
        displayWidth / 2,
        displayHeight / 2,
        12,
    )
    waitForIdle()
    pressBack()
    waitForIdle()
    // At least one target-process render must follow an instant reduced-motion close or
    // FrameTimingMetric has no expected/actual slice to query on some Samsung builds.
    scrollPageOnce()
}

private fun UiDevice.scrollPageOnce() {
    swipe(
        displayWidth / 2,
        displayHeight * 3 / 4,
        displayWidth / 2,
        displayHeight / 4,
        12,
    )
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

internal fun UiDevice.clearBenchmarkImageCache() {
    val result = executeShellCommand(
        "am broadcast -a $CLEAR_IMAGE_CACHE_ACTION -n $BENCHMARK_RECEIVER",
    )
    check(result.contains("data=\"cleared\"")) { "Image cache clear did not complete: $result" }
}

internal fun UiDevice.waitForBenchmarkImages(timeoutMillis: Long = 20_000) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var lastStatus = ""
    while (SystemClock.uptimeMillis() < deadline) {
        val result = executeShellCommand(
            "am broadcast -a $IMAGE_STATUS_ACTION -n $BENCHMARK_RECEIVER",
        )
        lastStatus = Regex("data=\"(\\d+:\\d+)\"")
            .find(result)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val (active, completed) = lastStatus.split(':')
            .takeIf { it.size == 2 }
            ?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 2 }
            ?: listOf(-1, -1)
        if (active == 0 && completed > 0) return
        Thread.sleep(50)
    }
    error("Image requests did not settle in ${timeoutMillis}ms; last status=$lastStatus")
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

private fun UiDevice.scrollUntilVisible(selector: BySelector) {
    repeat(5) {
        if (hasObject(selector)) return
        scrollPageOnce()
    }
    check(hasObject(selector)) { "UI target did not appear after scrolling: $selector" }
}

private const val POSTER_ROW_BUCKET_PX = 24
