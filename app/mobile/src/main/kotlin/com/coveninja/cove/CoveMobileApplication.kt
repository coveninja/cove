package com.coveninja.cove

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.coveninja.cove.backend.AndroidBackendRuntime
import com.coveninja.cove.player.AndroidMpvVideoPlayerHost
import com.coveninja.cove.shared.data.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CoveMobileApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var backend: AndroidBackendRuntime? = null
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

    fun backendRuntime(): AndroidBackendRuntime = backend ?: synchronized(this) {
        backend ?: AndroidBackendRuntime.open(
            context = this,
            tmdbApiKey = BuildConfig.TMDB_API_KEY,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            traktClientId = BuildConfig.TRAKT_CLIENT_ID,
            traktClientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
            appVersion = BuildConfig.VERSION_NAME,
        ).also {
            backend = it
            observeRemoteAccess(it)
        }
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
        applicationScope.cancel()
        backend?.close()
        backend = null
        super.onTerminate()
    }
}
