package com.coveninja.cove.backend.platform

import android.content.Context
import com.coveninja.cove.backend.http.MpvConfigStore
import com.coveninja.cove.shared.data.DeviceRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android's app-private mpv.conf and installed package version. */
class AndroidDeviceRepository(
    context: Context,
    override val appVersion: String,
) : DeviceRepository, MpvConfigStore {
    private val config = File(context.applicationContext.filesDir, "mpv/mpv.conf")

    override val available: Boolean = true

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

    private companion object {
        const val MAX_CONFIG_BYTES = 1 shl 20
    }
}
