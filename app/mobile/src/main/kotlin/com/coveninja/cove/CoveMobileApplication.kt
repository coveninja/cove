package com.coveninja.cove

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Trace
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
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
import okio.Path.Companion.toOkioPath

sealed interface MobileRuntimeState {
    data object Loading : MobileRuntimeState
    data class Ready(val runtime: AndroidBackendRuntime) : MobileRuntimeState
    data class Failed(val message: String) : MobileRuntimeState
}

class CoveMobileApplication : Application(), SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val systemLocale = MutableStateFlow("")
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

    override fun onCreate() {
        super.onCreate()
        systemLocale.value = resources.configuration.primaryLanguageTag()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        systemLocale.value = newConfig.primaryLanguageTag()
    }

    /**
     * One explicitly sized loader for every poster, backdrop, and prefetch request in the app.
     * The default is also singleton-backed, but owning the policy here lets Cove bound memory on
     * low-RAM devices, retain a useful disk working set, and de-duplicate a visible request that
     * races a look-ahead prefetch for the same URL.
     */
    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        return ImageLoader.Builder(context)
            .components {
                add(
                    KtorNetworkFetcherFactory(
                        concurrentRequestStrategy = {
                            DeDupeConcurrentRequestStrategy()
                        },
                    ),
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, if (lowRam) 0.15 else 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIRECTORY).toOkioPath())
                    .maxSizeBytes(
                        if (lowRam) LOW_RAM_IMAGE_CACHE_BYTES else IMAGE_CACHE_BYTES,
                    )
                    .build()
            }
            .eventListenerFactory { CoveImageEventListener() }
            .build()
    }

    @Synchronized
    fun initializeBackend() {
        if (backend != null || initializationJob?.isActive == true) return
        _runtimeState.value = MobileRuntimeState.Loading
        initializationJob = applicationScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    traced("Cove startup backend") {
                        AndroidBackendRuntime.open(
                            context = this@CoveMobileApplication,
                            tmdbApiKey = BuildConfig.TMDB_API_KEY,
                            supabaseUrl = BuildConfig.SUPABASE_URL,
                            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                            traktClientId = BuildConfig.TRAKT_CLIENT_ID,
                            traktClientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                            appVersion = BuildConfig.VERSION_NAME,
                            updatePublicKeys = BuildConfig.UPDATE_PUBLIC_KEYS,
                            updateApiBase = BuildConfig.UPDATE_API_BASE,
                            systemLocale = systemLocale,
                        )
                    }
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

    private companion object {
        const val IMAGE_CACHE_DIRECTORY = "cove_image_cache"
        const val LOW_RAM_IMAGE_CACHE_BYTES = 128L * 1024L * 1024L
        const val IMAGE_CACHE_BYTES = 256L * 1024L * 1024L
    }
}

private inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private fun Configuration.primaryLanguageTag(): String =
    locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
