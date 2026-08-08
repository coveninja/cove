package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.network.CoveApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveSettingsRepository(
    private val api: CoveApi,
    private val scope: CoroutineScope,
) : SettingsRepository {

    private val _settings = MutableStateFlow<SettingsState>(SettingsState.Loading)
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        try {
            _settings.value = SettingsState.Ready(api.settings())
        } catch (e: Exception) {
            _settings.value = SettingsState.Failed(e.message ?: "Unknown error loading settings")
        }
    }

    // PUT /api/settings is a whole-object replace — any field absent from the body
    // is persisted as its Go zero value, silently erasing unrelated settings.
    // The StateFlow always holds the full last-loaded AppSettings, so callers
    // can safely do `current.copy(oneField = newValue)` and pass the result here.
    // Passing anything less than a complete object is a caller bug.
    override suspend fun update(settings: AppSettings) {
        try {
            val saved = api.updateSettings(settings)
            _settings.value = SettingsState.Ready(saved)
        } catch (e: Exception) {
            _settings.value = SettingsState.Failed(e.message ?: "Unknown error saving settings")
        }
    }
}
