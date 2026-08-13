package com.coveninja.cove.backend.platform

import com.coveninja.cove.shared.data.DeviceRepository
import com.coveninja.cove.shared.data.DevicePerformanceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop device settings for the UI, in front of [DeviceSettingsService].
 *
 * mpv.conf is a real file read and written on disk, hence the IO dispatcher —
 * the settings page calls this straight from a composition scope.
 */
class LocalDeviceRepository(
    private val service: DeviceSettingsService,
    override val appVersion: String,
) : DeviceRepository {
    override val available: Boolean = true
    private val performanceMutation = Mutex()
    private val _performance = MutableStateFlow(service.readPerformanceState())
    override val performance = _performance.asStateFlow()

    override suspend fun setLowPerformanceMode(enabled: Boolean) {
        updatePerformance { copy(lowPerformanceMode = enabled) }
    }

    override suspend fun dismissLowPerformanceRecommendation() {
        updatePerformance { copy(recommendationDismissed = true) }
    }

    override suspend fun readMpvConfig(): String = withContext(Dispatchers.IO) {
        service.readMpvConfig()
    }

    override suspend fun writeMpvConfig(content: String) = withContext(Dispatchers.IO) {
        service.writeMpvConfig(content)
    }

    private suspend fun updatePerformance(
        transform: DevicePerformanceState.() -> DevicePerformanceState,
    ) = performanceMutation.withLock {
        val next = _performance.value.transform().copy(lowPerformanceRecommended = false)
        withContext(Dispatchers.IO) { service.writePerformanceState(next) }
        _performance.value = next
    }
}
