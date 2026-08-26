package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One title's share of a year's watch time.
 *
 * [mediaType] is a plain string rather than [MediaType] because these rows are keyed by the
 * `"{tmdbId}:{mediaType}"` form the activity tables store, and the wire payload predates the
 * enum. Callers that need the enum convert at the edge.
 */
@Serializable
data class ActivityTitle(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val seconds: Long,
    val title: String = "",
    @SerialName("poster_path") val posterPath: String = "",
)

/**
 * Which slice of history the insights page is asking about.
 *
 * Only the figures that genuinely vary by period are narrowed — totals, the calendar, the
 * hour and weekday breakdowns, and the leaderboard. Streaks, the year-over-year comparison
 * and the by-year chart stay all-time whatever is selected, because a "current streak"
 * inside a year that ended is not a thing anyone means, and the comparison badge exists
 * precisely to relate two periods rather than sit inside one.
 */
enum class InsightsRange(val wireName: String) {
    ThisYear("this_year"),
    LastYear("last_year"),
    AllTime("all_time"),
    ;

    companion object {
        fun fromWire(value: String?): InsightsRange =
            entries.firstOrNull { it.wireName == value } ?: AllTime
    }
}

/**
 * How many times a title has been started from the beginning.
 *
 * Only titles played more than once appear, because "watched once" is every other title on
 * the page and listing them here would say nothing.
 */
@Serializable
data class TitlePlayCount(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val plays: Int,
    val title: String = "",
    @SerialName("poster_path") val posterPath: String = "",
)

/**
 * One dated event in a viewer's history, and whatever was on at the time.
 *
 * The counterpart to everything else on this class, which is aggregates. A sum describes a
 * population; a moment describes a person, and "the Saturday you watched nine hours" is the
 * kind of thing someone recognises about themselves in a way that "your average active day
 * is 96 minutes" never is. That is the whole reason these exist.
 *
 * One type serves every moment on the page rather than four near-identical ones. The cost
 * is that [date] means something slightly different for a monthly headliner — it is the
 * first of that month, since the moment covers a month rather than a day — and that is
 * spelled out here rather than left for a caller to discover.
 */
@Serializable
data class WatchMoment(
    /** ISO `YYYY-MM-DD`. The first of the month for an entry in [ActivityStats.monthlyHeadliners]. */
    val date: String = "",
    val seconds: Long = 0,
    @SerialName("tmdb_id") val tmdbId: Int = 0,
    @SerialName("media_type") val mediaType: String = "",
    /**
     * Empty when the title is no longer in the library.
     *
     * Activity rows are keyed by tmdb id and learn their name from `library_entries`, so a
     * title that was watched and then removed still has time against it and nothing to call
     * it. Presentation decides what to do about that; the model does not invent a name.
     */
    val title: String = "",
    @SerialName("poster_path") val posterPath: String = "",
) {
    val isEmpty: Boolean
        get() = date.isBlank() || seconds <= 0L
}

/**
 * Everything the activity tables can say about how a profile actually watches.
 *
 * Lives in `:shared` rather than beside the service that builds it because both the
 * in-process hosts and the HTTP clients need the same shape: the desktop and Android
 * runtimes hand this straight to the UI, while a compatibility client decodes it from
 * `GET /library/activity`. The `@SerialName`s are the wire contract for that route and must
 * not drift.
 *
 * Every field defaults to empty. A profile that has never played anything is an ordinary
 * state — the insights page renders around it rather than treating it as a failure.
 *
 * Index bases are fixed and worth stating because they are easy to get wrong when reading:
 * [byMonthThisYear] and [byMonthLastYear] start at January, [byDayOfWeek] starts at Sunday,
 * [byHourOfDay] starts at midnight. [calendar] holds only days with time on them, keyed
 * `"YYYY-MM-DD"`, so its size is the number of active days rather than the span covered.
 */
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
    /** Titles started more than once, most-played first. */
    val rewatched: List<TitlePlayCount> = emptyList(),
    /** The single day of the range with the most time on it, and what dominated it. */
    @SerialName("biggest_day") val biggestDay: WatchMoment? = null,
    /**
     * The title that took the most time in each month of the range, earliest first.
     *
     * Months with nothing watched in them are absent rather than present and empty, so the
     * list is between zero and twelve entries long and a caller must not index it by month.
     */
    @SerialName("monthly_headliners") val monthlyHeadliners: List<WatchMoment> = emptyList(),
    /** The longest unbroken run of hours with real watching in them. */
    @SerialName("longest_session") val longestSession: WatchMoment? = null,
    /** The first day of the range with anything on it. */
    @SerialName("first_watch") val firstWatch: WatchMoment? = null,
)
