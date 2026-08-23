package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.toDomainType
import kotlin.math.roundToLong

/**
 * The primary action advertised by a title's details screen.
 *
 * This is derived from the same library fields used by [PlaybackSession]: a series resumes
 * its unfinished episode, advances after a completed one, and only shows a clock when the
 * resolved target has a usable resume point. Keeping that decision outside the composable
 * makes the label testable and prevents it from promising a different episode than Watch
 * will actually open.
 */
data class MediaWatchAction(
    val label: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionSeconds: Double? = null,
)

fun mediaWatchAction(
    media: Media,
    entry: LibraryEntry?,
    progressRows: List<WatchProgress>,
): MediaWatchAction {
    val domainType = media.type.toDomainType() ?: return MediaWatchAction(label = "Watch")
    val titleProgress = progressRows.filter { row ->
        row.tmdbId == media.tmdbId && row.mediaType == domainType
    }

    if (media.type != MediaType.Series) {
        val progress = titleProgress.latestProgress()
        val position = resumablePositionSeconds(progress)
        return MediaWatchAction(
            label = when {
                position != null -> "Continue ${formatWatchPosition(position)}"
                progress?.completed == true -> "Watch Again"
                else -> "Watch"
            },
            positionSeconds = position,
        )
    }

    val lastSeason = entry?.lastWatchedSeason
    val lastEpisode = entry?.lastWatchedEpisode
    val lastProgress = if (lastSeason != null && lastEpisode != null) {
        titleProgress.progressFor(lastSeason, lastEpisode)
    } else {
        null
    }
    val target = defaultSeriesEpisode(
        media = media,
        entry = entry,
        lastEpisodeCompleted = lastProgress?.completed == true,
    )
    val targetProgress = titleProgress.progressFor(target.first, target.second)
    val position = resumablePositionSeconds(targetProgress)
    val hasPlayed = titleProgress.isNotEmpty() || (lastSeason != null && lastEpisode != null)
    val finishedSeries = hasPlayed && lastProgress?.completed == true &&
        target == (lastSeason to lastEpisode)
    val episodeLabel = "S${target.first}E${target.second}"

    return MediaWatchAction(
        label = when {
            position != null -> "Continue $episodeLabel ${formatWatchPosition(position)}"
            finishedSeries -> "Watch Again $episodeLabel"
            hasPlayed -> "Continue $episodeLabel"
            else -> "Watch"
        },
        season = target.first,
        episode = target.second,
        positionSeconds = position,
    )
}

/** The default episode used when Watch was not given an explicit episode. */
internal fun defaultSeriesEpisode(
    media: Media,
    entry: LibraryEntry?,
    lastEpisodeCompleted: Boolean,
): Pair<Int, Int> {
    val firstSeason = media.seasons.minOfOrNull { it.number } ?: 1
    val lastSeason = entry?.lastWatchedSeason
    val lastEpisode = entry?.lastWatchedEpisode
    if (lastSeason == null || lastEpisode == null) return firstSeason to 1
    if (!lastEpisodeCompleted) return lastSeason to lastEpisode
    return nextEpisodeAfter(media.seasons, lastSeason, lastEpisode) ?: (lastSeason to lastEpisode)
}

/** The position Playback will really resume from, excluding completed and near-ended media. */
internal fun resumablePositionSeconds(progress: WatchProgress?): Double? {
    progress ?: return null
    if (progress.completed || progress.positionSeconds <= 0.0) return null
    if (
        progress.durationSeconds > 0.0 &&
        progress.positionSeconds >= progress.durationSeconds - RESUME_TAIL_SECONDS
    ) {
        return null
    }
    return progress.positionSeconds
}

internal fun formatWatchPosition(seconds: Double): String {
    val total = if (!seconds.isFinite() || seconds <= 0.0) 0L else seconds.roundToLong()
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val remainingSeconds = total % 60
    val minuteText = minutes.toString().let { if (hours > 0) it.padStart(2, '0') else it }
    val body = "$minuteText:${remainingSeconds.toString().padStart(2, '0')}"
    return if (hours > 0) "$hours:$body" else body
}

private fun List<WatchProgress>.progressFor(season: Int, episode: Int): WatchProgress? =
    filter { it.season == season && it.episode == episode }.latestProgress()

private fun List<WatchProgress>.latestProgress(): WatchProgress? =
    maxWithOrNull(
        compareBy<WatchProgress> { it.watchedAt }
            // At the same instant, an unfinished row is the one playback can resume.
            .thenBy { !it.completed },
    )

private const val RESUME_TAIL_SECONDS = 15.0
