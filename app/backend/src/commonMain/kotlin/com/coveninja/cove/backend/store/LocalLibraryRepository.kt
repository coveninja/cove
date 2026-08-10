package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.db.Library_entries
import com.coveninja.cove.backend.db.Watch_progress
import com.coveninja.cove.shared.data.LibraryRepository
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.LibraryDetailDto
import com.coveninja.cove.shared.network.WatchProgressRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class LocalLibraryRepository(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val now: () -> String,
) : LibraryRepository {
    private val mutation = Mutex()
    private val _entries = MutableStateFlow<LibraryState>(LibraryState.Loading)
    override val entries: StateFlow<LibraryState> = _entries.asStateFlow()

    init {
        reload(session.profileId.value)
        scope.launch { session.profileId.collectLatest { reload(it) } }
    }

    override suspend fun add(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        posterPath: String,
        voteAverage: Double,
    ): Unit = mutate {
        val profileId = session.profileId.value
        val existing = find(profileId, tmdbId, mediaType)
        val timestamp = now()
        val entry = existing?.copy(
            title = title,
            posterPath = posterPath,
            voteAverage = voteAverage,
            updatedAt = timestamp,
        ) ?: LibraryEntry(
            id = newId(),
            profileId = profileId,
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = title,
            posterPath = posterPath,
            status = LibraryStatus.WatchLater,
            voteAverage = voteAverage,
            addedAt = timestamp,
            updatedAt = timestamp,
        )
        upsert(profileId, entry)
        database.coveQueries.deleteRemoval(profileId, tmdbId.toLong(), mediaType.wireName)
    }

    override suspend fun remove(tmdbId: Int, mediaType: MediaType): Unit = mutate {
        val profileId = session.profileId.value
        database.coveQueries.deleteLibraryEntry(profileId, tmdbId.toLong(), mediaType.wireName)
        database.coveQueries.upsertRemoval(profileId, tmdbId.toLong(), mediaType.wireName, now())
    }

    override suspend fun setStatus(tmdbId: Int, mediaType: MediaType, status: LibraryStatus) = mutate {
        updateExisting(tmdbId, mediaType) { it.copy(status = status, updatedAt = now()) }
    }

    override suspend fun setRating(tmdbId: Int, mediaType: MediaType, rating: Double?) = mutate {
        require(rating == null || rating in 0.0..5.0) { "rating must be between 0 and 5" }
        updateExisting(tmdbId, mediaType) { it.copy(rating = rating, updatedAt = now()) }
    }

    override suspend fun setDismissed(tmdbId: Int, mediaType: MediaType, dismissed: Boolean): Unit = mutate {
        val profileId = session.profileId.value
        if (dismissed) {
            database.coveQueries.upsertDismissal(profileId, tmdbId.toLong(), mediaType.wireName, now())
        } else {
            database.coveQueries.deleteDismissal(profileId, tmdbId.toLong(), mediaType.wireName)
        }
    }

    override suspend fun episodeWatchStates(
        tmdbId: Int,
        mediaType: MediaType,
    ): Map<Pair<Int, Int>, Boolean> = database.coveQueries
        .selectWatchProgress(session.profileId.value)
        .executeAsList()
        .asSequence()
        .filter { it.tmdb_id == tmdbId.toLong() && it.media_type == mediaType.wireName }
        .mapNotNull { progress ->
            val season = progress.season?.toInt() ?: return@mapNotNull null
            val episode = progress.episode?.toInt() ?: return@mapNotNull null
            (season to episode) to (progress.completed != 0L)
        }
        .toMap()

    override suspend fun setEpisodeWatched(
        tmdbId: Int,
        title: String,
        posterPath: String,
        voteAverage: Double,
        season: Int,
        episode: Int,
        runtimeMinutes: Int?,
        watched: Boolean,
    ): Unit = mutate {
        require(season >= 0 && episode > 0) { "invalid season or episode" }
        val profileId = session.profileId.value
        var entry = find(profileId, tmdbId, MediaType.Tv)
        if (entry == null) {
            val timestamp = now()
            entry = LibraryEntry(
                id = newId(),
                profileId = profileId,
                tmdbId = tmdbId,
                mediaType = MediaType.Tv,
                title = title,
                posterPath = posterPath,
                status = LibraryStatus.Watching,
                voteAverage = voteAverage,
                addedAt = timestamp,
                updatedAt = timestamp,
            )
        }
        val timestamp = now()
        val duration = runtimeMinutes?.takeIf { it > 0 }?.times(60)?.toDouble() ?: 1.0
        val updatedEntry = entry.copy(
            lastWatchedAt = timestamp,
            lastWatchedSeason = season,
            lastWatchedEpisode = episode,
            updatedAt = timestamp,
        )
        upsert(profileId, updatedEntry)
        val key = "$tmdbId:tv:$season:$episode"
        database.coveQueries.upsertWatchProgress(
            progress_key = key,
            id = database.coveQueries.selectWatchProgress(profileId).executeAsList()
                .firstOrNull { it.progress_key == key }?.id ?: newId(),
            profile_id = profileId,
            library_entry_id = updatedEntry.id,
            tmdb_id = tmdbId.toLong(),
            media_type = MediaType.Tv.wireName,
            season = season.toLong(),
            episode = episode.toLong(),
            position_seconds = if (watched) duration else 0.0,
            duration_seconds = if (watched) duration else 0.0,
            completed = if (watched) 1L else 0L,
            watched_at = timestamp,
        )
    }

    suspend fun detail(tmdbId: Int, mediaType: MediaType): LibraryDetailDto {
        val profileId = session.profileId.value
        return LibraryDetailDto(
            entry = find(profileId, tmdbId, mediaType),
            progress = progressFor(profileId, tmdbId, mediaType),
            dismissed = database.coveQueries.selectDismissals(profileId).executeAsList().any {
                it.tmdb_id == tmdbId.toLong() && it.media_type == mediaType.wireName
            },
        )
    }

    // Defaults live on the LibraryRepository declaration; Kotlin forbids repeating
    // them on an override.
    override suspend fun progress(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int?,
        episode: Int?,
    ): WatchProgress? {
        require(tmdbId > 0) { "tmdb id must be positive" }
        require(season == null || season >= 0) { "season must not be negative" }
        require(episode == null || episode > 0) { "episode must be positive" }
        val key = progressKey(tmdbId, mediaType, season, episode)
        return database.coveQueries.selectWatchProgress(session.profileId.value).executeAsList()
            .firstOrNull { it.progress_key == key }
            ?.toModel()
    }

    // selectWatchProgress is already ordered newest-first, so the contract's ordering
    // costs nothing here.
    override suspend fun progressSnapshot(): List<WatchProgress> =
        database.coveQueries.selectWatchProgress(session.profileId.value)
            .executeAsList()
            .map(Watch_progress::toModel)

    override suspend fun recordProgress(request: WatchProgressRequest): WatchProgress = mutate {
        require(request.tmdbId > 0) { "tmdb id must be positive" }
        require(request.positionSeconds >= 0 && request.durationSeconds >= 0) {
            "position and duration must be non-negative"
        }
        require(request.season?.let { it >= 0 } != false) { "season must not be negative" }
        require(request.episode?.let { it > 0 } != false) { "episode must be positive" }

        val profileId = session.profileId.value
        val timestamp = now()
        val existing = find(profileId, request.tmdbId, request.mediaType)
        val entry = (existing ?: LibraryEntry(
            id = newId(),
            profileId = profileId,
            tmdbId = request.tmdbId,
            mediaType = request.mediaType,
            title = request.title,
            posterPath = request.posterPath,
            status = LibraryStatus.Watching,
            voteAverage = request.voteAverage,
            addedAt = timestamp,
            updatedAt = timestamp,
        )).copy(
            lastAirDate = request.lastAirDate.takeIf(String::isNotBlank) ?: existing?.lastAirDate.orEmpty(),
            lastWatchedAt = timestamp,
            lastWatchedSeason = request.season ?: existing?.lastWatchedSeason,
            lastWatchedEpisode = request.episode ?: existing?.lastWatchedEpisode,
            lastAiredSeason = request.lastAiredSeason ?: existing?.lastAiredSeason,
            lastAiredEpisode = request.lastAiredEpisode ?: existing?.lastAiredEpisode,
            updatedAt = timestamp,
        )
        upsert(profileId, entry)
        database.coveQueries.deleteRemoval(profileId, request.tmdbId.toLong(), request.mediaType.wireName)

        val key = progressKey(request.tmdbId, request.mediaType, request.season, request.episode)
        val existingProgress = database.coveQueries.selectWatchProgress(profileId).executeAsList()
            .firstOrNull { it.progress_key == key }
        val progress = WatchProgress(
            id = existingProgress?.id ?: newId(),
            profileId = profileId,
            libraryEntryId = entry.id,
            tmdbId = request.tmdbId,
            mediaType = request.mediaType,
            season = request.season,
            episode = request.episode,
            positionSeconds = request.positionSeconds,
            durationSeconds = request.durationSeconds,
            completed = request.completed,
            watchedAt = timestamp,
        )
        database.coveQueries.upsertWatchProgress(
            progress_key = key,
            id = progress.id,
            profile_id = profileId,
            library_entry_id = entry.id,
            tmdb_id = request.tmdbId.toLong(),
            media_type = request.mediaType.wireName,
            season = request.season?.toLong(),
            episode = request.episode?.toLong(),
            position_seconds = request.positionSeconds,
            duration_seconds = request.durationSeconds,
            completed = if (request.completed) 1L else 0L,
            watched_at = timestamp,
        )
        progress
    }

    internal fun snapshotForSync(profileId: String = session.profileId.value): LibrarySyncSnapshot =
        LibrarySyncSnapshot(
            entries = database.coveQueries.selectLibraryEntries(profileId).executeAsList().map { it.toModel() },
            progress = database.coveQueries.selectWatchProgress(profileId).executeAsList().map { it.toModel() },
            dismissals = database.coveQueries.selectDismissals(profileId).executeAsList().map {
                SyncDismissal(it.tmdb_id.toInt(), it.media_type, it.dismissed_at)
            },
            removals = database.coveQueries.selectRemovals(profileId).executeAsList().map {
                SyncRemoval(it.tmdb_id.toInt(), it.media_type, it.removed_at)
            },
        )

    internal suspend fun mergeFromRemote(remote: LibrarySyncSnapshot) = mutate {
        val profileId = session.profileId.value
        val localEntries = database.coveQueries.selectLibraryEntries(profileId).executeAsList()
            .associateBy { "${it.tmdb_id}:${it.media_type}" }
        val localRemovals = database.coveQueries.selectRemovals(profileId).executeAsList()
            .associateBy { "${it.tmdb_id}:${it.media_type}" }
        val remoteRemovals = remote.removals.associateBy { "${it.tmdbId}:${it.mediaType}" }

        remote.entries.forEach { entry ->
            val key = "${entry.tmdbId}:${entry.mediaType.wireName}"
            val tombstoneAt = listOfNotNull(
                localRemovals[key]?.removed_at,
                remoteRemovals[key]?.removedAt,
            ).maxOrNull()
            if (tombstoneAt != null && entry.updatedAt <= tombstoneAt) return@forEach
            if (tombstoneAt != null) {
                database.coveQueries.deleteRemoval(profileId, entry.tmdbId.toLong(), entry.mediaType.wireName)
            }
            val local = localEntries[key]
            if (local == null || entry.updatedAt > local.updated_at) upsert(profileId, entry)
        }

        remote.removals.forEach { removal ->
            val type = MediaType.entries.firstOrNull { it.wireName == removal.mediaType }
                ?: return@forEach
            val local = database.coveQueries.selectLibraryEntry(
                profileId,
                removal.tmdbId.toLong(),
                type.wireName,
            ).executeAsOneOrNull()
            if (local == null || local.updated_at <= removal.removedAt) {
                database.coveQueries.deleteLibraryEntry(profileId, removal.tmdbId.toLong(), type.wireName)
                val old = localRemovals["${removal.tmdbId}:${removal.mediaType}"]
                if (old == null || removal.removedAt > old.removed_at) {
                    database.coveQueries.upsertRemoval(
                        profileId,
                        removal.tmdbId.toLong(),
                        removal.mediaType,
                        removal.removedAt,
                    )
                }
            } else {
                database.coveQueries.deleteRemoval(profileId, removal.tmdbId.toLong(), type.wireName)
            }
        }

        val localProgress = database.coveQueries.selectWatchProgress(profileId).executeAsList()
            .associateBy { it.progress_key }
        remote.progress.forEach { progress ->
            val key = progressKey(progress.tmdbId, progress.mediaType, progress.season, progress.episode)
            val local = localProgress[key]
            if (local == null || progress.watchedAt > local.watched_at) {
                database.coveQueries.upsertWatchProgress(
                    key,
                    progress.id,
                    profileId,
                    progress.libraryEntryId,
                    progress.tmdbId.toLong(),
                    progress.mediaType.wireName,
                    progress.season?.toLong(),
                    progress.episode?.toLong(),
                    progress.positionSeconds,
                    progress.durationSeconds,
                    if (progress.completed) 1L else 0L,
                    progress.watchedAt,
                )
            }
        }
        val localDismissals = database.coveQueries.selectDismissals(profileId).executeAsList()
            .mapTo(mutableSetOf()) { "${it.tmdb_id}:${it.media_type}" }
        remote.dismissals.forEach { dismissal ->
            if (localDismissals.add("${dismissal.tmdbId}:${dismissal.mediaType}")) {
                database.coveQueries.upsertDismissal(
                    profileId,
                    dismissal.tmdbId.toLong(),
                    dismissal.mediaType,
                    dismissal.dismissedAt,
                )
            }
        }
    }

    /** Additive Trakt pull: newer completions win and watchlist rows never overwrite local status. */
    internal suspend fun applyExternal(
        history: List<ExternalHistoryItem>,
        watchlist: List<ExternalWatchlistItem>,
    ) = mutate {
        val profileId = session.profileId.value
        history.forEach { item ->
            val existing = find(profileId, item.tmdbId, item.mediaType)
            val timestamp = now()
            val entry = existing ?: LibraryEntry(
                id = newId(),
                profileId = profileId,
                tmdbId = item.tmdbId,
                mediaType = item.mediaType,
                title = item.title,
                posterPath = item.posterPath,
                status = LibraryStatus.Watching,
                addedAt = timestamp,
                updatedAt = timestamp,
            )
            val key = progressKey(item.tmdbId, item.mediaType, item.season, item.episode)
            val prior = database.coveQueries.selectWatchProgress(profileId).executeAsList()
                .firstOrNull { it.progress_key == key }
            if (prior != null && prior.completed != 0L && prior.watched_at >= item.watchedAt) {
                if (existing == null) upsert(profileId, entry)
                return@forEach
            }
            val updatedEntry = entry.copy(
                title = entry.title.ifBlank { item.title },
                posterPath = entry.posterPath.ifBlank { item.posterPath },
                lastWatchedAt = item.watchedAt,
                lastWatchedSeason = item.season ?: entry.lastWatchedSeason,
                lastWatchedEpisode = item.episode ?: entry.lastWatchedEpisode,
                updatedAt = timestamp,
            )
            upsert(profileId, updatedEntry)
            database.coveQueries.deleteRemoval(profileId, item.tmdbId.toLong(), item.mediaType.wireName)
            database.coveQueries.upsertWatchProgress(
                key,
                prior?.id ?: newId(),
                profileId,
                updatedEntry.id,
                item.tmdbId.toLong(),
                item.mediaType.wireName,
                item.season?.toLong(),
                item.episode?.toLong(),
                prior?.position_seconds ?: 0.0,
                prior?.duration_seconds ?: 0.0,
                1,
                item.watchedAt,
            )
        }
        watchlist.forEach { item ->
            if (find(profileId, item.tmdbId, item.mediaType) != null) return@forEach
            val entry = LibraryEntry(
                id = newId(),
                profileId = profileId,
                tmdbId = item.tmdbId,
                mediaType = item.mediaType,
                title = item.title,
                posterPath = item.posterPath,
                status = LibraryStatus.WatchLater,
                addedAt = item.listedAt,
                updatedAt = now(),
            )
            upsert(profileId, entry)
            database.coveQueries.deleteRemoval(profileId, item.tmdbId.toLong(), item.mediaType.wireName)
            database.coveQueries.selectWatchProgress(profileId).executeAsList()
                .filter { it.tmdb_id == item.tmdbId.toLong() && it.media_type == item.mediaType.wireName }
                .forEach { progress ->
                    database.coveQueries.upsertWatchProgress(
                        progress.progress_key,
                        progress.id,
                        profileId,
                        entry.id,
                        progress.tmdb_id,
                        progress.media_type,
                        progress.season,
                        progress.episode,
                        progress.position_seconds,
                        progress.duration_seconds,
                        progress.completed,
                        progress.watched_at,
                    )
                }
        }
    }

    fun stats(): LibraryStats {
        val profileId = session.profileId.value
        val entries = database.coveQueries.selectLibraryEntries(profileId).executeAsList()
        val byType = mutableMapOf("movie" to 0, "tv" to 0)
        val byStatus = mutableMapOf<String, Int>()
        val finished = mutableMapOf("movie" to 0, "tv" to 0)
        val engaged = mutableMapOf("movie" to 0, "tv" to 0)
        var ratingTotal = 0.0
        var rated = 0
        entries.forEach { entry ->
            byType[entry.media_type] = byType.getValue(entry.media_type) + 1
            byStatus[entry.status] = (byStatus[entry.status] ?: 0) + 1
            if (entry.status == LibraryStatus.Finished.wireName) {
                finished[entry.media_type] = finished.getValue(entry.media_type) + 1
            }
            if (entry.status == LibraryStatus.Finished.wireName || entry.status == LibraryStatus.Watching.wireName) {
                engaged[entry.media_type] = engaged.getValue(entry.media_type) + 1
            }
            entry.rating?.let { ratingTotal += it; rated++ }
        }
        val engagedTotal = engaged.values.sum()
        return LibraryStats(
            total = entries.size,
            byType = byType,
            byStatus = byStatus,
            finished = finished,
            dismissed = database.coveQueries.selectDismissals(profileId).executeAsList().size,
            rated = rated,
            avgRating = if (rated == 0) 0.0 else ratingTotal / rated,
            movieShare = if (engagedTotal == 0) 0.0 else engaged.getValue("movie").toDouble() / engagedTotal,
            tvShare = if (engagedTotal == 0) 0.0 else engaged.getValue("tv").toDouble() / engagedTotal,
        )
    }

    suspend fun recordProgressBulk(request: BulkProgressRequest): LibraryDetailDto = mutate {
        require(request.tmdbId > 0) { "tmdb id must be positive" }
        require(request.durationSeconds >= 0) { "duration must be non-negative" }
        require(request.episodes.size <= 5_000) { "too many episodes" }
        val distinctEpisodes = request.episodes.distinctBy { it.season to it.episode }
        require(distinctEpisodes.all {
            (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 && it.durationSeconds >= 0
        }) {
            "episodes require positive season/episode and non-negative duration"
        }
        require(request.mediaType != MediaType.Tv || !request.completed || distinctEpisodes.isNotEmpty()) {
            "at least one aired episode is required"
        }

        val profileId = session.profileId.value
        val timestamp = now()
        var entry = find(profileId, request.tmdbId, request.mediaType)
        if (entry == null && request.completed) {
            entry = LibraryEntry(
                id = newId(),
                profileId = profileId,
                tmdbId = request.tmdbId,
                mediaType = request.mediaType,
                title = request.title,
                posterPath = request.posterPath,
                status = request.status ?: LibraryStatus.Watching,
                voteAverage = request.voteAverage,
                addedAt = timestamp,
                updatedAt = timestamp,
            )
        }
        val currentEntry = entry
        entry = currentEntry?.copy(
            title = request.title.ifBlank { currentEntry.title },
            posterPath = request.posterPath.ifBlank { currentEntry.posterPath },
            voteAverage = request.voteAverage,
            status = request.status ?: currentEntry.status,
            lastWatchedAt = if (request.completed) timestamp else null,
            updatedAt = timestamp,
        )
        if (request.completed && request.mediaType == MediaType.Tv) {
            val last = distinctEpisodes.maxWith(
                compareBy<BulkEpisode> { it.season ?: 0 }.thenBy { it.episode ?: 0 },
            )
            entry = requireNotNull(entry).copy(
                lastWatchedSeason = requireNotNull(last.season),
                lastWatchedEpisode = requireNotNull(last.episode),
            )
        } else if (!request.completed) {
            entry = entry?.copy(lastWatchedSeason = null, lastWatchedEpisode = null)
        }
        entry?.let { upsert(profileId, it) }

        val existing = database.coveQueries.selectWatchProgress(profileId).executeAsList()
        if (request.completed) {
            val inputs = if (request.mediaType == MediaType.Movie) {
                listOf(BulkEpisode(null, null, request.durationSeconds))
            } else {
                distinctEpisodes
            }
            inputs.forEach { input ->
                val key = progressKey(request.tmdbId, request.mediaType, input.season, input.episode)
                val prior = existing.firstOrNull { it.progress_key == key }
                val duration = input.durationSeconds.takeIf { it > 0 } ?: 1.0
                database.coveQueries.upsertWatchProgress(
                    key,
                    prior?.id ?: newId(),
                    profileId,
                    requireNotNull(entry).id,
                    request.tmdbId.toLong(),
                    request.mediaType.wireName,
                    input.season?.toLong(),
                    input.episode?.toLong(),
                    duration,
                    duration,
                    1,
                    timestamp,
                )
            }
            database.coveQueries.deleteRemoval(profileId, request.tmdbId.toLong(), request.mediaType.wireName)
        } else {
            existing.filter {
                it.tmdb_id == request.tmdbId.toLong() && it.media_type == request.mediaType.wireName
            }.forEach { progress ->
                database.coveQueries.upsertWatchProgress(
                    progress.progress_key,
                    progress.id,
                    profileId,
                    entry?.id ?: progress.library_entry_id,
                    progress.tmdb_id,
                    progress.media_type,
                    progress.season,
                    progress.episode,
                    0.0,
                    0.0,
                    0,
                    timestamp,
                )
            }
        }
        LibraryDetailDto(
            entry = entry,
            progress = database.coveQueries.selectWatchProgress(profileId).executeAsList()
                .filter { it.tmdb_id == request.tmdbId.toLong() && it.media_type == request.mediaType.wireName }
                .map { it.toModel() },
        )
    }

    private suspend fun <T> mutate(block: () -> T): T = mutation.withLock {
        try {
            val result = database.transactionWithResult { block() }
            reload(session.profileId.value)
            result
        } catch (error: Exception) {
            _entries.value = LibraryState.Failed(error.message ?: "Unknown library error")
            throw error
        }
    }

    private fun updateExisting(
        tmdbId: Int,
        mediaType: MediaType,
        update: (LibraryEntry) -> LibraryEntry,
    ) {
        val profileId = session.profileId.value
        val existing = find(profileId, tmdbId, mediaType)
            ?: error("library entry does not exist")
        upsert(profileId, update(existing))
    }

    private fun find(profileId: String, tmdbId: Int, mediaType: MediaType): LibraryEntry? =
        database.coveQueries.selectLibraryEntry(profileId, tmdbId.toLong(), mediaType.wireName)
            .executeAsOneOrNull()?.toModel()

    private fun reload(profileId: String) {
        _entries.value = try {
            LibraryState.Ready(database.coveQueries.selectLibraryEntries(profileId).executeAsList().map { it.toModel() })
        } catch (error: Exception) {
            LibraryState.Failed(error.message ?: "Unknown error loading library")
        }
    }

    private fun progressFor(profileId: String, tmdbId: Int, mediaType: MediaType): List<WatchProgress> =
        database.coveQueries.selectWatchProgress(profileId).executeAsList()
            .filter { it.tmdb_id == tmdbId.toLong() && it.media_type == mediaType.wireName }
            .map(Watch_progress::toModel)

    private fun upsert(profileId: String, entry: LibraryEntry) {
        database.coveQueries.upsertLibraryEntry(
            entry.id,
            profileId,
            entry.tmdbId.toLong(),
            entry.mediaType.wireName,
            entry.title,
            entry.posterPath,
            entry.status.wireName,
            entry.rating,
            entry.voteAverage,
            entry.lastAirDate,
            entry.lastWatchedAt,
            entry.lastWatchedSeason?.toLong(),
            entry.lastWatchedEpisode?.toLong(),
            entry.lastAiredSeason?.toLong(),
            entry.lastAiredEpisode?.toLong(),
            entry.addedAt,
            entry.updatedAt,
        )
    }
}

internal data class LibrarySyncSnapshot(
    val entries: List<LibraryEntry> = emptyList(),
    val progress: List<WatchProgress> = emptyList(),
    val dismissals: List<SyncDismissal> = emptyList(),
    val removals: List<SyncRemoval> = emptyList(),
)

internal data class SyncDismissal(
    val tmdbId: Int,
    val mediaType: String,
    val dismissedAt: String,
)

internal data class SyncRemoval(
    val tmdbId: Int,
    val mediaType: String,
    val removedAt: String,
)

internal data class ExternalHistoryItem(
    val tmdbId: Int,
    val mediaType: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String = "",
    val posterPath: String = "",
    val watchedAt: String,
)

internal data class ExternalWatchlistItem(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String = "",
    val posterPath: String = "",
    val listedAt: String,
)

@Serializable
data class LibraryStats(
    val total: Int,
    @SerialName("by_type") val byType: Map<String, Int>,
    @SerialName("by_status") val byStatus: Map<String, Int>,
    val finished: Map<String, Int>,
    val dismissed: Int,
    val rated: Int,
    @SerialName("avg_rating") val avgRating: Double,
    @SerialName("movie_share") val movieShare: Double,
    @SerialName("tv_share") val tvShare: Double,
)

@Serializable
data class BulkProgressRequest(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: MediaType,
    val title: String = "",
    @SerialName("poster_path") val posterPath: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val completed: Boolean,
    val status: LibraryStatus? = null,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    val episodes: List<BulkEpisode> = emptyList(),
)

@Serializable
data class BulkEpisode(
    val season: Int?,
    val episode: Int?,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
)

private fun progressKey(tmdbId: Int, mediaType: MediaType, season: Int?, episode: Int?): String =
    if (season != null && episode != null) {
        "$tmdbId:${mediaType.wireName}:$season:$episode"
    } else {
        "$tmdbId:${mediaType.wireName}"
    }

private fun Library_entries.toModel(): LibraryEntry = LibraryEntry(
    id = id,
    profileId = profile_id,
    tmdbId = tmdb_id.toInt(),
    mediaType = MediaType.entries.first { it.wireName == media_type },
    title = title,
    posterPath = poster_path,
    status = LibraryStatus.entries.first { it.wireName == status },
    rating = rating,
    voteAverage = vote_average,
    lastAirDate = last_air_date,
    lastWatchedAt = last_watched_at,
    lastWatchedSeason = last_watched_season?.toInt(),
    lastWatchedEpisode = last_watched_episode?.toInt(),
    lastAiredSeason = last_aired_season?.toInt(),
    lastAiredEpisode = last_aired_episode?.toInt(),
    addedAt = added_at,
    updatedAt = updated_at,
)

private fun Watch_progress.toModel(): WatchProgress = WatchProgress(
    id = id,
    profileId = profile_id,
    libraryEntryId = library_entry_id,
    tmdbId = tmdb_id.toInt(),
    mediaType = MediaType.entries.first { it.wireName == media_type },
    season = season?.toInt(),
    episode = episode?.toInt(),
    positionSeconds = position_seconds,
    durationSeconds = duration_seconds,
    completed = completed != 0L,
    watchedAt = watched_at,
)
