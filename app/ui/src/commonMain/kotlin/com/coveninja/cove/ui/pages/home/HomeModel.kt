package com.coveninja.cove.ui.pages.home

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.displayImageUrl
import com.coveninja.cove.ui.pages.mylist.calendar.episodeMarker
import com.coveninja.cove.ui.pages.mylist.calendar.parsedDate
import com.coveninja.cove.ui.state.hasUnwatchedAired
import com.coveninja.cove.ui.state.neverPlayed
import com.coveninja.cove.ui.state.watchFraction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.roundToInt

/**
 * Everything Home decides before it draws anything.
 *
 * Deliberately free of Compose and of the repositories: every function here takes plain data
 * and returns plain data, because the ranking is the part that is easy to get subtly wrong
 * and the only part a test can actually pin down. `HomeController` does the fetching, the
 * view files do the drawing, and the choices live here.
 */

// ── Continue watching ───────────────────────────────────────────────────────

/** One episode, by season and number. */
data class EpisodeRef(val season: Int, val episode: Int) {
    /** Stable key for caching a fetched still against a show. */
    fun cacheKey(tmdbId: Int): String = "$tmdbId:$season:$episode"
}

/**
 * Why a title is on the carry-on rail.
 *
 * There are exactly two honest answers, and neither is "it is marked as watching". A show's
 * library status is set once and stays set: it says what the viewer intends, not whether
 * anything is actually left to play, so a show whose every episode is finished stays
 * `Watching` forever. Qualifying on it put completed episodes on a rail headed "where you
 * left off" — see [continueWatchingRows].
 */
enum class ContinueReason {
    /** Stopped part-way through. The one case where a resume point genuinely exists. */
    Resume,

    /** Marked as watching but never actually played. The offer is to begin, not to resume. */
    Unstarted,
}

/**
 * One saved title with something genuinely left to play: a live resume point, or a show
 * marked as watching that has never been started.
 *
 * Deliberately *not* including "has episodes waiting". That is the backlog rail's job — see
 * [BacklogRow] — and the two would otherwise list the same shows for different reasons.
 */
data class ContinueRow(
    val media: Media,
    val entry: LibraryEntry,
    val progress: WatchProgress?,
    val watchFraction: Float?,
    val hasNewEpisodes: Boolean,
    val reason: ContinueReason,
) {
    val id: String get() = media.id

    val displayTitle: String
        get() = media.title?.takeIf { it.isNotBlank() }
            ?: media.name?.takeIf { it.isNotBlank() }
            ?: entry.title

    /** Where the viewer stopped, as `S3 E7`, or null for films and unstarted shows. */
    val episodeMarker: String?
        get() {
            val season = progress?.season ?: entry.lastWatchedSeason ?: return null
            val episode = progress?.episode ?: entry.lastWatchedEpisode ?: return null
            return "S$season E$episode"
        }

    /**
     * The widest art the row already knows about, before any episode still is fetched.
     *
     * A library row carries only a poster, so a title no feed happens to be listing falls
     * back to one — see [hasWideArt], which is what lets the card crop a portrait image
     * sensibly into a landscape frame instead of smearing it.
     */
    val artUrl: String? get() = media.backdropUrl ?: media.posterUrl

    val hasWideArt: Boolean get() = !media.backdropUrl.isNullOrBlank()

    /**
     * Which episode's still frame represents this row: the one being played.
     *
     * Null for films, which have no episodes, and for unstarted titles, which fall out here
     * without a guard — [ContinueReason.Unstarted] means no progress row *and* no watched
     * counters, so there is nothing for either lookup to find. That is the intended answer
     * rather than a gap: a still from an episode the viewer has not reached would be both a
     * spoiler and a lie about where they are. Both keep their backdrop instead.
     */
    fun thumbnailEpisode(): EpisodeRef? {
        val season = progress?.season ?: entry.lastWatchedSeason ?: return null
        val episode = progress?.episode ?: entry.lastWatchedEpisode ?: return null
        return EpisodeRef(season, episode)
    }

    /**
     * The line under the title: `S3 E7 · 24 min left`, or whatever part of it is known.
     *
     * An unstarted title never names an episode, because the marker would have to come from
     * the library's watched counters and there are none — naming one would be inventing a
     * position the viewer has never reached.
     */
    val caption: String
        get() = when (reason) {
            // Says what the card is offering. The format label would be the obvious filler
            // here and a wasted line — the poster already says which format it is, while
            // "not started" is the one fact distinguishing this card from the resume cards
            // beside it.
            ContinueReason.Unstarted -> "Not started"
            ContinueReason.Resume -> buildList {
                episodeMarker?.let(::add)
                remainingLabel(progress)?.let(::add)
                if (isEmpty()) media.type?.label?.let(::add)
            }.joinToString("  ·  ")
        }
}

