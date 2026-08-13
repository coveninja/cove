package com.coveninja.cove.backend.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DeviceSettingsServiceTest {
    @Test
    fun `performance preferences survive reopening the repository`() = runTest {
        val directory = Files.createTempDirectory("cove-device-settings-test")
        val repository = LocalDeviceRepository(DeviceSettingsService(directory), "test")

        assertFalse(repository.performance.value.lowPerformanceMode)
        assertFalse(repository.performance.value.lowPerformanceRecommended)
        assertFalse(repository.performance.value.recommendationDismissed)

        repository.setLowPerformanceMode(true)
        repository.dismissLowPerformanceRecommendation()

        val reopened = LocalDeviceRepository(DeviceSettingsService(directory), "test")
        assertTrue(reopened.performance.value.lowPerformanceMode)
        assertTrue(reopened.performance.value.recommendationDismissed)
        assertFalse(reopened.performance.value.lowPerformanceRecommended)
    }
}
