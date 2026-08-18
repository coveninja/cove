package com.coveninja.cove.ui.onboarding

import androidx.compose.ui.graphics.Color
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.model.MediaType

/**
 * First run, as a value.
 *
 * Everything here is pure: no Compose, no repositories, no coroutines. The flow's shape —
 * which steps a host offers, what the footer says, what the viewer's choices add up to —
 * is decided in this file and merely *rendered* by the two presentations. That split is what
 * lets the seven-step flow be tested at all, because the module has no Compose UI tests.
 *
 * The one thing worth stating up front: **every step after Welcome is skippable**. A first
 * run that traps someone behind a form is worse than a first run that teaches them nothing,
 * and the two steps that genuinely matter (sources, taste) both have a perfectly good
 * "later, in Settings" answer.
 */
enum class OnboardingStep(
    val title: String,
    val blurb: String,
    val icon: String,
) {
    Welcome(
        title = "Welcome to Cove",
        blurb = "Your films and series, in one place, on every screen you own.",
        icon = "lucide:sparkles",
    ),
    Profile(
        title = "Who's watching?",
        blurb = "A name for this profile. Everyone on this device gets their own library.",
        icon = "lucide:user-round",
    ),
    Taste(
        title = "What are you in the mood for?",
        blurb = "Pick a few things you like. Cove uses them to build your Home.",
        icon = "lucide:heart",
    ),
    Sources(
        title = "Bring your own sources",
        blurb = "Cove hosts nothing itself. Add a provider addon and it can find streams.",
        icon = "lucide:blocks",
    ),
    Preferences(
        title = "How you like to watch",
        blurb = "Three things worth setting now. Everything else lives in Settings.",
        icon = "lucide:settings",
    ),
    Sync(
        title = "Keep it in step",
        blurb = "An account carries your library and progress to your other devices.",
        icon = "lucide:cloud",
    ),
    Finish(
        title = "You're all set",
        blurb = "That's everything. The rest of Cove is waiting.",
        icon = "lucide:check",
    ),
}

/**
 * What this host can actually offer.
 *
 * Resolved from the graph rather than from the platform: a desktop build talking to a remote
 * backend over `--api-base` has no profile store, and a build with no Supabase credentials
 * has no account, and in both cases the honest thing is to not show the step at all. Offering
 * a sign-in form that could never succeed is the failure mode this exists to prevent.
 */
data class OnboardingCapabilities(
    val profiles: Boolean = true,
    val account: Boolean = true,
)

/**
 * The steps this host shows, in order.
 *
 * Welcome, Taste, Sources, Preferences and Finish are unconditional. Sources stays even where
 * the addon repository is unreachable — it renders an explanation instead of an input — because
 * it is the one step whose absence leaves the viewer with an app that cannot play anything and
 * no idea why. A step that quietly disappears teaches nothing.
 */
fun stepsFor(capabilities: OnboardingCapabilities): List<OnboardingStep> = buildList {
    add(OnboardingStep.Welcome)
    if (capabilities.profiles) add(OnboardingStep.Profile)
    add(OnboardingStep.Taste)
    add(OnboardingStep.Sources)
    add(OnboardingStep.Preferences)
    if (capabilities.account) add(OnboardingStep.Sync)
    add(OnboardingStep.Finish)
}

/** Everything the viewer has chosen so far. Committed to the graph in one pass at the end. */
data class OnboardingDraft(
    val profileName: String = "",
    val likedGenreIds: Set<Int> = emptySet(),
    /** TMDB identity of each liked title, in the order they were picked. */
    val likedTitles: List<OnboardingPick> = emptyList(),
    val addedAddons: List<String> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val autoPlayNext: Boolean = true,
    val autoSkipIntro: Boolean = false,
    /** True once an account was signed into during the flow; the Sync step reports it. */
    val signedIn: Boolean = false,
)

/**
 * A title the viewer liked, carried by identity rather than by the whole [Media] object.
 *
 * The poster wall is rebuilt whenever content reloads — a locale change alone does it — so
 * holding onto full media objects would pin a stale presentation for the length of the flow.
 * These five fields are exactly what `LibraryRepository.add` needs and nothing more.
 */
data class OnboardingPick(
    val id: String,
    val tmdbId: Int,
    /**
     * Carried alongside the id rather than parsed back out of it. The id is `"Movie:603"` —
     * a display key, not a schema — and reading a type out of it would make the library write
     * depend on a string format that exists for entirely unrelated reasons.
     */
    val type: MediaType?,
    val title: String,
    val posterUrl: String,
    val voteAverage: Double,
)

/** The genre bubbles on the taste step, as ids the discovery engine already speaks. */
data class OnboardingGenre(val id: Int, val label: String, val icon: String)

