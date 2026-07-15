package com.coveninja.cove

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.ServerModeStore
import com.coveninja.cove.auth.TokenStore
import com.coveninja.cove.service.CoveService
import com.coveninja.cove.sync.SyncCoordinator
import com.coveninja.cove.updater.UpdatePrefs

/**
 * Application subclass. Only assembles configuration and starts CoveService.
 *
 * The Go backend lifecycle (start/stop, torrent dir setup, error logging) now
 * lives entirely in CoveService, which Android keeps alive as a foreground
 * service even when the app is backgrounded. Moving the server start here
 * directly (on a plain Thread) was the Phase 2 placeholder; Phase 3 replaces
 * that with startForegroundService() so the OS treats the backend as a
 * media-playback service and won't OOM-kill it mid-stream.
 *
 * gomobile generates a Java class `mobile.Mobile` (one class per Go package)
 * with one static method for each exported package-level function. Our Go
 * package `internal/mobile` therefore surfaces as:
 *   - mobile.Mobile.start(bindAddr, dataDir, cacheDir, torrentDir, tmdbKey, supaURL, supaKey, version)
 *   - mobile.Mobile.stop()
 *   - mobile.Mobile.isRunning()
 */
class CoveApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialise server-mode persistence and apply the saved mode to
        // CoveApiClient (BASE URL + token interceptor) before any network call.
        ServerModeStore.init(this)
        CoveApiClient.applyMode(ServerModeStore.get())

        // Initialise the auto-update preference store. Must happen before
        // WebViewActivity reads UpdatePrefs.isEnabled() in its onCreate.
        UpdatePrefs.init(this)

        // Initialise Keystore-backed token storage and pre-load any saved JWT
        // into CoveApiClient so the Authorization header is ready before the
        // first request fires (including the startup /api/auth/sync probe).
        TokenStore.init(this)
        TokenStore.get()?.let { session ->
            CoveApiClient.setJwt(session.accessToken)
        }

        // startForegroundService tells Android this service will call
        // startForeground() within 5 s — CoveService does so immediately in
        // onStartCommand, before the blocking mobile.Mobile.start() call.
        startForegroundService(Intent(this, CoveService::class.java))
        Log.i(TAG, "CoveService start requested")

        // Register ProcessLifecycleOwner observer so SyncCoordinator.syncOnResume()
        // fires every time the whole app comes to the foreground (ON_START), not just
        // on individual Activity resume events. This mirrors the desktop window-focus
        // handler in App.svelte, throttled to ≥ 60 s between syncs.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SyncCoordinator.syncOnResume()
            }
        })
    }

    companion object {
        private const val TAG = "CoveApplication"
    }
}
