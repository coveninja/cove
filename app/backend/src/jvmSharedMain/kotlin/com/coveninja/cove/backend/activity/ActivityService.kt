package com.coveninja.cove.backend.activity

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveJson
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Profile-scoped watch-time accounting.
 *
 * Progress positions are converted to bounded deltas before being credited, so
 * seeks and resume jumps cannot inflate activity. Counters live in normalized
 * SQLite rows and retain the old JSON shape at the sync/import boundary.
 */
class ActivityService(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    init {
        // Complete the one-time historical backfill before the first new
        // progress tick is written, otherwise that tick could be mistaken for
        // pre-existing history.
        ensureInitialized(session.profileId.value)
    }

    fun record(progress: WatchProgress) {
        val profileId = session.profileId.value
        ensureInitialized(profileId)
        val key = progressKey(progress)
        database.transaction {
            val previous = database.coveQueries
                .selectActivityPosition(profileId, key)
                .executeAsOneOrNull() ?: 0.0
            database.coveQueries.upsertActivityPosition(profileId, key, progress.positionSeconds)
            val delta = progress.positionSeconds - previous
            if (delta <= 0.0 || delta > MAX_CREDIT_SECONDS) return@transaction

            val at = parseInstant(progress.watchedAt) ?: clock.instant()
            val local = at.atZone(zoneId)
            val seconds = delta.toLong()
            if (seconds <= 0) return@transaction
            addHour(
                profileId,
                local.toLocalDate().toString(),
                local.hour.toLong(),
                seconds,
            )
            addTitle(
                profileId,
                local.toLocalDate().toString(),
                "${progress.tmdbId}:${progress.mediaType.wireName}",
                seconds,
            )
        }
    }

    fun stats(): ActivityStats {
        val profileId = session.profileId.value
        ensureInitialized(profileId)
        val today = LocalDate.now(clock.withZone(zoneId))
        val thisYear = today.year
        val lastYear = thisYear - 1
        val byYear = linkedMapOf<String, Long>()
        val calendar = linkedMapOf<String, Long>()
        val byMonthThisYear = MutableList(12) { 0L }
        val byMonthLastYear = MutableList(12) { 0L }
        val byDayOfWeek = MutableList(7) { 0L }
        val byHourOfDay = MutableList(24) { 0L }

        database.coveQueries.selectActivityHours(profileId).executeAsList().forEach { row ->
            val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@forEach
            val seconds = row.seconds
            calendar[row.date] = (calendar[row.date] ?: 0L) + seconds
            byYear[date.year.toString()] = (byYear[date.year.toString()] ?: 0L) + seconds
            byDayOfWeek[date.dayOfWeek.value % 7] += seconds // response index 0 is Sunday
            val hour = row.hour.toInt()
            if (hour in byHourOfDay.indices) byHourOfDay[hour] += seconds
            when (date.year) {
                thisYear -> byMonthThisYear[date.monthValue - 1] += seconds
                lastYear -> byMonthLastYear[date.monthValue - 1] += seconds
            }
        }

        val titlesThisYear = linkedMapOf<String, Long>()
        val allTitles = linkedSetOf<String>()
        database.coveQueries.selectActivityTitles(profileId).executeAsList().forEach { row ->
            allTitles += row.title_key
            val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@forEach
            if (date.year == thisYear) {
                titlesThisYear[row.title_key] = (titlesThisYear[row.title_key] ?: 0L) + row.seconds
            }
        }

        val entryByKey = database.coveQueries.selectLibraryEntries(profileId).executeAsList()
            .associateBy { "${it.tmdb_id}:${it.media_type}" }
        val topTitles = titlesThisYear.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(20)
            .mapNotNull { (key, seconds) ->
                val separator = key.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val id = key.substring(0, separator).toIntOrNull() ?: return@mapNotNull null
                val entry = entryByKey[key]
                ActivityTitle(
                    tmdbId = id,
                    mediaType = key.substring(separator + 1),
                    seconds = seconds,
                    title = entry?.title.orEmpty(),
                    posterPath = entry?.poster_path.orEmpty(),
                )
            }

        val activeDays = calendar.filterValues { it > 0 }.keys.sorted()
        val total = calendar.values.sum()
        return ActivityStats(
            totalSeconds = total,
            totalTitles = allTitles.size,
            currentStreak = currentStreak(activeDays, today),
            longestStreak = longestStreak(activeDays),
            avgSecondsPerActiveDay = if (activeDays.isEmpty()) 0 else total / activeDays.size,
            titlesThisYear = titlesThisYear.size,
            thisYearSeconds = byYear[thisYear.toString()] ?: 0,
            lastYearSeconds = byYear[lastYear.toString()] ?: 0,
            byYear = byYear,
            byMonthThisYear = byMonthThisYear,
            byMonthLastYear = byMonthLastYear,
            byDayOfWeek = byDayOfWeek,
            byHourOfDay = byHourOfDay,
            calendar = calendar.filterValues { it > 0 },
            titlesWatchedThisYear = topTitles,
        )
    }

    fun snapshotJson(profileId: String = session.profileId.value): String {
        ensureInitialized(profileId)
        return CoveJson.encodeToString(snapshot(profileId))
    }

    fun mergeFromJson(json: String, profileId: String = session.profileId.value) {
        val remote = CoveJson.decodeFromString<ActivityDiskStore>(json)
        ensureInitialized(profileId)
        database.transaction {
            remote.days.forEach { (date, day) ->
                day.byHour.take(24).forEachIndexed { hour, seconds ->
                    if (seconds > 0) maxHour(
                        profileId, date, hour.toLong(), seconds,
                    )
                }
                day.byTitle.forEach { (key, seconds) ->
                    if (seconds > 0) maxTitle(profileId, date, key, seconds)
                }
            }
            remote.lastPos.forEach { (key, position) ->
                maxPosition(profileId, key, position)
            }
            val state = database.coveQueries.selectActivityState(profileId).executeAsOne()
            database.coveQueries.upsertActivityState(
                profileId,
                if (state.backfilled != 0L || remote.backfilled) 1 else 0,
                1,
            )
        }
    }

    private fun ensureInitialized(profileId: String) {
        val existing = database.coveQueries.selectActivityState(profileId).executeAsOneOrNull()
        if (existing?.backfilled == 1L && existing.legacy_imported == 1L) return

        database.transaction {
            var state = database.coveQueries.selectActivityState(profileId).executeAsOneOrNull()
            if (state?.legacy_imported != 1L) {
                database.coveQueries.selectLegacyPayloadRecord(profileId, "activity")
                    .executeAsOneOrNull()
                    ?.json
                    ?.let { raw ->
                        runCatching { CoveJson.decodeFromString<ActivityDiskStore>(raw) }
                            .getOrNull()
                            ?.let { importSnapshot(profileId, it) }
                    }
                val importedBackfilled = database.coveQueries.selectLegacyPayloadRecord(profileId, "activity")
                    .executeAsOneOrNull()
                    ?.json
                    ?.let { runCatching { CoveJson.decodeFromString<ActivityDiskStore>(it).backfilled }.getOrNull() }
                    ?: false
                database.coveQueries.upsertActivityState(
                    profileId,
                    if (state?.backfilled == 1L || importedBackfilled) 1 else 0,
                    1,
                )
                state = database.coveQueries.selectActivityState(profileId).executeAsOne()
            }
            if (state.backfilled != 1L) {
                database.coveQueries.selectWatchProgress(profileId).executeAsList().forEach { progress ->
                    val seconds = if (progress.completed != 0L) {
                        progress.duration_seconds
                    } else {
                        progress.position_seconds
                    }.toLong()
                    if (seconds <= 0) return@forEach
                    val at = parseInstant(progress.watched_at) ?: clock.instant()
                    val local = at.atZone(zoneId)
                    val key = buildString {
                        append(progress.tmdb_id).append(':').append(progress.media_type)
                        if (progress.season != null && progress.episode != null) {
                            append(':').append(progress.season).append(':').append(progress.episode)
                        }
                    }
                    maxPosition(profileId, key, progress.position_seconds)
                    addHour(
                        profileId, local.toLocalDate().toString(), local.hour.toLong(), seconds,
                    )
                    addTitle(
                        profileId,
                        local.toLocalDate().toString(),
                        "${progress.tmdb_id}:${progress.media_type}",
                        seconds,
                    )
                }
                database.coveQueries.upsertActivityState(profileId, 1, 1)
            }
        }
    }

    private fun importSnapshot(profileId: String, store: ActivityDiskStore) {
        store.days.forEach { (date, day) ->
            day.byHour.take(24).forEachIndexed { hour, seconds ->
                if (seconds > 0) maxHour(profileId, date, hour.toLong(), seconds)
            }
            day.byTitle.forEach { (key, seconds) ->
                if (seconds > 0) maxTitle(profileId, date, key, seconds)
            }
        }
        store.lastPos.forEach { (key, position) ->
            maxPosition(profileId, key, position)
        }
    }

    private fun snapshot(profileId: String): ActivityDiskStore {
        val days = linkedMapOf<String, MutableActivityDay>()
        database.coveQueries.selectActivityHours(profileId).executeAsList().forEach { row ->
            val day = days.getOrPut(row.date) { MutableActivityDay() }
            val hour = row.hour.toInt()
            if (hour in day.byHour.indices) day.byHour[hour] = row.seconds
        }
        database.coveQueries.selectActivityTitles(profileId).executeAsList().forEach { row ->
            days.getOrPut(row.date) { MutableActivityDay() }.byTitle[row.title_key] = row.seconds
        }
        val state = database.coveQueries.selectActivityState(profileId).executeAsOneOrNull()
        return ActivityDiskStore(
            days = days.mapValues { (_, day) -> ActivityDay(day.byHour, day.byTitle) },
            lastPos = database.coveQueries.selectActivityPositions(profileId).executeAsList()
                .associate { it.progress_key to it.position },
            backfilled = state?.backfilled == 1L,
        )
    }

    private fun addHour(profileId: String, date: String, hour: Long, seconds: Long) {
        val existing = database.coveQueries.selectActivityHour(profileId, date, hour)
            .executeAsOneOrNull() ?: 0L
        database.coveQueries.upsertActivityHour(profileId, date, hour, existing + seconds)
    }

    private fun maxHour(profileId: String, date: String, hour: Long, seconds: Long) {
        val existing = database.coveQueries.selectActivityHour(profileId, date, hour)
            .executeAsOneOrNull() ?: 0L
        if (seconds > existing) database.coveQueries.upsertActivityHour(profileId, date, hour, seconds)
    }

    private fun addTitle(profileId: String, date: String, key: String, seconds: Long) {
        val existing = database.coveQueries.selectActivityTitle(profileId, date, key)
            .executeAsOneOrNull() ?: 0L
        database.coveQueries.upsertActivityTitle(profileId, date, key, existing + seconds)
    }

    private fun maxTitle(profileId: String, date: String, key: String, seconds: Long) {
        val existing = database.coveQueries.selectActivityTitle(profileId, date, key)
            .executeAsOneOrNull() ?: 0L
        if (seconds > existing) database.coveQueries.upsertActivityTitle(profileId, date, key, seconds)
    }

    private fun maxPosition(profileId: String, key: String, position: Double) {
        val existing = database.coveQueries.selectActivityPosition(profileId, key)
            .executeAsOneOrNull() ?: 0.0
        if (position > existing) database.coveQueries.upsertActivityPosition(profileId, key, position)
    }

    private class MutableActivityDay(
        val byHour: MutableList<Long> = MutableList(24) { 0 },
        val byTitle: MutableMap<String, Long> = linkedMapOf(),
    )

    companion object {
        private const val MAX_CREDIT_SECONDS = 90.0

        private fun progressKey(progress: WatchProgress): String = buildString {
            append(progress.tmdbId).append(':').append(progress.mediaType.wireName)
            if (progress.season != null && progress.episode != null) {
                append(':').append(progress.season).append(':').append(progress.episode)
            }
        }

        private fun parseInstant(value: String): Instant? {
            if (value.isBlank()) return null
            return runCatching { Instant.parse(value) }.getOrElse {
                runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            }
        }

        private fun longestStreak(days: List<String>): Int {
            var longest = 0
            var run = 0
            var previous: LocalDate? = null
            days.forEach { value ->
                val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return@forEach
                run = if (previous?.plusDays(1) == date) run + 1 else 1
                if (run > longest) longest = run
                previous = date
            }
            return longest
        }

        private fun currentStreak(days: List<String>, today: LocalDate): Int {
            val dates = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
            var expected = when {
                today in dates -> today
                today.minusDays(1) in dates -> today.minusDays(1)
                else -> return 0
            }
            var streak = 0
            while (expected in dates) {
                streak++
                expected = expected.minusDays(1)
            }
            return streak
        }
    }
}