/**
 * The titles worth offering to carry on with, most recent activity first.
 *
 * A title qualifies on exactly two grounds, both of which mean something is genuinely left
 * to play right now:
 *
 *  - **[ContinueReason.Resume]** — a live resume point. `watchFraction` is null once an
 *    episode completes, so finishing something removes it here rather than leaving a card
 *    advertising an episode already watched.
 *  - **[ContinueReason.Unstarted]** — marked as watching with no playback recorded at all.
 *
 * What is deliberately *not* a qualification is the library status on its own. Status is an
 * intention the viewer sets once; it stays `Watching` after the last episode is finished, so
 * treating it as evidence kept completed titles on the rail indefinitely. Nor is
 * [LibraryEntry.hasUnwatchedAired] a qualification: a show with episodes waiting belongs on
 * the backlog rail, which knows how many are waiting and is built from the release calendar
 * rather than from counters nothing in the app currently writes.
 *
 * [enrich] and [progressFor] are passed in rather than reached for so this stays a pure
 * function; the page supplies `MediaCatalog::enrich` and `WatchProgressIndex::progressFor`.
 */
fun continueWatchingRows(
    entries: List<LibraryEntry>,
    progressFor: (String) -> WatchProgress?,
    enrich: (LibraryEntry) -> Media,
    limit: Int = CONTINUE_ROW_LIMIT,
): List<ContinueRow> =
    entries
        .asSequence()
        .filter { it.status != LibraryStatus.Dropped }
        .mapNotNull { entry ->
            val media = enrich(entry)
            val progress = progressFor(media.id)
            val fraction = watchFraction(progress)

            val reason = when {
                fraction != null -> ContinueReason.Resume
                entry.status == LibraryStatus.Watching && entry.neverPlayed(progress) ->
                    ContinueReason.Unstarted
                // Everything else is caught up as far as anything here can tell: the last
                // episode played is complete and no resume point survives it.
                else -> return@mapNotNull null
            }

            ContinueRow(
                media = media,
                entry = entry,
                progress = progress,
                watchFraction = fraction,
                hasNewEpisodes = entry.hasUnwatchedAired(),
                reason = reason,
            )
        }
        .sortedWith(
            compareByDescending<ContinueRow> { it.activityKey() }
                .thenBy { it.displayTitle.lowercase() },
        )
        .take(limit)
        .toList()

/**
 * When this title was last touched, newest first.
 *
 * Prefers the progress row's timestamp over the library's: playback writes the former on
 * every tick, while the latter also moves for things that are not watching — rating a title,
 * or dragging it to another list.
 */
private fun ContinueRow.activityKey(): String =
    progress?.watchedAt?.takeIf { it.isNotBlank() }
        ?: entry.lastWatchedAt?.takeIf { it.isNotBlank() }
        ?: entry.updatedAt

/**
 * How much is left, as `24 min left` / `1 h 20 min left`.
 *
 * Null whenever a countdown would mislead: nothing played, no known duration, already
 * finished, or under a minute to go — "0 min left" on something effectively over is worse
 * than saying nothing.
 */
fun remainingLabel(progress: WatchProgress?): String? {
    if (progress == null || progress.completed) return null
    if (progress.durationSeconds <= 0.0) return null
    val remaining = progress.durationSeconds - progress.positionSeconds
    if (remaining < 60.0) return null

    val minutes = (remaining / 60.0).roundToInt()
    if (minutes < 60) return "$minutes min left"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours h left" else "$hours h $rest min left"
}

// ── Backlog ─────────────────────────────────────────────────────────────────

/**
 * A calendar entry for something watchable now, joined to the title it belongs to.
 *
 * The join is what makes it usable: a [CalendarItem] names a title by TMDB id, and every
 * button on the card needs a [Media]. Resolving it up front rather than per-press means a
 * card is only ever drawn for something that can actually be opened and played.
 */
