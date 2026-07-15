package com.coveninja.cove.updater

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's auto-update preference using SharedPreferences.
 * It must be initialised exactly once — call [init] from Application.onCreate
 * before any call to [isEnabled].
 *
 * Uses the same init(context) pattern as ServerModeStore. SharedPreferences
 * reads and apply()-based writes are thread-safe by the platform contract.
 */
object UpdatePrefs {

    private const val PREFS_NAME = "cove_update_prefs"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"

    private lateinit var prefs: SharedPreferences

    /** Must be called in Application.onCreate before any other method. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns true when automatic updates are enabled (default: true). */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true)

    /**
     * Persists the auto-update preference. Safe to call from any thread
     * (apply() is async and thread-safe).
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }
}