@Serializable
data class ActivityTitle(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val seconds: Long,
    val title: String = "",
    @SerialName("poster_path") val posterPath: String = "",
)

@Serializable
data class ActivityStats(
    @SerialName("total_seconds") val totalSeconds: Long = 0,
    @SerialName("total_titles") val totalTitles: Int = 0,
    @SerialName("current_streak") val currentStreak: Int = 0,
    @SerialName("longest_streak") val longestStreak: Int = 0,
    @SerialName("avg_seconds_per_active_day") val avgSecondsPerActiveDay: Long = 0,
    @SerialName("titles_this_year") val titlesThisYear: Int = 0,
    @SerialName("this_year_seconds") val thisYearSeconds: Long = 0,
    @SerialName("last_year_seconds") val lastYearSeconds: Long = 0,
    @SerialName("by_year") val byYear: Map<String, Long> = emptyMap(),
    @SerialName("by_month_this_year") val byMonthThisYear: List<Long> = List(12) { 0 },
    @SerialName("by_month_last_year") val byMonthLastYear: List<Long> = List(12) { 0 },
    @SerialName("by_day_of_week") val byDayOfWeek: List<Long> = List(7) { 0 },
    @SerialName("by_hour_of_day") val byHourOfDay: List<Long> = List(24) { 0 },
    val calendar: Map<String, Long> = emptyMap(),
    @SerialName("titles_watched_this_year") val titlesWatchedThisYear: List<ActivityTitle> = emptyList(),
)

@Serializable
internal data class ActivityDiskStore(
    val days: Map<String, ActivityDay> = emptyMap(),
    @SerialName("last_pos") val lastPos: Map<String, Double> = emptyMap(),
    val backfilled: Boolean = false,
)

@Serializable
internal data class ActivityDay(
    @SerialName("by_hour") val byHour: List<Long> = List(24) { 0 },
    @SerialName("by_title") val byTitle: Map<String, Long> = emptyMap(),
)
