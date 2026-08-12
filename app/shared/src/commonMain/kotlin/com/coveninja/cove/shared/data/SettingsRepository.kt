package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

sealed interface SettingsState {
    data object Loading : SettingsState
    data class Ready(val settings: AppSettings) : SettingsState
    data class Failed(val message: String) : SettingsState
}

interface SettingsRepository {
    val settings: StateFlow<SettingsState>
    // PUT /api/settings is a whole-object replace — callers must supply the
    // full updated settings, not just changed fields.
    suspend fun update(settings: AppSettings)
}
