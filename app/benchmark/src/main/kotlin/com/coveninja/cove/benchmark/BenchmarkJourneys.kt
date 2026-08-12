package com.coveninja.cove.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val PACKAGE_NAME = "com.coveninja.cove"
private const val MAIN_ACTIVITY = "$PACKAGE_NAME.MainActivity"
private const val BENCHMARK_FIXTURE_EXTRA = "$PACKAGE_NAME.BENCHMARK_FIXTURE"

internal fun MacrobenchmarkScope.startFixtureActivity() {
    startActivityAndWait(
        Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PACKAGE_NAME, MAIN_ACTIVITY)
            putExtra(BENCHMARK_FIXTURE_EXTRA, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        },
    )
}

internal fun UiDevice.openExploreAndScroll() {
    wait(Until.hasObject(By.desc("Explore")), 5_000)
    findObject(By.desc("Explore"))?.click()
    waitForIdle()
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
