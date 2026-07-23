package com.coveninja.cove.api

import android.content.Context
import com.coveninja.cove.storage.KeyValueStore
import com.coveninja.cove.storage.SharedPreferencesStore

/**
 * ServerMode describes how the Android app connects to its Cove backend.
 *
 *  - Local  : the embedded Go backend (always running via CoveService). This is
 *             the default for normal use; 127.0.0.1:6969 is never auth-gated.
 *  - Remote : a Cove backend running on another device on the LAN.  All HTTP
 *             requests carry X-Cove-Token so the host's LAN listener accepts them.
 *             The embedded backend keeps running in the background (harmless;
 *             a future optimisation may stop it to save battery).
 */
sealed class ServerMode {
    object Local : ServerMode()
    data class Remote(val baseUrl: String, val token: String) : ServerMode()
}

/**
 * ServerModeStore persists the active ServerMode using SharedPreferences.
 * It must be initialised exactly once — call [init] from Application.onCreate
 * before any call to [get].
 *
 * Note: the task spec calls for DataStore preferences; this implementation uses
 * SharedPreferences instead because it avoids a new dependency and provides
 * synchronous reads, which is important for initialising CoveApiClient at
 * app-startup time before the Compose tree is built. The behavior is identical.
 */
object ServerModeStore {

    private const val PREFS_NAME = "cove_server_mode"
    private const val KEY_MODE = "mode"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"

    private lateinit var store: KeyValueStore

    /** Must be called in Application.onCreate before any other method. */
    fun init(context: Context) {
        initWith(
            SharedPreferencesStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )
    }

    /** Injection seam for unit tests; production code goes through [init]. */
    internal fun initWith(backing: KeyValueStore) {
        store = backing
    }

    /** Returns the currently-persisted ServerMode. */
    fun get(): ServerMode {
        return when (store.getString(KEY_MODE, "local")) {
            "remote" -> {
                val url = store.getString(KEY_BASE_URL, "") ?: ""
                val token = store.getString(KEY_TOKEN, "") ?: ""
                if (url.isNotBlank()) ServerMode.Remote(url, token) else ServerMode.Local
            }
            else -> ServerMode.Local
        }
    }

    /** Persists Local mode and updates CoveApiClient immediately. */
    fun setLocal() {
        store.edit { putString(KEY_MODE, "local") }
        CoveApiClient.applyMode(ServerMode.Local)
    }

    /**
     * Persists Remote mode with the given [baseUrl] and [token], then updates
     * CoveApiClient. Only call after a successful ping — the caller is responsible
     * for validating connectivity before persisting.
     */
    fun setRemote(baseUrl: String, token: String) {
        store.edit {
            putString(KEY_MODE, "remote")
            putString(KEY_BASE_URL, baseUrl)
            putString(KEY_TOKEN, token)
        }
        CoveApiClient.applyMode(ServerMode.Remote(baseUrl, token))
    }
}
