package com.coveninja.cove.backend.prefetch

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Warms provider and scraper caches for the titles most likely to play next. */
class PrefetchService(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val settings: LocalSettingsRepository,
    private val catalog: TmdbClient,
    private val addons: AddonManager,
    private val nuvio: NuvioManager,
    private val scope: CoroutineScope,
) {
    private val notifications = Channel<Unit>(Channel.CONFLATED)
    private val cycleMutex = Mutex()

    init {
        scope.launch {
            delay(STARTUP_DELAY_MILLIS)
            while (isActive) {
                runCatching { runCycle() }
                delay(INTERVAL_MILLIS)
            }
        }
        scope.launch {
            for (ignored in notifications) {
                delay(DEBOUNCE_MILLIS)
                while (notifications.tryReceive().isSuccess) {
                    // Drain progress ticks that arrived during the debounce window.
                }
                runCatching { runCycle() }
            }
        }
    }

    fun notifyProgressChanged() {
        notifications.trySend(Unit)
    }

    internal suspend fun runCycle() = cycleMutex.withLock {
        val currentSettings = settings.current()
        if (!currentSettings.prefetchStreams) return@withLock
        val candidates = buildCandidates(currentSettings.prefetchNextEpisode)
        candidates.forEachIndexed { index, candidate ->
            if (!scope.isActive) return@withLock
            withTimeoutOrNull(PER_CANDIDATE_TIMEOUT_MILLIS) { warm(candidate) }
            if (index < candidates.lastIndex) delay(CANDIDATE_SPACING_MILLIS)
        }
    }

    private suspend fun buildCandidates(prefetchNextEpisode: Boolean): List<PrefetchCandidate> {
        val profileId = session.profileId.value
        val progress = database.coveQueries.selectWatchProgress(profileId).executeAsList()
            .groupBy { "${it.media_type}:${it.tmdb_id}" }
            .mapValues { (_, rows) -> rows.maxBy { it.watched_at } }
        val candidates = mutableListOf<PrefetchCandidate>()
        database.coveQueries.selectLibraryEntries(profileId).executeAsList()
            .asSequence()
            .filter { it.status == "watching" }
            .forEach { entry ->
                val type = if (entry.media_type == "tv") MediaType.Tv else MediaType.Movie
                val latest = progress["${entry.media_type}:${entry.tmdb_id}"]
                val fraction = latest?.let {
                    if (it.duration_seconds <= 0) 0.0 else it.position_seconds / it.duration_seconds
                } ?: 0.0
                val target = when {
                    type == MediaType.Movie && latest != null && (latest.completed != 0L || fraction >= 0.9) -> null
                    type == MediaType.Movie -> PrefetchCandidate(entry.tmdb_id.toInt(), type)
                    latest != null && latest.completed == 0L && fraction < 0.9 -> {
                        val season = latest.season?.toInt()
                        val episode = latest.episode?.toInt()
                        if (season == null || episode == null) null
                        else PrefetchCandidate(entry.tmdb_id.toInt(), type, season, episode)
                    }
                    !prefetchNextEpisode -> null
                    latest?.season != null && latest.episode != null -> nextEpisode(
                        entry.tmdb_id.toInt(),
                        latest.season.toInt(),
                        latest.episode.toInt(),
                    )
                    entry.last_watched_season != null && entry.last_watched_episode != null -> nextEpisode(
                        entry.tmdb_id.toInt(),
                        entry.last_watched_season.toInt(),
                        entry.last_watched_episode.toInt(),
                    )
                    else -> PrefetchCandidate(entry.tmdb_id.toInt(), type, 1, 1)
                }
                if (target != null) candidates += target.copy(
                    touchedAt = maxOf(entry.updated_at, entry.last_watched_at.orEmpty(), latest?.watched_at.orEmpty()),
                )
            }
        return candidates.sortedByDescending(PrefetchCandidate::touchedAt).take(MAX_CANDIDATES)
    }

    private suspend fun nextEpisode(tmdbId: Int, season: Int, episode: Int): PrefetchCandidate? {
        val withinSeason = runCatching { catalog.episodes(tmdbId, season) }.getOrDefault(emptyList())
            .map { it.episodeNumber }.filter { it > episode }.minOrNull()
        if (withinSeason != null) return PrefetchCandidate(tmdbId, MediaType.Tv, season, withinSeason)
        val nextSeason = runCatching { catalog.episodes(tmdbId, season + 1) }.getOrDefault(emptyList())
            .map { it.episodeNumber }.filter { it > 0 }.minOrNull()
            ?: return null
        return PrefetchCandidate(tmdbId, MediaType.Tv, season + 1, nextSeason)
    }

    private suspend fun warm(candidate: PrefetchCandidate) {
        val imdbId = runCatching { catalog.imdbId(candidate.tmdbId, candidate.type) }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: return
        val stremioId = if (candidate.type == MediaType.Tv) {
            "$imdbId:${candidate.season}:${candidate.episode}"
        } else imdbId
        coroutineScope {
            val addonWarm = async { runCatching { addons.streams(candidate.type, stremioId) } }
            val nuvioWarm = async {
                runCatching {
                    val media = runCatching { catalog.media(candidate.tmdbId, candidate.type) }.getOrNull()
                    nuvio.streams(
                        candidate.type,
                        candidate.tmdbId,
                        imdbId,
                        media?.displayTitle.orEmpty(),
                        media?.displayDate?.take(4)?.toIntOrNull() ?: 0,
                        candidate.season,
                        candidate.episode,
                    )
                }
            }
            addonWarm.await()
            nuvioWarm.await()
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 10
        const val STARTUP_DELAY_MILLIS = 30_000L
        const val INTERVAL_MILLIS = 10 * 60_000L
        const val DEBOUNCE_MILLIS = 15_000L
        const val PER_CANDIDATE_TIMEOUT_MILLIS = 30_000L
        const val CANDIDATE_SPACING_MILLIS = 2_000L
    }
}

internal data class PrefetchCandidate(
    val tmdbId: Int,
    val type: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val touchedAt: String = "",
)
