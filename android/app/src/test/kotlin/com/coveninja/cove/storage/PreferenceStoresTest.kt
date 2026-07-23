package com.coveninja.cove.storage

import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.ServerMode
import com.coveninja.cove.api.ServerModeStore
import com.coveninja.cove.auth.TokenStore
import com.coveninja.cove.updater.UpdatePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decision logic in the three preference-backed stores. Each one is
 * an object with an internal initWith() seam, so these run as plain JVM tests
 * against InMemoryKeyValueStore — no Robolectric, no emulator.
 */
class PreferenceStoresTest {

    @After
    fun resetApiClient() {
        // ServerModeStore.setLocal/setRemote reach into the shared API client.
        CoveApiClient.applyMode(ServerMode.Local)
    }

    // ── ServerModeStore ──────────────────────────────────────────────────────

    @Test
    fun serverModeDefaultsToLocalWhenNothingStored() {
        ServerModeStore.initWith(InMemoryKeyValueStore())

        assertEquals(ServerMode.Local, ServerModeStore.get())
    }

    @Test
    fun serverModeReadsStoredRemoteConfiguration() {
        ServerModeStore.initWith(
            InMemoryKeyValueStore(
                mapOf(
                    "mode" to "remote",
                    "base_url" to "http://192.168.1.5:6969/api",
                    "token" to "tok",
                ),
            ),
        )

        assertEquals(
            ServerMode.Remote("http://192.168.1.5:6969/api", "tok"),
            ServerModeStore.get(),
        )
    }

    @Test
    fun serverModeFallsBackToLocalWhenRemoteUrlIsBlank() {
        // A half-written remote config must not leave the app pointed at nothing.
        ServerModeStore.initWith(
            InMemoryKeyValueStore(mapOf("mode" to "remote", "base_url" to "   ", "token" to "tok")),
        )

        assertEquals(ServerMode.Local, ServerModeStore.get())
    }

    @Test
    fun serverModeFallsBackToLocalWhenRemoteUrlKeyMissing() {
        ServerModeStore.initWith(InMemoryKeyValueStore(mapOf("mode" to "remote")))

        assertEquals(ServerMode.Local, ServerModeStore.get())
    }

    @Test
    fun serverModeTreatsUnknownModeStringAsLocal() {
        ServerModeStore.initWith(InMemoryKeyValueStore(mapOf("mode" to "banana")))

        assertEquals(ServerMode.Local, ServerModeStore.get())
    }

    @Test
    fun serverModeRemoteWithMissingTokenUsesEmptyString() {
        ServerModeStore.initWith(
            InMemoryKeyValueStore(mapOf("mode" to "remote", "base_url" to "http://h:1/api")),
        )

        assertEquals(ServerMode.Remote("http://h:1/api", ""), ServerModeStore.get())
    }

    @Test
    fun setRemoteThenGetRoundTrips() {
        ServerModeStore.initWith(InMemoryKeyValueStore())

        ServerModeStore.setRemote("http://10.0.0.2:6969/api", "abc")

        assertEquals(ServerMode.Remote("http://10.0.0.2:6969/api", "abc"), ServerModeStore.get())
        assertEquals("http://10.0.0.2:6969/api", CoveApiClient.BASE)
    }

    @Test
    fun setLocalAfterRemoteRevertsToLocal() {
        ServerModeStore.initWith(InMemoryKeyValueStore())
        ServerModeStore.setRemote("http://10.0.0.2:6969/api", "abc")

        ServerModeStore.setLocal()

        assertEquals(ServerMode.Local, ServerModeStore.get())
        assertEquals(com.coveninja.cove.BuildConfig.BACKEND_URL, CoveApiClient.BASE)
    }

    // ── UpdatePrefs ──────────────────────────────────────────────────────────

    @Test
    fun autoUpdateDefaultsToEnabled() {
        UpdatePrefs.initWith(InMemoryKeyValueStore())

        assertTrue(UpdatePrefs.isEnabled())
    }

    @Test
    fun autoUpdateRespectsStoredOptOut() {
        UpdatePrefs.initWith(InMemoryKeyValueStore(mapOf("auto_update_enabled" to false)))

        assertFalse(UpdatePrefs.isEnabled())
    }

    @Test
    fun autoUpdateSettingRoundTrips() {
        UpdatePrefs.initWith(InMemoryKeyValueStore())

        UpdatePrefs.setEnabled(false)
        assertFalse(UpdatePrefs.isEnabled())

        UpdatePrefs.setEnabled(true)
        assertTrue(UpdatePrefs.isEnabled())
    }

    // ── TokenStore ───────────────────────────────────────────────────────────

    @Test
    fun sessionIsNullWhenNoAccessTokenStored() {
        TokenStore.initWith(InMemoryKeyValueStore())

        assertNull(TokenStore.get())
    }

    @Test
    fun sessionIsNullWhenOnlyRefreshTokenStored() {
        // The access token is the presence signal; a stray refresh token alone
        // must not be reported as a live session.
        TokenStore.initWith(InMemoryKeyValueStore(mapOf("refresh_token" to "r")))

        assertNull(TokenStore.get())
    }

    @Test
    fun sessionRoundTripsThroughSave() {
        TokenStore.initWith(InMemoryKeyValueStore())

        TokenStore.save("jwt", "refresh", "user@example.test")

        val session = TokenStore.get()
        assertEquals("jwt", session?.accessToken)
        assertEquals("refresh", session?.refreshToken)
        assertEquals("user@example.test", session?.email)
    }

    @Test
    fun sessionDefaultsMissingRefreshAndEmailToEmptyString() {
        TokenStore.initWith(InMemoryKeyValueStore(mapOf("access_token" to "jwt")))

        val session = TokenStore.get()
        assertEquals("jwt", session?.accessToken)
        assertEquals("", session?.refreshToken)
        assertEquals("", session?.email)
    }

    @Test
    fun clearRemovesEntireSession() {
        TokenStore.initWith(InMemoryKeyValueStore())
        TokenStore.save("jwt", "refresh", "user@example.test")

        TokenStore.clear()

        assertNull(TokenStore.get())
    }

    @Test
    fun saveOverwritesPreviousSession() {
        TokenStore.initWith(InMemoryKeyValueStore())
        TokenStore.save("old", "oldr", "old@example.test")

        TokenStore.save("new", "newr", "new@example.test")

        assertEquals("new", TokenStore.get()?.accessToken)
        assertEquals("new@example.test", TokenStore.get()?.email)
    }

    // ── InMemoryKeyValueStore itself ─────────────────────────────────────────

    @Test
    fun inMemoryStoreHonoursDefaultsAndRemoval() {
        val store = InMemoryKeyValueStore(mapOf("a" to "x", "flag" to true))

        assertEquals("x", store.getString("a", null))
        assertEquals("fallback", store.getString("missing", "fallback"))
        assertTrue(store.getBoolean("flag", false))
        assertFalse(store.getBoolean("missing", false))

        store.edit { remove("a") }
        assertNull(store.getString("a", null))
    }

    @Test
    fun inMemoryStoreIgnoresTypeMismatch() {
        // Mirrors SharedPreferences' behaviour of returning the default rather
        // than throwing when a key holds a different type.
        val store = InMemoryKeyValueStore(mapOf("a" to true))

        assertEquals("fallback", store.getString("a", "fallback"))
    }
}
