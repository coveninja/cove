package com.coveninja.cove.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun syncResponseDecodesGenerationAndIgnoresNewServerFields() {
        val response = json.decodeFromString<SyncResponse>(
            """{"status":"ok","library_generation":42,"push_error":"previous failure"}""",
        )

        assertEquals("ok", response.status)
        assertEquals(42L, response.libraryGeneration)
    }

    @Test
    fun guestProfileKeepsNullableSupabaseIdentity() {
        val profile = json.decodeFromString<Profile>(
            """{"id":"p1","name":"Primary","is_primary":true,"supabase_uid":null}""",
        )

        assertEquals("p1", profile.id)
        assertEquals(true, profile.isPrimary)
        assertNull(profile.supabaseUid)
    }
}
