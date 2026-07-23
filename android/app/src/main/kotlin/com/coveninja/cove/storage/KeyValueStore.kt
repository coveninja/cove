package com.coveninja.cove.storage

import android.content.SharedPreferences

/**
 * Minimal persistence seam over SharedPreferences.
 *
 * The stores that sit on top of this (ServerModeStore, UpdatePrefs, TokenStore)
 * hold real decision logic — default values, blank-config fallbacks, absent-key
 * handling — that is worth testing but was previously reachable only through an
 * Android Context. Depending on this interface instead lets those tests run as
 * plain JVM unit tests against [InMemoryKeyValueStore], with no Robolectric and
 * no emulator.
 *
 * Writes use SharedPreferences.apply() semantics: asynchronous, thread-safe, and
 * not reported back to the caller.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
    }
}

/** Production implementation backed by real SharedPreferences. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                editor.putString(key, value)
            }

            override fun putBoolean(key: String, value: Boolean) {
                editor.putBoolean(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }
        }.block()
        editor.apply()
    }
}

/** In-memory implementation used by unit tests. */
class InMemoryKeyValueStore(
    initial: Map<String, Any> = emptyMap(),
) : KeyValueStore {

    private val values = LinkedHashMap<String, Any>(initial)

    override fun getString(key: String, default: String?): String? =
        values[key] as? String ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        values[key] as? Boolean ?: default

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                values[key] = value
            }

            override fun putBoolean(key: String, value: Boolean) {
                values[key] = value
            }

            override fun remove(key: String) {
                values.remove(key)
            }
        }.block()
    }
}
