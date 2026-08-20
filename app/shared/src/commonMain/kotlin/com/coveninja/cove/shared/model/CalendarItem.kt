package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One dated thing belonging to a saved title.
 *
 * Shapes exactly what `GET /api/library/calendar` has always returned — [mediaType] is the
 * wire name rather than the enum, and [date] a plain `yyyy-MM-dd` string — because this is
 * both the HTTP payload and the persisted cache row. [type] adapts it for callers that want
 * the enum.
 */
@Serializable
data class CalendarItem(
    val date: String,
    /** One of [KIND_AVAILABLE], [KIND_MOVIE], [KIND_EPISODE]. */
    val kind: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    @SerialName("poster_path") val posterPath: String,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_name") val episodeName: String = "",
    @SerialName("still_path") val stillPath: String = "",
    /** How many aired episodes are still unwatched, for a backlog entry. */
    @SerialName("waiting_count") val waitingCount: Int = 0,
    /**
     * The aired seasons [waitingCount] was counted from; empty on anything but a TV
     * backlog entry.
     *
     * Carried so the count can be redone against watch progress without rebuilding the
     * snapshot from TMDB — see `applyWatchProgress`. Without it a finished episode goes on
     * waiting until the whole calendar is refetched.
     */
    @SerialName("aired_seasons") val airedSeasons: List<AiredSeason> = emptyList(),
) {
    val type: MediaType?
        get() = when (mediaType) {
            MediaType.Movie.wireName -> MediaType.Movie
            MediaType.Tv.wireName -> MediaType.Tv
            else -> null
        }

    /** Watchable right now, as opposed to scheduled for a future date. */
    val available: Boolean get() = kind == KIND_AVAILABLE

    /**
     * Stable across refreshes so a lazy list keeps item identity. Includes [kind] because
     * one title can appear both as a backlog entry and as an upcoming episode.
     */
    val id: String
        get() = buildString {
            append(kind)
            append(':')
            append(mediaType)
            append(':')
            append(tmdbId)
            if (seasonNumber != null && episodeNumber != null) {
                append(':')
                append(seasonNumber)
                append(':')
                append(episodeNumber)
            }
        }

    companion object {
        const val KIND_AVAILABLE = "available"
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
    }
}

/**
 * How much of one season had aired when a calendar snapshot was built.
 *
 * The aired episodes are `1..episodeCount` of [seasonNumber]: the last aired season is
 * clamped to the episode that aired, earlier ones run whole. Four bytes of arithmetic per
 * season is all a backlog entry needs to be re-counted, which is why the entry carries this
 * rather than the episodes themselves.
 */
@Serializable
data class AiredSeason(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_count") val episodeCount: Int,
)
