package com.coveninja.cove

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.coveninja.cove.backend.AndroidBackendRuntime
import com.coveninja.cove.player.AndroidMpvVideoPlayerHost
import com.coveninja.cove.shared.data.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MobileRuntimeState {
    data object Loading : MobileRuntimeState
    data class Ready(val runtime: AndroidBackendRuntime) : MobileRuntimeState
    data class Failed(val message: String) : MobileRuntimeState
}

class CoveMobileApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var backend: AndroidBackendRuntime? = null
    private val _runtimeState = MutableStateFlow<MobileRuntimeState>(MobileRuntimeState.Loading)
    val runtimeState: StateFlow<MobileRuntimeState> = _runtimeState.asStateFlow()
    private var initializationJob: Job? = null
    @Volatile
    private var observingRemoteAccess = false
    private val playerDelegate = lazy {
        AndroidMpvVideoPlayerHost(this) { active ->
            val intent = Intent(this, PlaybackService::class.java)
            if (active) ContextCompat.startForegroundService(this, intent)
            else stopService(intent)
        }
    }
    private val player by playerDelegate

    @Synchronized
    fun initializeBackend() {
        if (backend != null || initializationJob?.isActive == true) return
        _runtimeState.value = MobileRuntimeState.Loading
        initializationJob = applicationScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AndroidBackendRuntime.open(
                        context = this@CoveMobileApplication,
                        tmdbApiKey = BuildConfig.TMDB_API_KEY,
                        supabaseUrl = BuildConfig.SUPABASE_URL,
                        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                        traktClientId = BuildConfig.TRAKT_CLIENT_ID,
                        traktClientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                }
            }.onSuccess { runtime ->
                backend = runtime
                observeRemoteAccess(runtime)
                _runtimeState.value = MobileRuntimeState.Ready(runtime)
            }.onFailure { error ->
                _runtimeState.value = MobileRuntimeState.Failed(
                    error.message ?: "Cove could not initialize its local data.",
                )
            }
        }
    }

    suspend fun awaitBackendRuntime(): AndroidBackendRuntime {
        initializeBackend()
        return runtimeState.filterIsInstance<MobileRuntimeState.Ready>().first().runtime
    }

    fun playerHost(): AndroidMpvVideoPlayerHost = player

    private fun observeRemoteAccess(runtime: AndroidBackendRuntime) {
        if (observingRemoteAccess) return
        observingRemoteAccess = true
        applicationScope.launch {
            runtime.graph.settings.settings.collectLatest { state ->
                val intent = Intent(this@CoveMobileApplication, RemoteAccessService::class.java)
                if ((state as? SettingsState.Ready)?.settings?.remoteAccessEnabled == true) {
                    ContextCompat.startForegroundService(this@CoveMobileApplication, intent)
                } else {
                    stopService(intent)
                }
            }
        }
    }

    override fun onTerminate() {
        if (playerDelegate.isInitialized()) player.dispose()
        initializationJob?.cancel()
        initializationJob = null
        applicationScope.cancel()
        backend?.close()
        backend = null
        super.onTerminate()
    }
}
