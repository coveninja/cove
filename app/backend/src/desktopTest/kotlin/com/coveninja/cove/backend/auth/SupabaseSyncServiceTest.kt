package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonSyncPayload
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class SupabaseSyncServiceTest {
    @Test
    fun pullMergesRemoteDataThenPushesThroughUserRls() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        fixture(
            remote = { request, profileId ->
                requests += request
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "library_entries" -> if (request.method == HttpMethod.Get) """[{
                        "id":"remote-entry","profile_id":"$profileId","tmdb_id":42,"media_type":"movie",
                        "title":"Remote Movie","status":"watch_later","added_at":"2026-08-01T00:00:00Z",
                        "updated_at":"2026-08-02T00:00:00Z"
                    }]""" else "[]"
                    "profile_settings" -> if (request.method == HttpMethod.Get) """[{"data":{
                        "defaultVolume":0.25,"remoteAccessEnabled":true,"remoteAccessToken":"remote-secret",
                        "allowLanStreamSources":true,"updatedAt":"2026-08-02T00:00:00Z"
                    },"updated_at":"2026-08-02T00:00:00Z"}]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            graph.settings.update(AppSettings(remoteAccessEnabled = true, remoteAccessToken = "local-secret"))
            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertEquals("", result.pushError)
            assertEquals(
                "Remote Movie",
                assertIs<LibraryState.Ready>(graph.library.entries.value).entries.single().title,
            )
            val merged = assertIs<SettingsState.Ready>(graph.settings.settings.value).settings
            assertEquals(0.25, merged.defaultVolume)
            assertTrue(merged.remoteAccessEnabled)
            assertEquals("local-secret", merged.remoteAccessToken)
            assertEquals(
                "user-1",
                assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).profiles.single().supabaseUid,
            )
            assertTrue(requests.filter { it.method == HttpMethod.Post }.isNotEmpty())
            assertTrue(requests.all { request ->
                !request.url.encodedPath.startsWith("/rest/v1/") ||
                    request.headers[HttpHeaders.Authorization] == "Bearer jwt"
            })
        }
    }

    @Test
    fun freshDeviceAdoptsCanonicalRemoteProfileIdWithoutLosingLocalLibrary() = runTest {
        fixture(
            remote = { request, _ ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> if (request.method == HttpMethod.Get) {
                        """[{"id":"remote-primary","user_id":"user-1","name":"Remote Name","is_primary":true,"updated_at":"2026-08-02T00:00:00Z"}]"""
                    } else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            graph.library.add(7, MediaType.Movie, "Local Movie")
            graph.sync.reconcileAndSync("user-1", "jwt")

            val state = assertIs<ProfilesState.Ready>(graph.profiles.profiles.value)
            assertEquals("remote-primary", state.activeProfileId)
            assertEquals("Remote Name", state.profiles.single().name)
            assertEquals(
                "Local Movie",
                assertIs<LibraryState.Ready>(graph.library.entries.value).entries.single().title,
            )
            assertEquals(
                "remote-primary",
                graph.database.database.coveQueries.selectLibraryEntries("remote-primary")
                    .executeAsOne().profile_id,
            )
        }
    }

    /**
     * Rows written by an older generation of this app.
     *
     * `watch_progress.library_entry_id` is null for progress recorded before that
     * column existed, and for progress whose library entry was later deleted.
     * Decoding those into a non-null String threw, and because the pull is the
     * first thing a sync does, one such row from 2026-07 aborted *everything* —
     * library, settings, addons — on every sync attempt.
     */
    @Test
    fun `progress with no library entry id merges and relinks to the local entry`() = runTest {
        fixture(
            remote = { request, profileId ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "library_entries" -> if (request.method == HttpMethod.Get) """[{
                        "id":"remote-entry","profile_id":"$profileId","tmdb_id":87917,"media_type":"tv",
                        "title":"Remote Show","status":"watching","added_at":"2026-07-01T00:00:00Z",
                        "updated_at":"2026-07-22T21:35:11.948704+00:00"
                    }]""" else "[]"
                    // The shape from the user's own account, verbatim.
                    "watch_progress" -> if (request.method == HttpMethod.Get) """[{
                        "id":"remote-progress","profile_id":"$profileId","library_entry_id":null,
                        "tmdb_id":87917,"media_type":"tv","season":5,"episode":1,
                        "position_seconds":120.0,"duration_seconds":2400.0,"completed":true,
                        "watched_at":"2026-07-22T21:35:11.948704+00:00"
                    }]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertEquals("", result.pullWarning, "a legacy row was skipped instead of merged")
            val stored = graph.database.database.coveQueries
                .selectWatchProgress(
                    (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId,
                )
                .executeAsList()
                .single()
            assertEquals(87917L, stored.tmdb_id)
            assertEquals(
                "remote-entry",
                stored.library_entry_id,
                "progress was not relinked to the library entry it belongs to",
            )
        }
    }

    /**
     * The same field, outbound.
     *
     * Most rows migrated from the pre-SQLite files carry no entry id, but this
     * profile still knows which entry the progress belongs to — so the push sends
     * that rather than writing the gap out to every other device. An empty string
     * is never an option: the column is a uuid upstream and would be rejected.
     */
    @Test
    fun `progress with no entry id is pushed linked to its library entry`() = runTest {
        var pushedProgress: String? = null
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post &&
                    request.url.encodedPath.endsWith("/watch_progress")
                ) {
                    pushedProgress = (request.body as TextContent).text
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    else -> "[]"
                }
            },
        ) { graph ->
            graph.library.add(11, MediaType.Movie, "Local Movie")
            val profileId = (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId
            val entryId = graph.database.database.coveQueries.selectLibraryEntries(profileId)
                .executeAsList().single { it.tmdb_id == 11L }.id
            // A row as the legacy migration left it: no entry id at all.
            graph.database.database.coveQueries.upsertWatchProgress(
                "11:movie", "progress-1", profileId, "", 11L, "movie", null, null,
                30.0, 90.0, 0L, "2026-08-01T00:00:00Z",
            )
            graph.sync.reconcileAndSync("user-1", "jwt")

            val body = assertNotNull(pushedProgress, "no watch_progress push happened")
            val pushed = CoveJson.parseToJsonElement(body).jsonArray
                .map { it.jsonObject }
                .single { it["id"]?.jsonPrimitive?.content == "progress-1" }
            assertEquals(
                entryId,
                pushed["library_entry_id"]?.jsonPrimitive?.contentOrNull,
                "progress was not linked to the entry this profile already has",
            )
        }
    }

    /**
     * The same title under two row ids.
     *
     * Upstream, (profile_id, tmdb_id, media_type) is unique, so pushing our id for
     * a title another device already owns under a different id is rejected — and
     * because the push is one batch, that single row costs the entire library.
     * Seen for real: 229 of 230 entries agreed, one did not, and no library sync
     * had worked since.
     */
    @Test
    fun `an entry adopts the id the account already uses for that title`() = runTest {
        fixture(
            remote = { request, profileId ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "library_entries" -> if (request.method == HttpMethod.Get) """[{
                        "id":"shared-id","profile_id":"$profileId","tmdb_id":5,"media_type":"movie",
                        "title":"Same Film","status":"watch_later","added_at":"2026-08-01T00:00:00Z",
                        "updated_at":"2026-08-01T00:00:00Z"
                    }]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            // Local copy of the same title, newer, under a different id — so the
            // content stays and only the identity moves.
            graph.library.add(5, MediaType.Movie, "Same Film")
            val profileId = (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId
            val localId = graph.database.database.coveQueries.selectLibraryEntries(profileId)
                .executeAsList().single { it.tmdb_id == 5L }.id
            graph.database.database.coveQueries.upsertWatchProgress(
                "5:movie", "progress-5", profileId, localId, 5L, "movie", null, null,
                10.0, 100.0, 0L, "2026-08-05T00:00:00Z",
            )

            graph.sync.reconcileAndSync("user-1", "jwt")

            val entries = graph.database.database.coveQueries.selectLibraryEntries(profileId)
                .executeAsList().filter { it.tmdb_id == 5L }
            assertEquals(1, entries.size, "adoption left a duplicate row behind")
            assertEquals("shared-id", entries.single().id)
            assertEquals("Same Film", entries.single().title)
            assertEquals(
                "shared-id",
                graph.database.database.coveQueries.selectWatchProgress(profileId)
                    .executeAsList().single { it.tmdb_id == 5L }.library_entry_id,
                "watch progress was left pointing at the old id",
            )
        }
    }

    /**
     * The same episode under two row ids — the progress table's version of the
     * problem above, and the one that blocked this account after the entries were
     * fixed. (profile_id, tmdb_id, media_type, season, episode) is unique
     * upstream, so a divergent id fails the whole progress batch.
     */
    @Test
    fun `progress adopts the id the account already uses for that episode`() = runTest {
        fixture(
            remote = { request, profileId ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "watch_progress" -> if (request.method == HttpMethod.Get) """[{
                        "id":"shared-progress","profile_id":"$profileId","library_entry_id":null,
                        "tmdb_id":9,"media_type":"tv","season":1,"episode":2,
                        "position_seconds":5.0,"duration_seconds":100.0,"completed":false,
                        "watched_at":"2026-08-01T00:00:00Z"
                    }]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            val profileId = (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId
            // Newer locally, under our own id: the position must survive, the id
            // must not.
            graph.database.database.coveQueries.upsertWatchProgress(
                "9:tv:1:2", "local-progress", profileId, "", 9L, "tv", 1L, 2L,
                90.0, 100.0, 0L, "2026-08-09T00:00:00Z",
            )

            graph.sync.reconcileAndSync("user-1", "jwt")

            val stored = graph.database.database.coveQueries.selectWatchProgress(profileId)
                .executeAsList().single { it.tmdb_id == 9L }
            assertEquals("shared-progress", stored.id, "row id did not converge")
            assertEquals(90.0, stored.position_seconds, "the newer local position was lost")
        }
    }

    /**
     * Progress left behind by a title that was removed from the library.
     *
     * The row keeps naming the entry it used to belong to, and upstream that
     * column is a foreign key — so one leftover row rejects the entire progress
     * batch. Four of these were sitting in a real library and no progress had
     * synced since.
     */
    @Test
    fun `progress pointing at a deleted entry is pushed unlinked`() = runTest {
        var pushedProgress: String? = null
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post &&
                    request.url.encodedPath.endsWith("/watch_progress")
                ) {
                    pushedProgress = (request.body as TextContent).text
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    else -> "[]"
                }
            },
        ) { graph ->
            val profileId = (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId
            // Watched, then removed from the library: the entry is gone, the
            // progress and its stale pointer are not.
            graph.database.database.coveQueries.upsertWatchProgress(
                "77:movie", "orphan-progress", profileId, "entry-that-no-longer-exists",
                77L, "movie", null, null, 42.0, 100.0, 0L, "2026-08-01T00:00:00Z",
            )

            graph.sync.reconcileAndSync("user-1", "jwt")

            val body = assertNotNull(pushedProgress, "no watch_progress push happened")
            assertTrue(
                "entry-that-no-longer-exists" !in body,
                "a dangling entry id went on the wire, which upstream refuses: $body",
            )
        }
    }

    /**
     * PostgREST refuses a bulk upsert whose objects do not all carry the same
     * keys — "All object keys must match", and the whole batch is lost.
     *
     * That is easy to reintroduce, because the wire encoder omits null fields by
     * default: one film with a rating and one without is enough to produce two
     * shapes and fail every library push. Hit exactly this against a real account.
     */
    @Test
    fun `every row in a bulk push carries the same keys`() = runTest {
        var pushedEntries: String? = null
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post &&
                    request.url.encodedPath.endsWith("/library_entries")
                ) {
                    pushedEntries = (request.body as TextContent).text
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    else -> "[]"
                }
            },
        ) { graph ->
            graph.library.add(1, MediaType.Movie, "Rated")
            graph.library.add(2, MediaType.Movie, "Unrated")
            // Exactly the asymmetry that breaks it: one row has a value the other
            // does not, so the omit-nulls encoder gives them different shapes.
            graph.library.setRating(1, MediaType.Movie, 4.0)
            graph.sync.reconcileAndSync("user-1", "jwt")

            val rows = CoveJson.parseToJsonElement(
                assertNotNull(pushedEntries, "no library push happened"),
            ).jsonArray.map { it.jsonObject.keys }
            assertEquals(2, rows.size)
            assertEquals(rows.first(), rows.last(), "rows went out with different key sets")
        }
    }

    /**
     * One row this build cannot read must cost that row, not the sync.
     */
    @Test
    fun `an unreadable row is skipped and reported rather than aborting the sync`() = runTest {
        fixture(
            remote = { request, profileId ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "library_entries" -> if (request.method == HttpMethod.Get) """[
                        {"id":"broken","profile_id":"$profileId","tmdb_id":1,"media_type":"movie",
                         "title":null,"status":"watch_later","added_at":"2026-08-01T00:00:00Z",
                         "updated_at":"2026-08-01T00:00:00Z"},
                        {"id":"fine","profile_id":"$profileId","tmdb_id":2,"media_type":"movie",
                         "title":"Readable","status":"watch_later","added_at":"2026-08-01T00:00:00Z",
                         "updated_at":"2026-08-02T00:00:00Z"}
                    ]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertEquals(
                "Readable",
                assertIs<LibraryState.Ready>(graph.library.entries.value).entries.single().title,
                "the readable row did not survive alongside the broken one",
            )
            assertTrue(
                result.pullWarning.contains("library_entries"),
                "skipped row was not reported: '${result.pullWarning}'",
            )
        }
    }

    /**
     * A payload blob this build cannot read costs that blob, not the sync.
     *
     * The addon list is the one that bit: entries written by the previous
     * generation of this app omit fields the current models require, and the
     * merge sits before every push, so one such row meant *nothing* synced —
     * library, settings and watch progress included.
     */
    @Test
    fun `an unreadable payload blob is reported without failing the whole sync`() = runTest {
        fixture(
            remote = { request, profileId ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "library_entries" -> if (request.method == HttpMethod.Get) """[{
                        "id":"remote-entry","profile_id":"$profileId","tmdb_id":42,"media_type":"movie",
                        "title":"Remote Movie","status":"watch_later","added_at":"2026-08-01T00:00:00Z",
                        "updated_at":"2026-08-02T00:00:00Z"
                    }]""" else "[]"
                    "profile_addons" -> if (request.method == HttpMethod.Get) {
                        """[{"profile_id":"$profileId","data":[{"nonsense":true}],"updated_at":"2026-08-09T00:00:00Z"}]"""
                    } else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertTrue(
                result.pullWarning.contains("addons"),
                "unreadable addon blob was not reported: '${result.pullWarning}'",
            )
            assertEquals(
                "Remote Movie",
                assertIs<LibraryState.Ready>(graph.library.entries.value).entries.single().title,
                "the library did not sync despite the addon blob being unreadable",
            )
        }
    }

    /**
     * Official integrations have no URL at all.
     *
     * `cove.justwatch` and `cove.introdb` are dispatched by id and have no
     * manifest to fetch, so the previous generation of this app stored them with
     * no url field. Requiring one failed the decode of the whole addon list.
     */
    @Test
    fun `official addons sync without a url and get the local synthetic one`() = runTest {
        fixture(
            remote = { request, profileId ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    // The shape from the user's own account: no url, source official.
                    "profile_addons" -> if (request.method == HttpMethod.Get) """[{
                        "profile_id":"$profileId","updated_at":"2026-08-09T00:00:00Z","data":[
                          {"id":"cove.justwatch","kind":"provider","source":"official","enabled":true,
                           "manifest":{"id":"cove.justwatch","name":"JustWatch"}}
                        ]}]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertEquals("", result.pullWarning, "the addon blob was not merged")
            val stored = graph.database.database.coveQueries
                .selectAddons((graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId)
                .executeAsList()
                .first { it.addon_id == "cove.justwatch" }
            assertEquals("official:cove.justwatch", stored.url)
        }
    }

    /**
     * The guarantee that makes syncing from a reduced-capability host safe.
     *
     * Such a host runs no addon manager, so it has no view of the addon list at
     * all. If a sync from there pushed what it knows — nothing — the desktop's
     * configured providers would be wiped on its next pull, and playback would
     * quietly stop finding sources everywhere.
     */
    @Test
    fun `a host with no addon manager round-trips the addon blob untouched`() = runTest {
        val posted = mutableMapOf<String, String>()
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post) {
                    posted[request.url.encodedPath.substringAfterLast('/')] =
                        (request.body as TextContent).text
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    // Deliberately not a shape this build can parse: the point is
                    // that an unrecognised blob survives a host that cannot read it.
                    "profile_addons" -> if (request.method == HttpMethod.Get) {
                        """[{"profile_id":"$profileId","data":[{"future_addon_field":"keep me"}],"updated_at":"2026-08-09T00:00:00Z"}]"""
                    } else "[]"
                    else -> "[]"
                }
            },
            withAddonParticipant = false,
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")

            val stored = graph.database.database.coveQueries
                .selectLegacyPayloadRecord(
                    (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId,
                    "addons",
                )
                .executeAsOneOrNull()
            assertTrue(
                stored?.json?.contains("future_addon_field") == true,
                "the unreadable addon blob was not kept locally",
            )
            assertTrue(
                posted["profile_addons"]?.contains("future_addon_field") == true,
                "the addon blob was not pushed back; other devices would lose it",
            )
        }
    }

    /**
     * The other half of the same guarantee: with nothing to say about a payload,
     * say nothing.
     *
     * A host with no participant and no stored blob — a phone signing in for the
     * first time, before any device has pushed addons — must not push an empty
     * list. An empty push is indistinguishable from "I deliberately have no
     * addons", and carries a fresh timestamp that beats every other device's.
     */
    @Test
    fun `a host with nothing to say about a payload pushes nothing`() = runTest {
        val posted = mutableListOf<String>()
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post) {
                    posted += request.url.encodedPath.substringAfterLast('/')
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    else -> "[]"
                }
            },
            withAddonParticipant = false,
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")

            assertTrue(
                "profile_addons" !in posted,
                "pushed an addon row this host knows nothing about: $posted",
            )
        }
    }


    // --- the profile roster -------------------------------------------------
    //
    // Every fixture above returns a single-profile `profiles` array, which is
    // exactly why none of them noticed that a second profile never left the
    // device it was made on.

    @Test
    fun `a profile from another device is added to this one`() = runTest {
        fixture(
            remote = { _, profileId -> roster(primary(profileId), secondary("kids-1", "Kids")) },
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")

            val state = assertIs<ProfilesState.Ready>(graph.profiles.profiles.value)
            assertEquals(listOf("Primary", "Kids"), state.profiles.map { it.name })
            // Adopting the account's own row, not inventing an id for it: every one
            // of that profile's rows upstream is keyed by it.
            assertEquals("kids-1", state.profiles.single { it.name == "Kids" }.id)
            // The viewer is not moved off the profile they were using.
            assertEquals(profileIdOf(graph), state.activeProfileId)
        }
    }

    // The first half of the household scenario: a profile made on the desktop and
    // never switched to still belongs to the account.
    @Test
    fun `a profile created here and never activated is pushed`() = runTest {
        var pushed: String? = null
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/profiles")) {
                    pushed = (request.body as TextContent).text
                }
                roster(primary(profileId))
            },
        ) { graph ->
            val kids = graph.profiles.create("Kids")

            graph.sync.reconcileAndSync("user-1", "jwt")

            val rows = CoveJson.parseToJsonElement(assertNotNull(pushed)).jsonArray
            assertEquals(
                setOf(profileIdOf(graph), kids.id),
                rows.mapTo(mutableSetOf()) { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
            // PostgREST refuses a bulk upsert whose objects do not all carry the
            // same keys, and it refuses the whole batch rather than one row of it.
            assertEquals(1, rows.map { it.jsonObject.keys }.distinct().size)
        }
    }

    @Test
    fun `a tombstoned profile is removed even while it is the active one`() = runTest {
        var kidsDeleted = false
        fixture(
            remote = { _, profileId ->
                if (kidsDeleted) {
                    roster(primary(profileId), secondary("kids-1", "Kids", deletedAt = "2026-08-02T00:00:00Z"))
                } else {
                    roster(primary(profileId), secondary("kids-1", "Kids"))
                }
            },
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")
            graph.profiles.activate("kids-1")
            assertEquals("kids-1", assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).activeProfileId)

            kidsDeleted = true
            graph.sync.reconcileAndSync("user-1", "jwt")

            val state = assertIs<ProfilesState.Ready>(graph.profiles.profiles.value)
            assertEquals(listOf("Primary"), state.profiles.map { it.name })
            assertEquals(profileIdOf(graph), state.activeProfileId)
        }
    }

    // Child RLS proves ownership by looking the parent row up, so a tombstone
    // written first would put every one of that profile's rows beyond reach — a
    // property invisible in the result and only checkable in the request order.
    @Test
    fun `a removal clears the child rows before tombstoning the profile`() = runTest {
        val calls = mutableListOf<String>()
        fixture(
            remote = { request, profileId ->
                val table = request.url.encodedPath.substringAfterLast('/')
                if (request.method != HttpMethod.Get) calls += "${request.method.value} $table"
                roster(primary(profileId))
            },
        ) { graph ->
            val kids = graph.profiles.create("Kids")
            graph.profiles.delete(kids.id)

            graph.sync.reconcileAndSync("user-1", "jwt")

            val patch = calls.indexOf("PATCH profiles")
            assertTrue(patch >= 0, "the profile was never tombstoned: $calls")
            val children = calls.withIndex().filter { it.value.startsWith("DELETE ") }
            assertTrue(children.isNotEmpty(), "no child rows were cleared: $calls")
            assertTrue(
                children.all { it.index < patch },
                "child rows must be cleared before the parent is tombstoned: $calls",
            )
            // Every profile-keyed table upstream, checked as a whole rather than a
            // sample: one left out leaves rows nothing can reach once the parent is
            // tombstoned, and nothing else in the system would ever notice.
            assertEquals(
                setOf(
                    "library_entries",
                    "watch_progress",
                    "dismissals",
                    "library_removals",
                    "profile_settings",
                    "profile_addons",
                    "profile_nuvio",
                    "profile_activity",
                ),
                children.mapTo(mutableSetOf()) { it.value.removePrefix("DELETE ") },
                "this must match every table with a profile_id column upstream",
            )
            // Cleared once it succeeded, so it is not tombstoned again every sync.
            assertEquals(emptyList(), graph.database.database.coveQueries.selectProfileRemovals().executeAsList())
        }
    }

    // Two things keep the deleted profile out of that push, and either alone is
    // enough: the tombstone goes up before the roster is read, and a removal still
    // pending is skipped on the way back in. So neither is a mutation this test can
    // discriminate on its own — the test above pins the second directly.
    @Test
    fun `a profile deleted here is not pushed back`() = runTest {
        val pushes = mutableListOf<String>()
        var tombstoned = false
        fixture(
            remote = { request, profileId ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/profiles") ->
                        pushes += (request.body as TextContent).text
                    // The server the account actually has: once told, it answers
                    // with the tombstone rather than the profile.
                    request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/profiles") ->
                        tombstoned = true
                }
                if (tombstoned) {
                    roster(primary(profileId), secondary("kids-1", "Kids", deletedAt = "2026-08-02T00:00:00Z"))
                } else {
                    roster(primary(profileId), secondary("kids-1", "Kids"))
                }
            },
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")
            graph.profiles.delete("kids-1")

            graph.sync.reconcileAndSync("user-1", "jwt")

            assertTrue(
                pushes.last().let { "kids-1" !in it },
                "a deleted profile must not be re-pushed: ${pushes.last()}",
            )
            assertEquals(
                listOf("Primary"),
                assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).profiles.map { it.name },
            )
        }
    }

    // The identity step has to run before the roster merge. Merging first would add
    // the account's primary as a *second* local profile, and the adoption would then
    // find the id taken and leave this device permanently duplicated.
    @Test
    fun `a fresh device adopts the primary rather than gaining a third profile`() = runTest {
        fixture(
            remote = { _, _ ->
                roster(
                    primary("remote-primary", "Arcady"),
                    secondary("remote-second", "Kids"),
                )
            },
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")

            val state = assertIs<ProfilesState.Ready>(graph.profiles.profiles.value)
            assertEquals(setOf("remote-primary", "remote-second"), state.profiles.mapTo(mutableSetOf()) { it.id })
            assertEquals("remote-primary", state.activeProfileId)
            // One primary per account, and it is the account's, not this device's.
            assertEquals(listOf("Arcady"), state.profiles.filter { it.isPrimary }.map { it.name })
        }
    }

    // The tombstone can only be pushed when there is a connection, and until it is
    // the account still lists the profile. Re-adding it from that listing would undo
    // the deletion on every sync until the device got online — the resurrection this
    // whole mechanism exists to prevent, arriving from the device that did the delete.
    @Test
    fun `a removal that cannot be pushed yet still keeps the profile deleted`() = runTest {
        fixture(
            // The account has not been told, and cannot be: the tombstone write fails.
            remote = { _, profileId -> roster(primary(profileId), secondary("kids-1", "Kids")) },
            status = { request ->
                if (request.method == HttpMethod.Patch) {
                    HttpStatusCode.InternalServerError
                } else {
                    HttpStatusCode.OK
                }
            },
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")
            graph.profiles.delete("kids-1")

            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertEquals(
                listOf("Primary"),
                assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).profiles.map { it.name },
            )
            // Still pending, so it is retried rather than forgotten.
            assertEquals(
                listOf("kids-1"),
                graph.database.database.coveQueries.selectProfileRemovals().executeAsList().map { it.profile_id },
            )
            assertTrue(result.pushError.contains("profile removals"), "the failure was not reported: $result")

            // And it holds across further syncs, not just the one.
            graph.sync.reconcileAndSync("user-1", "jwt")
            assertEquals(
                listOf("Primary"),
                assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).profiles.map { it.name },
            )
        }
    }

    private fun profileIdOf(graph: TestGraph): String =
        assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).profiles.single { it.isPrimary }.id

    private fun roster(vararg rows: String) = rows.joinToString(",", "[", "]")

    private fun primary(id: String, name: String = "Primary") =
        """{"id":"$id","user_id":"user-1","name":"$name","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}"""

    private fun secondary(id: String, name: String, deletedAt: String? = null) =
        """{"id":"$id","user_id":"user-1","name":"$name","is_primary":false,""" +
            """"updated_at":"2026-01-01T00:00:00Z","deleted_at":${deletedAt?.let { "\"$it\"" } ?: "null"}}"""

    private suspend fun fixture(
        remote: (HttpRequestData, String) -> String,
        /** Lets a test refuse one kind of request while the rest still succeed. */
        status: (HttpRequestData) -> HttpStatusCode = { HttpStatusCode.OK },
        // False models a host that runs no addon manager, where the addon blob
        // has to survive a sync untouched rather than being replaced.
        withAddonParticipant: Boolean = true,
        test: suspend (TestGraph) -> Unit,
    ) {
        val dataDir = Files.createTempDirectory("cove-supabase-sync")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dataDir) { "local-primary" }.importIfNeeded()
            val profileId = database.database.coveQueries.selectActiveProfileId().executeAsOne()
            val http = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        respond(
                            remote(request, profileId),
                            status(request),
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var id = 0
                val ids = { "id-${++id}" }
                val now = { "2026-08-01T00:00:00Z" }
                val session = ActiveProfileSession(database.database)
                val profiles = LocalProfileRepository(database.database, session, ids, now)
                val library = LocalLibraryRepository(database.database, session, scope, ids, now)
                val settings = LocalSettingsRepository(database.database, session, scope, now) { "device-token" }
                val addons = AddonManager(database.database, session, http, now)
                val client = SupabaseClient(SupabaseConfig("https://project.invalid", "anon"), http)
                val sync = SupabaseSyncService(
                    client,
                    database.database,
                    profiles,
                    library,
                    settings,
                    now,
                    if (withAddonParticipant) listOf(AddonSyncPayload(addons)) else emptyList(),
                )
                test(TestGraph(database, profiles, library, settings, sync))
            } finally {
                scope.cancel()
                http.close()
            }
        }
    }

    private data class TestGraph(
        val database: DesktopDatabase,
        val profiles: LocalProfileRepository,
        val library: LocalLibraryRepository,
        val settings: LocalSettingsRepository,
        val sync: SupabaseSyncService,
    )
}
