package com.coveninja.cove

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MobileArtifactContractTest {
    @Test
    fun `mobile artifact keeps the upgrade-compatible package identity`() {
        assertEquals("com.coveninja.cove", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `deployment values cannot inject additional build config lines`() {
        listOf(
            BuildConfig.TMDB_API_KEY,
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            BuildConfig.TRAKT_CLIENT_ID,
            BuildConfig.TRAKT_CLIENT_SECRET,
        ).forEach { value -> assertFalse(value.contains('\n')) }
    }

    @Test
    fun `phone artifact requires touch and never advertises a TV launcher`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.hardware.touchscreen"))
        assertTrue(manifest.contains("android:required=\"true\""))
        assertFalse(manifest.contains("android.intent.category.LEANBACK_LAUNCHER"))
    }
}