data class BacklogRow(val media: Media, val item: CalendarItem) {
    val id: String get() = item.id

    /** Always at least one: the entry is on the calendar precisely because something waits. */
    val waitingCount: Int get() = item.waitingCount.coerceAtLeast(1)

    val displayTitle: String get() = item.title

    val caption: String
        get() = buildList {
            item.episodeMarker()?.let(::add)
            item.episodeName.takeIf { it.isNotBlank() }?.let(::add)
            if (isEmpty()) add("Ready to watch")
        }.joinToString("  ·  ")

    /** "3 waiting" / "1 waiting" — the badge, which counts rather than shouting "NEW". */
    val badge: String get() = "$waitingCount waiting"
}

/**
 * Pairs each watchable-now calendar entry with its library title, dropping any that cannot be
 * resolved. Nothing reaches the calendar that is not saved, so an unresolvable entry means the
 * library moved underneath a cached snapshot — a card whose buttons would do nothing.
 */
fun backlogRows(items: List<CalendarItem>, resolve: (CalendarItem) -> Media?): List<BacklogRow> =
    items.mapNotNull { item -> resolve(item)?.let { media -> BacklogRow(media, item) } }

// ── Hero ────────────────────────────────────────────────────────────────────

/** Why this title is the one at the top of the page. */
enum class HomeHeroKind(val kicker: String, val icon: String) {
    Resume("Jump back in", "iconamoon:history"),
    NewEpisodes("New episodes", "lucide:tv"),
    Spotlight("In the spotlight", "lucide:sparkles"),
}

data class HomeHero(
    val media: Media,
    val kind: HomeHeroKind,
    val watchFraction: Float? = null,
    val caption: String = "",
) {
    /** Resume and New episodes both go straight to playback; the spotlight has nowhere to go. */
    val playable: Boolean get() = kind != HomeHeroKind.Spotlight

    val playLabel: String get() = if (kind == HomeHeroKind.Resume) "Resume" else "Play"
}

/**
 * The single title Home leads with.
 *
 * Three tiers, in order of how strong a claim they have on the viewer's attention: something
 * half-watched beats something with a new episode, which beats whatever is merely popular.
 * One decisive answer rather than a carousel — Explore already has the carousel, and Home's
 * job is to be right rather than to be broad.
 *
 * Tier two reads the [backlog] rather than the library's aired counters. Those counters are
 * only ever written by the legacy importer, so on a live profile they are always null and a
 * hero tier resting on them would never fire at all.
 */
fun heroPick(
    continuing: List<ContinueRow>,
    backlog: List<BacklogRow>,
    trending: List<Media>,
): HomeHero? {
    continuing.firstOrNull { it.reason == ContinueReason.Resume }?.let { row ->
        return HomeHero(
            media = row.media,
            kind = HomeHeroKind.Resume,
            watchFraction = row.watchFraction,
            caption = row.caption,
        )
    }

    backlog.firstOrNull()?.let { row ->
        return HomeHero(
            media = row.media,
            kind = HomeHeroKind.NewEpisodes,
            caption = listOf(row.badge, row.caption)
                .filter { it.isNotBlank() }
                .joinToString("  ·  "),
        )
    }

    // Backdrop-first: the hero is a wide image with copy over it, and a poster stretched
    // into that frame renders as a smear behind unreadable text.
    val spotlight = trending.firstOrNull { !it.backdropUrl.isNullOrBlank() }
        ?: trending.firstOrNull()
        ?: return null

    return HomeHero(
        media = spotlight,
        kind = HomeHeroKind.Spotlight,
        caption = spotlightCaption(spotlight),
    )
}

private fun spotlightCaption(media: Media): String = buildList {
    media.released?.takeIf { it.isNotBlank() }?.let(::add)
    media.type?.label?.let(::add)
    media.rating?.let { add("★ ${(it * 10).roundToInt() / 10.0}") }
    addAll(media.genres.take(2))
}.joinToString("  ·  ")

// ── Calendar ────────────────────────────────────────────────────────────────

/**
 * Dated releases inside the next [horizonDays], soonest first.
 *
 * Excludes anything already watchable — that is the backlog rail's content, and its dates
 * point backwards, so it would sort to the wrong end of a countdown. Items whose date will
 * not parse are dropped: an undated entry has nothing to say on a "coming up" strip.
 */
