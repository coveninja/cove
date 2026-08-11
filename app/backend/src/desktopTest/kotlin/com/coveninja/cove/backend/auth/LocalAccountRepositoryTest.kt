package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AuthOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

private const val PROFILE_ID = "local-primary"

/**
 * What the account settings page actually reads.
 *
 * The point of these is the *reporting*: a sign-in that half-worked, or a sync
 * that failed, has to end up visible rather than swallowed, because the status
 * line is the only evidence the user gets that any of this happened.
 */
class LocalAccountRepositoryTest {
    // Mutation applied to verify: had refreshAccountState() report SignedIn
    // whenever me() returned without throwing → this failed, showing a signed-in
    // account with no session at all.
    @Test
    fun `starts signed out with no stored session`() = runTest {
        fixture { graph ->
            assertEquals(AccountState.SignedOut, graph.account.account.value)
            assertNull(graph.account.syncStatus.value.lastSyncedAt)
        }
    }

    // Mutation applied to verify: dropped the sync() from onSignedIn() → this
    // failed on lastSyncedAt, i.e. signing in would leave the device out of step
    // until something else triggered a sync.
    @Test
    fun `signing in reports the account and syncs immediately`() = runTest {
        fixture { graph ->
            assertEquals(AuthOutcome.Success, graph.account.signIn("a@b.c", "hunter2"))

            val state = assertIs<AccountState.SignedIn>(graph.account.account.value)
            assertEquals("a@b.c", state.email)
            assertEquals("user-1", state.userId)
            val status = graph.account.syncStatus.value
            assertNotNull(status.lastSyncedAt, "signing in did not sync")
            assertNull(status.lastError)
            assertTrue(!status.running)
        }
    }

    // Mutation applied to verify: returned Failure(error.message) instead of the
    // SupabaseException detail → this failed with "Supabase auth (400): Invalid
    // login credentials", which is our plumbing shown to someone who mistyped a
    // password.
    @Test
    fun `a refused sign-in reports Supabase's own wording and stays signed out`() = runTest {
        fixture(signInStatus = HttpStatusCode.BadRequest) { graph ->
            val outcome = graph.account.signIn("a@b.c", "wrong")

            assertEquals("Invalid login credentials", assertIs<AuthOutcome.Failure>(outcome).message)
            assertEquals(AccountState.SignedOut, graph.account.account.value)
        }
    }

    // Mutation applied to verify: made sync() treat a non-empty pushError as a
    // full failure and leave lastSyncedAt untouched → this failed, because a sync
    // that pulled fine and failed one push would then look like it never ran.
    @Test
    fun `a partial push failure is reported without discarding the sync`() = runTest {
        // Only the profile upsert fails; everything pulled still merged.
        fixture(failProfileUpsert = true) { graph ->
            graph.account.signIn("a@b.c", "hunter2")

            val status = graph.account.syncStatus.value
            assertNotNull(status.lastSyncedAt, "the sync that mostly worked was discarded")
            assertTrue(
                status.lastError?.startsWith("profile:") == true,
                "push failure not reported: ${status.lastError}",
            )
        }
    }

    // Mutation applied to verify: had signOut() leave _account untouched → this
    // failed, leaving the page claiming a session that had just been cleared.
    @Test
    fun `signing out clears the account and its sync status`() = runTest {
        fixture { graph ->
            graph.account.signIn("a@b.c", "hunter2")
            graph.account.signOut()

            assertEquals(AccountState.SignedOut, graph.account.account.value)
            assertNull(graph.account.syncStatus.value.lastSyncedAt)
        }
    }

    private suspend fun fixture(
        signInStatus: HttpStatusCode = HttpStatusCode.OK,
        failProfileUpsert: Boolean = false,
        test: suspend (TestGraph) -> Unit,
    ) {
        val dataDir = Files.createTempDirectory("cove-account")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dataDir) { PROFILE_ID }.importIfNeeded()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val http = HttpClient(MockEngine) {
                engine { addHandler { request -> respond(request, signInStatus, failProfileUpsert) } }
            }
            try {
                var id = 0
                val ids = { "id-${++id}" }
                val now = { "2026-08-11T00:00:00Z" }
                val session = ActiveProfileSession(database.database)
                val profiles = LocalProfileRepository(database.database, session, ids, now)
                val library = LocalLibraryRepository(database.database, session, scope, ids, now)
                val settings = LocalSettingsRepository(database.database, session, scope, now) { "device-token" }
                val supabase = SupabaseClient(SupabaseConfig("https://project.invalid", "anon"), http)
                val auth = AuthService(
                    client = supabase,
                    sessions = AuthSessionStore(database.database, now),
                    profiles = profiles,
                    settings = settings,
                    sync = SupabaseSyncService(
                        client = supabase,
                        database = database.database,
                        profiles = profiles,
                        library = library,
                        settings = settings,
                        now = now,
                    ),
                )
                test(TestGraph(LocalAccountRepository(auth, settings, library, scope)))
            } finally {
                scope.cancel()
                http.close()
            }
        }
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respond(
        request: HttpRequestData,
        signInStatus: HttpStatusCode,
        failProfileUpsert: Boolean,
    ) = when {
        request.url.encodedPath.startsWith("/auth/v1/token") -> if (signInStatus.isSuccess()) {
            json(
                """{"access_token":"jwt-1","refresh_token":"refresh-1","expires_in":3600,
                    "user":{"id":"user-1","email":"a@b.c"}}""",
            )
        } else {
            json("""{"error_description":"Invalid login credentials"}""", signInStatus)
        }

        request.url.encodedPath.endsWith("/profiles") -> when {
            request.method == HttpMethod.Get ->
                // Matching the local id keeps profile reconciliation on its
                // early-return path, so the only failure is the push below.
                json("""[{"id":"$PROFILE_ID","user_id":"user-1","name":"Cove","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]""")

            failProfileUpsert -> json("""{"message":"denied"}""", HttpStatusCode.InternalServerError)
            else -> json("[]")
        }

        else -> json("[]")
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private data class TestGraph(val account: LocalAccountRepository)
}
