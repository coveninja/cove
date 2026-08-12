package com.coveninja.cove.backend.platform

import com.coveninja.cove.shared.data.DeviceRepository
import kotlinx.coroutines.Dispatchers
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

    override suspend fun readMpvConfig(): String = withContext(Dispatchers.IO) {
        service.readMpvConfig()
    }

    override suspend fun writeMpvConfig(content: String) = withContext(Dispatchers.IO) {
        service.writeMpvConfig(content)
    }
}