fun comingUp(
    items: List<CalendarItem>,
    today: LocalDate,
    horizonDays: Int = COMING_UP_HORIZON_DAYS,
): List<CalendarItem> =
    items
        .asSequence()
        .filter { !it.available }
        .mapNotNull { item -> item.parsedDate()?.let { date -> date to item } }
        .filter { (date, _) ->
            val days = today.daysUntil(date)
            days in 0..horizonDays
        }
        .sortedWith(compareBy({ it.first }, { it.second.title.lowercase() }))
        .map { it.second }
        .toList()

/**
 * The landscape counterpart to `calendarImageUrl`, which is poster-first.
 *
 * Home's calendar cards are wide, so the episode still is the right lead and the poster the
 * fallback — exactly the opposite preference to the list rows in My List, where a portrait
 * thumbnail is what the layout wants. Same [displayImageUrl] handling either way: calendar
 * items carry bare TMDB paths rather than the absolute proxied URLs used elsewhere.
 */
fun calendarWideImageUrl(item: CalendarItem, size: String = "w500"): String? =
    displayImageUrl(item.stillPath.ifBlank { item.posterPath }, size)

// ── Greeting and stats ──────────────────────────────────────────────────────

/** "Good evening" and friends, from the local hour. */
fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late"
}

/** The counters beside the greeting. */
data class HomeStats(
    val watching: Int,
    val episodesWaiting: Int,
    val watchLater: Int,
) {
    val isEmpty: Boolean get() = watching == 0 && episodesWaiting == 0 && watchLater == 0

    companion object {
        val Empty = HomeStats(watching = 0, episodesWaiting = 0, watchLater = 0)
    }
}

/**
 * What the viewer's library adds up to.
 *
 * A backlog entry counts at least one episode even when the backend reports no waiting
 * count: it is on the calendar as watchable, so claiming zero episodes wait would contradict
 * the rail sitting right below the number.
 */
fun libraryStats(entries: List<LibraryEntry>, calendar: List<CalendarItem>): HomeStats =
    HomeStats(
        watching = entries.count { it.status == LibraryStatus.Watching },
        episodesWaiting = calendar.filter { it.available }
            .sumOf { it.waitingCount.coerceAtLeast(1) },
        watchLater = entries.count { it.status == LibraryStatus.WatchLater },
    )

// ── Rails ───────────────────────────────────────────────────────────────────

/** One of Home's poster rails. */
data class HomeRail(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val media: List<Media>,
    /**
     * True when the rail's *ordering* is its content — "Trending now" says something about a
     * set rather than being one, so it earns its place even where the titles are familiar.
     * Membership rails ("Because you watched…") that repeat what the page already showed say
     * nothing and are dropped. Mirrors `ShelfKind.definedByOrder` on Explore.
     */
    val ordered: Boolean = false,
)

/**
 * Picks which rails make the page, in the order given.
 *
 * Same two rules as Explore's `buildShelves`: a rail too short to justify a heading is
 * dropped, and a membership rail whose titles have almost all appeared already is dropped as
 * redundant. Titles themselves are never deduplicated — a film can legitimately sit in more
 * than one rail, and stripping it from the later ones would hollow them out.
 */
fun buildHomeRails(
    candidates: List<HomeRail>,
    minimumSize: Int = MINIMUM_RAIL_SIZE,
): List<HomeRail> {
    val seen = mutableSetOf<String>()
    val kept = mutableListOf<HomeRail>()

    for (rail in candidates) {
        val distinct = rail.media.distinctBy(Media::id)
        if (distinct.size < minimumSize) continue
        if (!rail.ordered && distinct.count { it.id !in seen } < minimumSize) continue
        kept += rail.copy(media = distinct)
        seen += distinct.map(Media::id)
    }
    return kept
}

/** More than a screenful of cards is scrolling for its own sake; the rest is Explore's job. */
const val CONTINUE_ROW_LIMIT = 12

/** Below this a rail has too few posters to be worth a heading and a row of its own. */
const val MINIMUM_RAIL_SIZE = 5

/** A week is what "coming up" means; past that it is the calendar's business, not Home's. */
const val COMING_UP_HORIZON_DAYS = 7
