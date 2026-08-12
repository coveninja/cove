package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.WatchProgressRequest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRepositoriesTest {
    @Test
    fun settingsAndLibraryFollowAtomicProfileActivation() = runTest {
        val dir = Files.createTempDirectory("cove-local-store")
        val clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC)
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir, clock) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var nextId = 0
            val ids = { "id-${++nextId}" }
            val now = { "2026-08-08T12:00:00Z" }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val profiles = LocalProfileRepository(store.database, session, ids, now)
            val settings = LocalSettingsRepository(store.database, session, scope, now) { "remote-token" }
            val library = LocalLibraryRepository(store.database, session, scope, ids, now)
            advanceUntilIdle()

            settings.update(
                assertIs<SettingsState.Ready>(settings.settings.value).settings.copy(
                    defaultVolume = 0.25,
                    remoteAccessEnabled = true,
                ),
            )
            library.add(42, MediaType.Movie, "Primary Movie")
            val child = profiles.create("Child")
            profiles.activate(child.id)
            advanceUntilIdle()

            assertEquals(child.id, assertIs<ProfilesState.Ready>(profiles.profiles.value).activeProfileId)
            assertEquals(1.0, assertIs<SettingsState.Ready>(settings.settings.value).settings.defaultVolume)
            assertEquals(emptyList(), assertIs<LibraryState.Ready>(library.entries.value).entries)

            library.add(7, MediaType.Tv, "Child Show")
            library.setStatus(7, MediaType.Tv, LibraryStatus.Watching)
            library.setEpisodeWatched(7, "Child Show", "", 0.0, 1, 2, 45, true)
            assertEquals(true, library.episodeWatchStates(7, MediaType.Tv)[1 to 2])

            profiles.activate("primary")
            advanceUntilIdle()
            val primarySettings = assertIs<SettingsState.Ready>(settings.settings.value).settings
            assertEquals(0.25, primarySettings.defaultVolume)
            assertEquals("remote-token", primarySettings.remoteAccessToken)
            assertEquals(listOf("Primary Movie"), assertIs<LibraryState.Ready>(library.entries.value).entries.map { it.title })
            scope.cancel()
        }
    }

    @Test
    fun primaryProfileAndInvalidRatingsAreRejected() = runTest {
        val dir = Files.createTempDirectory("cove-local-invariants")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            val profiles = LocalProfileRepository(store.database, session, { "child" }, { "now" })
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val library = LocalLibraryRepository(store.database, session, scope, { "entry" }, { "now" })
            assertFailsWith<IllegalArgumentException> { profiles.delete("primary") }
            library.add(1, MediaType.Movie, "Movie")
            assertFailsWith<IllegalArgumentException> { library.setRating(1, MediaType.Movie, 6.0) }
            scope.cancel()
        }
    }

    @Test
    fun progressNaturalKeysAreIsolatedPerProfile() = runTest {
        val dir = Files.createTempDirectory("cove-local-progress-profiles")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val library = LocalLibraryRepository(store.database, session, scope, { "id-${++id}" }, { "now" })

            library.recordProgress(
                WatchProgressRequest(
                    tmdbId = 42,
                    mediaType = MediaType.Tv,
                    season = 1,
                    episode = 2,
                    positionSeconds = 10.0,
                    durationSeconds = 100.0,
                ),
            )
            assertEquals(10.0, library.watchProgress.value.single().positionSeconds)
            val child = profiles.create("Child")
            profiles.activate(child.id)
            advanceUntilIdle()
            assertEquals(emptyList(), library.watchProgress.value)
            library.recordProgress(
                WatchProgressRequest(
                    tmdbId = 42,
                    mediaType = MediaType.Tv,
                    season = 1,
                    episode = 2,
                    positionSeconds = 80.0,
                    durationSeconds = 100.0,
                ),
            )
            assertEquals(80.0, library.progress(42, MediaType.Tv, 1, 2)?.positionSeconds)
            assertEquals(80.0, library.watchProgress.value.single().positionSeconds)

            profiles.activate("primary")
            advanceUntilIdle()
            assertEquals(10.0, library.progress(42, MediaType.Tv, 1, 2)?.positionSeconds)
            assertEquals(10.0, library.watchProgress.value.single().positionSeconds)
            scope.cancel()
        }
    }
}
