package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AuthOutcome
import com.coveninja.cove.shared.data.ProfilesState
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    @Test
    fun `starts signed out with no stored session`() = runTest {
        fixture { graph ->
            assertEquals(AccountState.SignedOut, graph.account.account.value)
            assertNull(graph.account.syncStatus.value.lastSyncedAt)
        }
    }

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

    @Test
    fun `a refused sign-in reports Supabase's own wording and stays signed out`() = runTest {
        fixture(signInStatus = HttpStatusCode.BadRequest) { graph ->
            val outcome = graph.account.signIn("a@b.c", "wrong")

            assertEquals("Invalid login credentials", assertIs<AuthOutcome.Failure>(outcome).message)
            assertEquals(AccountState.SignedOut, graph.account.account.value)
        }
    }

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

    @Test
    fun `an access token the server has stopped accepting is refreshed and the sync retried`() =
        runTest {
            fixture { graph ->
                graph.account.signIn("a@b.c", "hunter2")
                // No local clock change: the stored session still says it has an hour
                // left, which is precisely the case a device with a wrong or frozen
                // clock is in when Supabase has already stopped accepting the token.
                graph.server.accepted -= "jwt-1"
                graph.server.requests.clear()

                graph.account.syncNow()

                val status = graph.account.syncStatus.value
                assertTrue(!status.failed, "sync gave up rather than refreshing: ${status.lastError}")
                assertNull(status.lastError)
                assertNotNull(status.lastSyncedAt)
                assertTrue(
                    graph.server.requests.any { it.url.encodedQuery.contains("grant_type=refresh_token") },
                    "the refused token was never refreshed",
                )
                assertEquals(
                    "Bearer jwt-2",
                    graph.server.requests.last().headers[HttpHeaders.Authorization],
                    "the retry went out with the token that had just been refused",
                )
            }
        }

    @Test
    fun `a token that dies mid-sync is refreshed rather than reported as a partial push`() =
        runTest {
            fixture { graph ->
                graph.account.signIn("a@b.c", "hunter2")
                graph.server.expireOnWrite = true

                graph.account.syncNow()

                val status = graph.account.syncStatus.value
                assertTrue(!status.failed, "sync gave up: ${status.lastError}")
                assertNull(
                    status.lastError,
                    "a refused token was reported as rows that could not be pushed",
                )
                assertNotNull(status.lastSyncedAt)
            }
        }

    @Test
    fun `a refused refresh asks for a new sign-in and keeps the session`() = runTest {
        fixture { graph ->
            graph.account.signIn("a@b.c", "hunter2")
            graph.server.accepted -= "jwt-1"
            graph.server.refuseRefresh = true

            graph.account.syncNow()

            val status = graph.account.syncStatus.value
            assertTrue(status.failed)
            assertEquals(
                "Your Cove session has expired. Sign in again to resume syncing.",
                status.lastError,
            )
            // Still signed in: Supabase rotates refresh tokens, so two syncs overlapping
            // can refuse a perfectly good session, and signing the device out over that
            // would lose more than the failure it is reacting to.
            assertIs<AccountState.SignedIn>(graph.account.account.value)
        }
    }

    @Test
    fun `a caller that goes away mid sign-in still ends up signed in`() = runTest {
        fixture { graph ->
            val held = CompletableDeferred<Unit>()
            graph.server.holdSignIn = held
            // A caller of its own, standing in for the composition scope behind the form.
            val form = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            form.launch { graph.account.signIn("a@b.c", "hunter2") }
            graph.server.signInReached.await()
            // Supabase has the request; the screen holding it is torn down before the answer.
            form.cancel()
            held.complete(Unit)

            // Real time, not the test scheduler's: the work being waited on is on a
            // dispatcher of its own, and virtual time would expire the timeout the moment
            // this coroutine went idle.
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    graph.account.account.first { it is AccountState.SignedIn }
                    // And the first sync it carries: the attempt has to run to the end on
                    // its own, not merely far enough to record the session. Waiting for it
                    // also keeps the fixture from closing the database underneath it.
                    graph.account.syncStatus.first { !it.running && it.lastSyncedAt != null }
                }
            }
            assertNotNull(
                AuthSessionStore(graph.database, { "" }).get(),
                "the session Supabase accepted was never stored",
            )
        }
    }

    @Test
    fun `signing out clears the account and its sync status`() = runTest {
        fixture { graph ->
            graph.account.signIn("a@b.c", "hunter2")
            graph.account.signOut()

            assertEquals(AccountState.SignedOut, graph.account.account.value)
            assertNull(graph.account.syncStatus.value.lastSyncedAt)
        }
    }

    // A profile that arrived from another device has no data here yet, and the
    // policy's 30s debounce and 2-minute floor would leave it looking empty for
    // minutes. Switching is the one roster event worth bypassing the pacing for.
    @Test
    fun `switching profile syncs at once`() = runTest {
        fixture { graph ->
            graph.account.signIn("a@b.c", "hunter2")
            val syncedBefore = graph.account.syncStatus.value.lastSyncedAt
            val before = graph.server.requests.count { it.url.encodedPath.endsWith("/profiles") }

            val second = graph.profiles.create("Kids")
            graph.profiles.activate(second.id)

            // Real time, not the test scheduler's: the sync runs on a dispatcher of
            // its own and virtual time would expire the moment this coroutine idled.
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    graph.account.syncStatus.first { !it.running && it.lastSyncedAt != syncedBefore }
                }
            }
            assertTrue(
                graph.server.requests.count { it.url.encodedPath.endsWith("/profiles") } > before,
                "switching profile did not sync",
            )
        }
    }

    // A sync can change the active profile itself: a device signing in for the
    // first time is re-keyed onto the account's primary. The watcher must not treat
    // that as the viewer switching profile and sync all over again.
    @Test
    fun `adopting the account's primary does not cost a second sync`() = runTest {
        fixture(remotePrimaryId = "account-primary") { graph ->
            graph.account.signIn("a@b.c", "hunter2")

            // The adoption really happened — otherwise this proves nothing.
            assertEquals(
                "account-primary",
                assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).activeProfileId,
            )
            // Give a stray second sync time to show up before counting.
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    graph.account.syncStatus.first { !it.running && it.lastSyncedAt != null }
                }
                delay(200)
            }
            assertEquals(
                1,
                graph.server.requests.count {
                    it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/profiles")
                },
                "signing in read the roster more than once",
            )
        }
    }

    private suspend fun fixture(
        signInStatus: HttpStatusCode = HttpStatusCode.OK,
        failProfileUpsert: Boolean = false,
        /** Differs from [PROFILE_ID] to model a device joining an existing account. */
        remotePrimaryId: String = PROFILE_ID,
        test: suspend (TestGraph) -> Unit,
    ) {
        val dataDir = Files.createTempDirectory("cove-account")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dataDir) { PROFILE_ID }.importIfNeeded()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val server = Server(signInStatus, failProfileUpsert, remotePrimaryId = remotePrimaryId)
            val http = HttpClient(MockEngine) {
                engine { addHandler { request -> respond(request, server) } }
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
                test(
                    TestGraph(
                        LocalAccountRepository(auth, settings, library, profiles, scope),
                        server,
                        database.database,
                        profiles,
                    ),
                )
            } finally {
                scope.cancel()
                http.close()
            }
        }
    }

    private suspend fun io.ktor.client.engine.mock.MockRequestHandleScope.respond(
        request: HttpRequestData,
        server: Server,
    ) = when {
        request.url.encodedPath.startsWith("/auth/v1/token") -> {
            server.requests += request
            when {
                request.url.encodedQuery.contains("refresh_token") -> if (server.refuseRefresh) {
                    json(
                        """{"error_description":"Invalid Refresh Token: Refresh Token Not Found"}""",
                        HttpStatusCode.BadRequest,
                    )
                } else {
                    server.accepted += "jwt-2"
                    json(
                        """{"access_token":"jwt-2","refresh_token":"refresh-2","expires_in":3600,
                            "user":{"id":"user-1","email":"a@b.c"}}""",
                    )
                }

                server.signInStatus.isSuccess() -> {
                    server.signInReached.complete(Unit)
                    server.holdSignIn?.await()
                    json(
                        """{"access_token":"jwt-1","refresh_token":"refresh-1","expires_in":3600,
                            "user":{"id":"user-1","email":"a@b.c"}}""",
                    )
                }

                else -> json("""{"error_description":"Invalid login credentials"}""", server.signInStatus)
            }
        }

        else -> {
            server.requests += request
            // A token dying mid-sync, which is what a rotated or revoked one does:
            // the pull went through and the first write is refused.
            if (server.expireOnWrite && request.method == HttpMethod.Post) {
                server.accepted -= "jwt-1"
            }
            val bearer = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
            when {
                bearer !in server.accepted ->
                    json("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized)

                request.url.encodedPath.endsWith("/profiles") -> when {
                    request.method == HttpMethod.Get ->
                        // Matching the local id keeps profile reconciliation on its
                        // early-return path, so the only failure is the push below.
                        json("""[{"id":"${server.remotePrimaryId}","user_id":"user-1","name":"Cove","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]""")

                    server.failProfileUpsert ->
                        json("""{"message":"denied"}""", HttpStatusCode.InternalServerError)

                    else -> json("[]")
                }

                else -> json("[]")
            }
        }
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private data class TestGraph(
        val account: LocalAccountRepository,
        val server: Server,
        val database: CoveDatabase,
        val profiles: LocalProfileRepository,
    )

    /**
     * Supabase, as far as these tests are concerned: which access tokens it still
     * honours, and what it does with a refresh.
     *
     * Mutable rather than parameterised because the interesting cases are changes of
     * mind *during* a run — a token that was fine at sign-in and is refused an hour
     * later is exactly the failure the retry exists for, and one that cannot be set up
     * in advance.
     */
    private class Server(
        val signInStatus: HttpStatusCode,
        val failProfileUpsert: Boolean,
        val remotePrimaryId: String = PROFILE_ID,
        val accepted: MutableSet<String> = mutableSetOf("jwt-1"),
        var refuseRefresh: Boolean = false,
        var expireOnWrite: Boolean = false,
        val requests: MutableList<HttpRequestData> = mutableListOf(),
        /** Completed once a sign-in request has arrived, so a test can act while one is open. */
        val signInReached: CompletableDeferred<Unit> = CompletableDeferred(),
        /** Held closed to keep that request open; null lets sign-ins answer immediately. */
        var holdSignIn: CompletableDeferred<Unit>? = null,
    )
}
