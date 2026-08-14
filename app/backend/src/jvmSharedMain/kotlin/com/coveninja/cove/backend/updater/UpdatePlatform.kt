package com.coveninja.cove.backend.updater

import com.coveninja.cove.shared.data.AppUpdateRelease
import com.coveninja.cove.shared.data.UpdateApplyResult
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal data class UpdatePreferences(
    val automaticUpdatesEnabled: Boolean = true,
    val lastCheckEpochMillis: Long = 0L,
)

internal sealed interface PlatformInstallEvent {
    data object Success : PlatformInstallEvent
    data class Failure(val message: String) : PlatformInstallEvent
}

internal interface UpdatePlatform {
    val target: String
    val stagingDirectory: Path
    val installEvents: Flow<PlatformInstallEvent> get() = emptyFlow()

    fun readPreferences(): UpdatePreferences
    fun writePreferences(preferences: UpdatePreferences)
    fun isMeteredConnection(): Boolean = false
    fun canResumePermissionRequest(): Boolean = false
    fun install(payload: Path, release: AppUpdateRelease): UpdateApplyResult
}
