package com.coveninja.cove.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Keystore-backed storage for the Supabase session (JWT, refresh token, email).
 *
 * Uses EncryptedSharedPreferences (androidx.security:security-crypto:1.0.0), which
 * is AES-256-GCM encrypted and backed by the Android Keystore. On minSdk 29 (Android
 * 10) the Keystore is hardware-backed on every certified device, so this is the
 * correct choice for a JWT.
 *
 * A try/catch fallback to plain app-private SharedPreferences guards against the rare
 * Keystore initialisation failure (can happen on first boot after a factory reset before
 * the secure element is ready). In the fallback path the JWT is still in app-private
 * storage (accessible only to this app on a non-rooted device) — acceptable for a
 * session token that has a short lifespan.
 *
 * init(context) must be called once from Application.onCreate before any other method.
 */
object TokenStore {

    private const val TAG = "TokenStore"
    private const val PREFS_FILE = "cove_auth_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EMAIL = "email"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        // security-crypto:1.0.0 uses MasterKeys (plural) with the pre-1.1.0-alpha API.
        // The parameter order is (fileName, masterKeyAlias, context, keyScheme, valueScheme).
        prefs = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs: ${e.message}")
            context.getSharedPreferences(PREFS_FILE + "_plain", Context.MODE_PRIVATE)
        }
    }

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val email: String,
    )

    /** Returns the stored session, or null if none exists. */
    fun get(): Session? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: ""
        val email = prefs.getString(KEY_EMAIL, null) ?: ""
        return Session(token, refresh, email)
    }

    /** Persists a new session. */
    fun save(accessToken: String, refreshToken: String, email: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    /** Clears the stored session (called on sign-out or 401). */
    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EMAIL)
            .apply()
    }
}