/**
 * A deliberately short, deliberately broad list.
 *
 * Not TMDB's full vocabulary: thirty-odd bubbles is a form, not a choice, and the ones left
 * out (Soap, Talk, News, TV Movie) describe how something was broadcast rather than what it
 * is like to watch. Ids are the film vocabulary from [com.coveninja.cove.ui.model.TmdbGenres],
 * which is the one both catalogs agree on for everything listed here.
 */
val OnboardingGenres: List<OnboardingGenre> = listOf(
    OnboardingGenre(28, "Action", "lucide:flame"),
    OnboardingGenre(35, "Comedy", "lucide:message-circle"),
    OnboardingGenre(18, "Drama", "lucide:align-left"),
    OnboardingGenre(878, "Sci-Fi", "lucide:globe-2"),
    OnboardingGenre(27, "Horror", "lucide:eye"),
    OnboardingGenre(10749, "Romance", "lucide:heart"),
    OnboardingGenre(53, "Thriller", "lucide:gauge"),
    OnboardingGenre(16, "Animation", "lucide:clapperboard"),
    OnboardingGenre(99, "Documentary", "lucide:film"),
    OnboardingGenre(14, "Fantasy", "lucide:sparkles"),
    OnboardingGenre(80, "Crime", "lucide:key"),
    OnboardingGenre(12, "Adventure", "lucide:compass"),
    OnboardingGenre(9648, "Mystery", "lucide:file-question"),
    OnboardingGenre(10751, "Family", "lucide:users"),
    OnboardingGenre(36, "History", "lucide:building-2"),
    OnboardingGenre(10402, "Music", "lucide:audio-lines"),
)

/**
 * The step after [step], or null when there is none.
 *
 * The `indexOf` guard is load-bearing here in a way its mirror in [previousStep] is not.
 * `indexOf` answers -1 for a step this host does not show, and `getOrNull(-1 + 1)` is the
 * *first* step — so without the check, Continue from a stale step would silently restart the
 * whole flow rather than ending it. Null is what the controller reads as "commit and finish".
 */
fun nextStep(step: OnboardingStep, steps: List<OnboardingStep>): OnboardingStep? {
    val index = steps.indexOf(step)
    if (index < 0) return null
    return steps.getOrNull(index + 1)
}

/**
 * The step before [step], or null at the start.
 *
 * No explicit bounds check: `indexOf` returns -1 for an absent step and 0 for the first one, and
 * `getOrNull` rejects both -2 and -1 already. A guard here would be unreachable — which is
 * exactly what the mutation check found when one was written.
 */
fun previousStep(step: OnboardingStep, steps: List<OnboardingStep>): OnboardingStep? =
    steps.getOrNull(steps.indexOf(step) - 1)

/**
 * What the forward button says.
 *
 * The label carries the only pressure this flow applies: a step the viewer has engaged with
 * says "Continue", one they have not says "Skip for now". Saying "Skip" on a step someone has
 * just filled in reads as though their answer was not registered.
 */
fun advanceLabel(step: OnboardingStep, draft: OnboardingDraft): String = when (step) {
    OnboardingStep.Welcome -> "Get started"
    OnboardingStep.Finish -> "Start watching"
    OnboardingStep.Profile ->
        if (normalizedProfileName(draft.profileName) != null) "Continue" else "Skip for now"
    OnboardingStep.Taste ->
        if (draft.likedGenreIds.isNotEmpty() || draft.likedTitles.isNotEmpty()) {
            "Continue"
        } else {
            "Skip for now"
        }
    OnboardingStep.Sources ->
        if (draft.addedAddons.isNotEmpty()) "Continue" else "Skip for now"
    OnboardingStep.Preferences -> "Continue"
    OnboardingStep.Sync -> if (draft.signedIn) "Continue" else "Skip for now"
}

/**
 * A profile name fit to store, or null when there is nothing to store.
 *
 * Null rather than a default like "Me": a blank field means the viewer declined to name the
 * profile, and writing a placeholder over whatever the store already called it would be a
 * silent edit they never asked for. The commit skips the rename entirely on null.
 */
fun normalizedProfileName(raw: String): String? = raw
    .trim()
    .replace(WHITESPACE_RUN, " ")
    .take(MAX_PROFILE_NAME)
    .takeIf { it.isNotEmpty() }

/**
 * What is obviously wrong with a manifest URL, or null if it is worth trying.
 *
 * Checked here rather than left to the network because the two mistakes people actually make —
 * pasting a `stremio://` link, or pasting the addon's *homepage* — both fail upstream with a
 * message about the fetch rather than about the paste. Anything less clear-cut is allowed
 * through: this is a hint, not a gate, and the repository is the authority on whether a
 * manifest resolves.
 */
