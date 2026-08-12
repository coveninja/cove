package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.data.SettingsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.network.CoveJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

class LocalSettingsRepository(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val scope: CoroutineScope,
    private val now: () -> String,
    private val newRemoteToken: () -> String,
) : SettingsRepository {
    private val mutation = Mutex()
    private val _settings = MutableStateFlow<SettingsState>(SettingsState.Loading)
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    init {
        var loadedProfileId = session.profileId.value
        reload(loadedProfileId)
        scope.launch {
            session.profileId.collectLatest { profileId ->
                if (profileId != loadedProfileId) {
                    loadedProfileId = profileId
                    reload(profileId)
                }
            }
        }
    }

    override suspend fun update(settings: AppSettings) = mutation.withLock {
        val profileId = session.profileId.value
        val current = read(profileId)
        val token = when {
            !settings.remoteAccessEnabled -> current.remoteAccessToken
            settings.remoteAccessToken.isNotBlank() && settings.remoteAccessToken != "***" -> settings.remoteAccessToken
            current.remoteAccessToken.isNotBlank() -> current.remoteAccessToken
            else -> newRemoteToken()
        }
        val stamped = settings.copy(
            remoteAccessToken = token,
            onboardingDone = settings.onboardingDone || current.onboardingDone,
            updatedAt = now(),
        )
        database.coveQueries.upsertSettings(
            profileId,
            CoveJson.encodeToString(stamped),
            stamped.updatedAt.orEmpty(),
        )
        _settings.value = SettingsState.Ready(stamped)
    }

    internal fun snapshotForSync(profileId: String = session.profileId.value): AppSettings =
        read(profileId).copy(
            remoteAccessEnabled = false,
            remoteAccessToken = "",
            allowLanStreamSources = false,
        )

    fun current(): AppSettings = read(session.profileId.value)

    fun masked(): AppSettings = current().let { settings ->
        if (settings.remoteAccessToken.isBlank()) settings
        else settings.copy(remoteAccessToken = "***")
    }

    internal suspend fun mergeFromRemote(incoming: AppSettings) = mutation.withLock {
        val profileId = session.profileId.value
        val local = read(profileId)
        val remoteIsNewer = incoming.updatedAt.orEmpty() > local.updatedAt.orEmpty()
        val merged = when {
            remoteIsNewer -> incoming.copy(
                remoteAccessEnabled = local.remoteAccessEnabled,
                remoteAccessToken = local.remoteAccessToken,
                allowLanStreamSources = local.allowLanStreamSources,
                onboardingDone = incoming.onboardingDone || local.onboardingDone,
            )
            incoming.onboardingDone && !local.onboardingDone -> local.copy(onboardingDone = true)
            else -> return@withLock
        }
        database.coveQueries.upsertSettings(
            profileId,
            CoveJson.encodeToString(merged),
            merged.updatedAt.orEmpty(),
        )
        _settings.value = SettingsState.Ready(merged)
    }

    private fun reload(profileId: String) {
        _settings.value = try {
            val settings = read(profileId)
            if (database.coveQueries.selectSettings(profileId).executeAsOneOrNull() == null) {
                database.coveQueries.upsertSettings(profileId, CoveJson.encodeToString(settings), "")
            }
            SettingsState.Ready(settings)
        } catch (error: Exception) {
            SettingsState.Failed(error.message ?: "Unknown error loading settings")
        }
    }

    private fun read(profileId: String): AppSettings =
        database.coveQueries.selectSettings(profileId).executeAsOneOrNull()?.let {
            CoveJson.decodeFromString<AppSettings>(it)
        } ?: AppSettings()
}
