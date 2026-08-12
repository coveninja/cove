package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.model.uiMediaId

/**
 * Where the viewer got to in every title they have played, keyed by UI media id.
 *
 * The library exposes resume points one title at a time, which cannot decorate a grid;
 * this is the bulk read, indexed for the cards to look themselves up in.
 */
@Stable
class WatchProgressIndex(private val byUiId: Map<String, WatchProgress>) {

    fun progressFor(mediaId: String): WatchProgress? = byUiId[mediaId]

    /** How far through, or null when there is nothing worth drawing. See [watchFraction]. */
    fun fractionFor(mediaId: String): Float? = watchFraction(byUiId[mediaId])

    companion object {
        val Empty = WatchProgressIndex(emptyMap())
    }
}

/**
 * The fraction of a title that has been watched, or null when a resume bar would be noise.
 *
 * Null rather than 0f or 1f in the uninteresting cases: a title never played, one finished,
 * one abandoned in the first seconds, and one all but over are all better drawn as *no*
 * bar than as an empty or full one that implies the viewer is mid-watch.
 */
fun watchFraction(progress: WatchProgress?): Float? {
    if (progress == null || progress.completed) return null
    if (progress.durationSeconds <= 0.0) return null
    val fraction = (progress.positionSeconds / progress.durationSeconds).toFloat()
    if (fraction < MIN_MEANINGFUL_FRACTION || fraction > MAX_MEANINGFUL_FRACTION) return null
    return fraction
}

/**
 * One row per title: the most recent, preferring an unfinished episode over a finished one
 * watched at the same moment, so a show sitting mid-episode shows that rather than the
 * episode before it.
 */
fun watchProgressByUiId(progress: List<WatchProgress>): Map<String, WatchProgress> =
    progress
        .groupBy { uiMediaId(it.tmdbId, it.mediaType) }
        .mapValues { (_, rows) ->
            rows.reduce { best, candidate ->
                when {
                    candidate.watchedAt > best.watchedAt -> candidate
                    candidate.watchedAt < best.watchedAt -> best
                    // Same instant: the one still in progress is the one to resume.
                    best.completed && !candidate.completed -> candidate
                    else -> best
                }
            }
        }

@Composable
fun rememberWatchProgressIndex(progress: List<WatchProgress>): WatchProgressIndex =
    remember(progress) { WatchProgressIndex(watchProgressByUiId(progress)) }

private const val MIN_MEANINGFUL_FRACTION = 0.02f
private const val MAX_MEANINGFUL_FRACTION = 0.98f
