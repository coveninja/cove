package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.db.DesktopDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthSessionStoreTest {
    @Test
    fun structuredAndOpaqueSessionsRoundTripAndClear() {
        DesktopDatabase.inMemory().use { store ->
            val auth = AuthSessionStore(store.database) { "now" }
            val session = SupabaseSession("user", "a@example.com", "access", "refresh", 123L)
            auth.save(session)
            assertEquals(session, auth.get())
            auth.clear()
            assertNull(auth.get())

            val opaque = ClientSessionStore(store.database) { "now" }
            opaque.save("""{"access_token":"legacy","profile_id":"primary"}""")
            assertEquals(
                """{"access_token":"legacy","profile_id":"primary"}""",
                opaque.get(),
            )
            assertFailsWith<IllegalArgumentException> { opaque.save("{") }
            assertFailsWith<IllegalArgumentException> {
                opaque.save("\"${"x".repeat((1 shl 20) + 1)}\"")
            }
            opaque.clear()
            assertNull(opaque.get())
        }
    }
}
