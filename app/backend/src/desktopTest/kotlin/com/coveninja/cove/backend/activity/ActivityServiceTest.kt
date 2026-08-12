package com.coveninja.cove.backend.activity

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityServiceTest {
    @Test
    fun boundedProgressDeltasProduceStableStatsAndIgnoreSeeks() {
        DesktopDatabase.inMemory().use { handle ->
            val queries = handle.database.coveQueries
            queries.insertProfile("p1", "Primary", 1, null, "")
            queries.setActiveProfile("p1")
            val service = ActivityService(
                handle.database,
                ActiveProfileSession(handle.database),
                Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC),
                ZoneOffset.UTC,
            )

            service.record(progress(10.0, "2026-08-08T10:00:00Z"))
            service.record(progress(25.0, "2026-08-08T10:00:15Z"))
            service.record(progress(500.0, "2026-08-08T10:00:30Z")) // seek: ignored
            service.record(progress(20.0, "2026-08-08T10:00:45Z")) // rewind: ignored
            service.record(progress(40.0, "2026-08-08T10:01:00Z"))

            val stats = service.stats()
            assertEquals(45, stats.totalSeconds)
            assertEquals(1, stats.totalTitles)
            assertEquals(45, stats.thisYearSeconds)
            assertEquals(45, stats.byHourOfDay[10])
            assertEquals(1, stats.currentStreak)
            assertEquals(1, stats.longestStreak)
            assertEquals(45, stats.titlesWatchedThisYear.single().seconds)
        }
    }

    @Test
    fun legacySnapshotsImportOnceAndMaxMergeWithoutDoubleCounting() {
        DesktopDatabase.inMemory().use { handle ->
            val queries = handle.database.coveQueries
            queries.insertProfile("p1", "Primary", 1, null, "")
            queries.setActiveProfile("p1")
            queries.upsertLegacyPayload(
                "p1",
                "activity",
                """{"days":{"2026-08-07":{"by_hour":[0,30],"by_title":{"7:movie":30}}},"last_pos":{"7:movie":30},"backfilled":true}""",
                "",
            )
            val service = ActivityService(
                handle.database,
                ActiveProfileSession(handle.database),
                Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC),
                ZoneOffset.UTC,
            )

            assertEquals(30, service.stats().totalSeconds)
            val snapshot = service.snapshotJson()
            service.mergeFromJson(snapshot)
            assertEquals(30, service.stats().totalSeconds)

            service.mergeFromJson(
                """{"days":{"2026-08-07":{"by_hour":[0,45],"by_title":{"7:movie":45}}},"last_pos":{"7:movie":40},"backfilled":true}""",
            )
            val merged = service.stats()
            assertEquals(45, merged.totalSeconds)
            assertTrue(service.snapshotJson().contains("\"7:movie\":40.0"))
        }
    }

    private fun progress(position: Double, watchedAt: String) = WatchProgress(
        id = "progress",
        profileId = "p1",
        libraryEntryId = "entry",
        tmdbId = 7,
        mediaType = MediaType.Movie,
        positionSeconds = position,
        durationSeconds = 1_000.0,
        watchedAt = watchedAt,
    )
}