fun manifestUrlProblem(raw: String): String? {
    val url = raw.trim()
    return when {
        url.isEmpty() -> "Paste the addon's manifest URL."
        url.startsWith("stremio://") ->
            "That's a Stremio install link. Swap stremio:// for https:// and try again."
        !url.startsWith("http://") && !url.startsWith("https://") ->
            "That doesn't look like a URL — it should start with https://."
        !url.substringBefore('?').endsWith("manifest.json") ->
            "Most addons end in /manifest.json. Cove will still try this one."
        else -> null
    }
}

/** Whether [manifestUrlProblem] is refusing the URL outright or merely doubting it. */
fun manifestUrlSubmittable(raw: String): Boolean {
    val url = raw.trim()
    return url.startsWith("http://") || url.startsWith("https://")
}

/**
 * The profile's mark: a letter and a colour, derived rather than stored.
 *
 * [com.coveninja.cove.shared.model.Profile] has no avatar field, and adding one would cost a
 * `Cove.sq` change, a numbered migration and a sync payload change for something purely
 * decorative. Deriving it from the name instead is free, identical on every device without
 * syncing anything, and updates live as the viewer types — which is the whole micro-interaction
 * on the profile step.
 *
 * The hash is written out rather than using `String.hashCode`, whose value is only specified
 * on the JVM; a derived colour that differs between a phone and a desktop would undo the point.
 */
fun emblemFor(name: String): OnboardingEmblem {
    val normalized = normalizedProfileName(name)
    val initial = normalized
        ?.firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()
        ?.toString()
        ?: "?"
    val seed = normalized?.fold(0) { acc, char -> (acc * 31 + char.code) and 0x7FFFFFFF } ?: 0
    return OnboardingEmblem(
        initial = initial,
        color = EmblemPalette[seed % EmblemPalette.size],
    )
}

data class OnboardingEmblem(val initial: String, val color: Color)

/**
 * One line of the finish screen's summary.
 *
 * Built from the draft rather than from what the commit reported, so it describes what the
 * viewer chose. A source that failed to install is reported by the Sources step at the time,
 * where it can still be corrected, not retroactively on the last screen.
 */
data class OnboardingSummaryItem(val icon: String, val count: Int, val label: String)

/**
 * What the flow accomplished, in countable things.
 *
 * Only non-zero entries: a summary that lists "0 sources" reports a failure the viewer
 * deliberately chose, and the last screen of a first run is a poor place to do that. An
 * entirely skipped flow returns empty and the finish screen shows its blurb alone.
 */
fun summaryFor(draft: OnboardingDraft): List<OnboardingSummaryItem> = buildList {
    if (draft.addedAddons.isNotEmpty()) {
        add(
            OnboardingSummaryItem(
                icon = "lucide:blocks",
                count = draft.addedAddons.size,
                label = if (draft.addedAddons.size == 1) "source" else "sources",
            ),
        )
    }
    if (draft.likedTitles.isNotEmpty()) {
        add(
            OnboardingSummaryItem(
                icon = "lucide:bookmark-check",
                count = draft.likedTitles.size,
                label = if (draft.likedTitles.size == 1) "title saved" else "titles saved",
            ),
        )
    }
    if (draft.likedGenreIds.isNotEmpty()) {
        add(
            OnboardingSummaryItem(
                icon = "lucide:heart",
                count = draft.likedGenreIds.size,
                label = "genres",
            ),
        )
    }
}

/**
 * The posters worth offering, given what the viewer said they liked.
 *
 * Titles matching a chosen genre come first, in the catalog's own order, and everything else
 * follows — rather than filtering the rest away. A narrow pick like Documentary plus Music can
 * match almost nothing in a discover feed, and a wall that empties out as you choose reads as
 * broken; this way choosing a genre visibly *reorders* the wall, which is the feedback the
 * step is trying to give.
 */
fun <T> rankByGenre(
    items: List<T>,
    likedGenreIds: Set<Int>,
    genreIdsOf: (T) -> List<Int>,
): List<T> {
    if (likedGenreIds.isEmpty()) return items
    val (matching, rest) = items.partition { item ->
        genreIdsOf(item).any { it in likedGenreIds }
    }
    return matching + rest
}

private val WHITESPACE_RUN = Regex("\\s+")
private const val MAX_PROFILE_NAME = 32

/**
 * Emblem colours.
 *
 * Drawn from the palette the rest of the app already uses rather than invented here, so a
 * profile mark cannot introduce a hue that appears nowhere else in Cove. The brand accent is
 * included because the first profile on a fresh install should be able to land on it.
 */
private val EmblemPalette: List<Color> = listOf(
    CoveColors.Brand.Accent,
    CoveColors.Status.Info,
    CoveColors.Status.Warning,
    CoveColors.Segment.Recap,
    CoveColors.Segment.Credits,
    CoveColors.Segment.Preview,
    CoveColors.Status.Rating,
)
