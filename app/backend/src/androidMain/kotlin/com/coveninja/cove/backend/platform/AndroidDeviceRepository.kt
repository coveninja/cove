package com.coveninja.cove.backend.platform

import android.app.ActivityManager
import android.content.Context
import com.coveninja.cove.backend.http.MpvConfigStore
import com.coveninja.cove.shared.data.DevicePerformanceState
import com.coveninja.cove.shared.data.DeviceRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Android's app-private mpv.conf and installed package version. */
class AndroidDeviceRepository(
    context: Context,
    override val appVersion: String,
) : DeviceRepository, MpvConfigStore {
    private val applicationContext = context.applicationContext
    private val config = File(applicationContext.filesDir, "mpv/mpv.conf")
    private val preferences = applicationContext.getSharedPreferences(
        DEVICE_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val performanceMutation = Mutex()
    private val lowRamRecommendation = applicationContext
        .getSystemService(ActivityManager::class.java)
        ?.isLowRamDevice == true
    private val _performance = MutableStateFlow(readPerformance())

    override val available: Boolean = true
    override val performance = _performance.asStateFlow()

    override suspend fun setLowPerformanceMode(enabled: Boolean) {
        updatePerformance { copy(lowPerformanceMode = enabled) }
    }

    override suspend fun dismissLowPerformanceRecommendation() {
        updatePerformance { copy(recommendationDismissed = true) }
    }

    override suspend fun readMpvConfig(): String = withContext(Dispatchers.IO) {
        if (config.isFile) config.readText() else ""
    }

    override suspend fun writeMpvConfig(content: String) = withContext(Dispatchers.IO) {
        require(content.encodeToByteArray().size <= MAX_CONFIG_BYTES) {
            "mpv.conf exceeds 1 MiB"
        }
        check(config.parentFile?.mkdirs() != false || config.parentFile?.isDirectory == true) {
            "Unable to create the mpv configuration directory"
        }
        val temporary = File(config.parentFile, "${config.name}.tmp-${UUID.randomUUID()}")
        temporary.writeText(content)
        check(temporary.renameTo(config) || runCatching {
            temporary.copyTo(config, overwrite = true)
            temporary.delete()
        }.isSuccess) { "Unable to save mpv.conf" }
    }

    private fun readPerformance() = DevicePerformanceState(
        lowPerformanceMode = preferences.getBoolean(LOW_PERFORMANCE_MODE, false),
        lowPerformanceRecommended = lowRamRecommendation,
        recommendationDismissed = preferences.getBoolean(
            LOW_PERFORMANCE_RECOMMENDATION_DISMISSED,
            false,
        ),
    )

    private suspend fun updatePerformance(
        transform: DevicePerformanceState.() -> DevicePerformanceState,
    ) = performanceMutation.withLock {
        val next = _performance.value.transform().copy(
            // This value comes from Android, never from persisted user input.
            lowPerformanceRecommended = lowRamRecommendation,
        )
        val saved = withContext(Dispatchers.IO) {
            preferences.edit()
                .putBoolean(LOW_PERFORMANCE_MODE, next.lowPerformanceMode)
                .putBoolean(
                    LOW_PERFORMANCE_RECOMMENDATION_DISMISSED,
                    next.recommendationDismissed,
                )
                .commit()
        }
        check(saved) { "Unable to save device performance settings" }
        _performance.value = next
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 1 shl 20
        const val DEVICE_PREFERENCES = "cove-device-settings"
        const val LOW_PERFORMANCE_MODE = "low-performance-mode"
        const val LOW_PERFORMANCE_RECOMMENDATION_DISMISSED =
            "low-performance-recommendation-dismissed"
    }
}
