package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One ranked entry in the taste profile — a genre, a keyword or a person.
 *
 * [score] is the raw decayed profile weight. It is deliberately never shown as a number:
 * it has no unit anyone could interpret, and its magnitude depends on library size. It
 * exists to fix the order and to size a bar relative to its neighbours.
 */
@Serializable
data class DiscoveryTaste(val id: Int, val name: String, val score: Double)

/** A production company or network, with how many titles in the taste set it made. */
@Serializable
data class StudioEntry(val id: Int, val name: String, val count: Int)

/**
 * A library title whose weight moved the taste profile, in whichever direction.
 *
 * A negative [weight] is a title that steered recommendations *away* from something —
 * dropped, or rated below ★3. Showing both halves is the point: a profile the viewer
 * cannot see the negative side of looks arbitrary when it stops suggesting a genre.
 */
@Serializable
data class ContributingTitle(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val title: String,
    @SerialName("poster_path") val posterPath: String,
    val weight: Double,
)


/** How many engaged titles came out of a given decade, e.g. 1990 meaning the 1990s. */
@Serializable
data class DecadeCount(val decade: Int, val count: Int)

/**
 * How many engaged titles are originally in a given language.
 *
 * Carries the ISO 639-1 code rather than a display name: naming a language is presentation,
 * belongs next to the rest of the page's copy, and would otherwise have to be translated
 * twice if the app is ever localised.
 */
@Serializable
data class LanguageCount(val code: String, val count: Int)

/**
 * The taste-side view of a profile: what the library argues the viewer likes.
 *
 * Lives in `:shared` for the same reason as [ActivityStats] — the in-process hosts return
 * it directly and a compatibility client decodes it from `GET /discover/insights`, so the
 * `@SerialName`s here are that route's wire contract.
 *
 * Building this is expensive on a cold cache: it costs one metadata request per saved
 * title. Callers should treat it as a slow follow-up to the cheap half of a screen, never
 * as something to block first paint on.
 */
@Serializable
data class DiscoveryInsights(
    @SerialName("top_movie_genres") val topMovieGenres: List<DiscoveryTaste> = emptyList(),
    @SerialName("top_tv_genres") val topTvGenres: List<DiscoveryTaste> = emptyList(),
    @SerialName("disliked_genres") val dislikedGenres: List<DiscoveryTaste> = emptyList(),
    @SerialName("top_keywords") val topKeywords: List<DiscoveryTaste> = emptyList(),
    @SerialName("top_people") val topPeople: List<DiscoveryTaste> = emptyList(),
    @SerialName("signals_used") val signalsUsed: Int = 0,
    @SerialName("top_studios") val topStudios: List<StudioEntry> = emptyList(),
    @SerialName("top_contributors") val topContributors: List<ContributingTitle> = emptyList(),
    @SerialName("negative_contributors") val negativeContributors: List<ContributingTitle> = emptyList(),
    /** Release decades of the engaged set, most-watched first. */
    val decades: List<DecadeCount> = emptyList(),
    /** Original languages of the engaged set, most-watched first. */
    val languages: List<LanguageCount> = emptyList(),
    /**
     * Mean length of an engaged title in minutes — a film's runtime, or one episode of a
     * series. Zero when nothing in the set reported a runtime.
     */
    @SerialName("average_runtime_minutes") val averageRuntimeMinutes: Int = 0,
)

/**
 * One tracker's own all-time totals for the linked account.
 *
 * Worth showing precisely because it disagrees with the rest of the page: Cove only knows
 * what it watched itself, while a tracker may carry years of history from before this app
 * was installed. Presenting it as a separate, clearly-attributed figure is honest; folding
 * it into the local totals would not be — which is also why [provider] is on the value
 * rather than implied by where it was fetched from.
 */
@Serializable
data class TrackerStats(
    /** A [TrackerProvider] key. Defaulted so a payload from an older host still decodes. */
    val provider: String = "trakt",
    @SerialName("movies_watched") val moviesWatched: Int = 0,
    @SerialName("movie_minutes") val movieMinutes: Long = 0,
    @SerialName("shows_watched") val showsWatched: Int = 0,
    @SerialName("episodes_watched") val episodesWatched: Int = 0,
    @SerialName("episode_minutes") val episodeMinutes: Long = 0,
    val ratings: Int = 0,
) {
    /** Nothing to show for an account that has been linked but never used. */
    val isEmpty: Boolean
        get() = moviesWatched == 0 && showsWatched == 0 && episodesWatched == 0
}
