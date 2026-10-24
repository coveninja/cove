package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.fileName
import kotlin.math.ln
import kotlin.math.log10

/**
 * What the viewer asked Cove to optimise for, from `AppSettings.streamSelectionMode`.
 *
 * The stored values are the strings the settings screen writes; anything
 * unrecognised falls back to [Balanced], which is also the stored default.
 */
enum class StreamSelectionMode {
    /** A healthy swarm weighed against the size of the file. */
    Balanced,

    /** Biggest file first, which in practice tracks the highest bitrate. */
    Quality,

    /** Peer count above all else. */
    Seeders,

    ;

    companion object {
        fun from(value: String?): StreamSelectionMode = when (value?.lowercase()) {
            "quality" -> Quality
            "seeders" -> Seeders
            else -> Balanced
        }
    }
}

/**
 * How a release names its resolution, in the coarse steps that matter for choosing one.
 * [Low] covers everything below 720p, [Unknown] a release that does not say.
 */
enum class ResolutionTier(val label: String?) {
    Uhd("4K"),
    Qhd("1440p"),
    FullHd("1080p"),
    Hd("720p"),
    Low("SD"),
    Unknown(null),
}

/**
 * `AppSettings.preferredResolution`: the resolution automatic selection aims for, and what it
 * falls back to, closest first, when no healthy release has it.
 *
 * Falling back downwards before upwards is deliberate: a viewer who picked 1080p usually did
 * so for the size of the file or the speed of the line, and a 4K file is the opposite of both.
 */
enum class PreferredResolution(
    /** What the settings store. */
    val setting: String,
    val label: String,
    internal val order: List<ResolutionTier>,
) {
    Uhd(
        "2160p",
        "4K",
        listOf(ResolutionTier.Uhd, ResolutionTier.Qhd, ResolutionTier.FullHd, ResolutionTier.Hd, ResolutionTier.Unknown, ResolutionTier.Low),
    ),
    FullHd(
        "1080p",
        "1080p",
        listOf(ResolutionTier.FullHd, ResolutionTier.Hd, ResolutionTier.Unknown, ResolutionTier.Qhd, ResolutionTier.Uhd, ResolutionTier.Low),
    ),
    Hd(
        "720p",
        "720p",
        listOf(ResolutionTier.Hd, ResolutionTier.FullHd, ResolutionTier.Low, ResolutionTier.Unknown, ResolutionTier.Qhd, ResolutionTier.Uhd),
    ),
    Sd(
        "480p",
        "480p",
        listOf(ResolutionTier.Low, ResolutionTier.Hd, ResolutionTier.Unknown, ResolutionTier.FullHd, ResolutionTier.Qhd, ResolutionTier.Uhd),
    ),
    ;

    companion object {
        /** The stored default; also what an unreadable value means. */
        val Default = FullHd

        fun from(value: String?): PreferredResolution =
            entries.firstOrNull { it.setting == value?.trim()?.lowercase() } ?: Default
    }
}

/** What the viewer asked to play, for checking a release name against. */
data class ReleaseTarget(
    /** Every name the title is published under: localized, English, original. */
    val titles: List<String>,
    /**
     * The title in other languages, each with the language it is in. A release named with one
     * is the same title, most likely in that language, which is for the language score to
     * weigh rather than for the name check to reject.
     */
    val translatedTitles: Map<String, String> = emptyMap(),
    /** A film's release year; ignored for series, whose packs carry the show's first year. */
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/** Whether a release is, by its name, the title that was asked for. */
enum class ReleaseMatch {
    /** The name carries one of the title's names and nothing contradicts it. */
    Match,

    /** The name says too little to tell: a bare "1080p WEB" with no title in it. */
    Unknown,

    /**
     * The name carries words that are not a recognisable form of the title. Usually still the
     * right title — addons answer by IMDb id — named in a way no rule anticipated, so it is
     * ranked lower and never refused: not finding the title is not evidence of anything.
     */
    Unrecognized,

    /** A film's name gives a different year: often a remake. Ranked well down, not refused. */
    WrongYear,

    /** The name gives a different episode. Never played automatically: it could spoil the series. */
    WrongEpisode,
}

/** What ranking concluded about one source, kept so the picker can explain it. */
data class SourceAssessment(
    val match: ReleaseMatch = ReleaseMatch.Unknown,
    val resolution: ResolutionTier = ResolutionTier.Unknown,
    val seeders: Int? = null,
    /** A cinema recording (CAM, TS, telecine, screener). */
    val theatricalCopy: Boolean = false,
    /** A torrent its provider reports nobody seeding; a debrid link's zero means nothing. */
    val deadSwarm: Boolean = false,
    val score: Double = 0.0,
) {
    /**
     * Whether Cove may start this source on the viewer's behalf. Everything stays in the
     * picker; this only decides what plays without asking.
     */
    val automaticallyPlayable: Boolean
        get() = match != ReleaseMatch.WrongEpisode && !theatricalCopy && !deadSwarm
}

data class RankedSource(val source: StreamSource, val assessment: SourceAssessment)

/** The settings that shape ranking, resolved once per listing. */
data class SourcePreferences(
    val mode: StreamSelectionMode = StreamSelectionMode.Balanced,
    val resolution: PreferredResolution = PreferredResolution.Default,
    /** Two-letter audio languages in the viewer's order, "original" already resolved. */
    val audioLanguages: List<String> = emptyList(),
    /**
     * The torrent the previous episode of this series played from. A season pack that worked
     * a moment ago starts the next episode with its metadata cached and its peers connected,
     * and keeps the release — and so the audio, subtitles and quality — the same.
     */
    val continuityHash: String? = null,
)

/**
 * Orders candidates so the first row is the one most likely to be wanted, best first.
 *
 * Every source is scored on the same terms and the terms add up, so a strong reason on one
 * axis can outweigh a weak one on another rather than the first key deciding everything:
 *
 * - **Resolution** — the preferred one first, then the closest fallbacks. A step costs about
 *   what thirty times the peers is worth, so a release one step away only wins when the
 *   preferred ones are barely seeded.
 * - **Swarm** — logarithmic in peers: going from 2 to 20 seeders matters, 200 to 2000 barely.
 * - **Size** — in Balanced, a file the swarm has to move more of is slower to start and more
 *   likely to stall, so a larger file pays a little; a file far below what its resolution
 *   needs is a poor encode, and pays a lot.
 * - **Language** — a release naming only other languages is almost certainly a dub.
 * - **The release name itself** — see [releaseMatch]. Unrecognized names rank lower; an
 *   explicitly different episode is never played automatically, whatever its score.
 *
 * The same torrent offered by two addons appears once, keeping its best-described copy.
 */
fun rankSources(
    sources: List<StreamSource>,
    preferences: SourcePreferences = SourcePreferences(),
    target: ReleaseTarget? = null,
): List<RankedSource> {
    val assessed = sources.mapIndexed { index, source ->
        Triple(index, source, assess(source, preferences, target))
    }
    val unique = assessed
        .groupBy { (index, source, _) -> source.duplicateKey() ?: "#$index" }
        .values
        .map { copies -> copies.maxBy { it.third.score } }
    return unique
        .sortedWith(
            compareByDescending<Triple<Int, StreamSource, SourceAssessment>> { it.third.automaticallyPlayable }
                .thenByDescending { it.third.score }
                .thenByDescending { it.third.seeders ?: -1 }
                .thenBy { it.second.sizeBytes.takeIf { size -> size > 0 } ?: Long.MAX_VALUE }
                .thenBy { it.first },
        )
        .map { (_, source, assessment) -> RankedSource(source, assessment) }
}

/**
 * The picker's order: highest resolution first, and within each resolution the ranking's own
 * order, best first. Ranking itself puts the preferred resolution first; a viewer browsing the
 * list instead wants to find it where it belongs — 4K above, 720p and below further down —
 * which is why the picker opens scrolled to [bestForPicker] rather than to the top.
 */
fun List<StreamChoice>.byResolution(): List<StreamChoice> =
    sortedBy { PICKER_RESOLUTION_ORDER.indexOf(it.assessment.resolution) }

/** What Watch plays: the first choice in ranking order that may play automatically. */
fun List<StreamChoice>.bestForPicker(): StreamChoice? = firstOrNull { it.eligibleForAutomaticPlayback() }

private val PICKER_RESOLUTION_ORDER = listOf(
    ResolutionTier.Uhd,
    ResolutionTier.Qhd,
    ResolutionTier.FullHd,
    ResolutionTier.Hd,
    ResolutionTier.Low,
    ResolutionTier.Unknown,
)

internal fun assess(
    source: StreamSource,
    preferences: SourcePreferences,
    target: ReleaseTarget?,
): SourceAssessment {
    val seeders = source.seederCount()
    val resolution = source.resolutionTier()
    val titleWords = (target?.titles.orEmpty() + target?.translatedTitles.orEmpty().keys)
        .flatMap(::matchTokens).toSet()
    val theatrical = source.isTheatricalCopy(titleWords)
    val deadSwarm = source.isKnownDeadTorrent()
    val check = target?.let(source::releaseCheck) ?: ReleaseCheck(ReleaseMatch.Unknown)
    val match = check.match
    // A release named in another language says what it is probably dubbed in, unless its own
    // tags already list its languages.
    val hints = source.audioHints().let { hints ->
        val titleLanguage = check.titleLanguage?.let(::canonicalLanguage)
        if (titleLanguage == null || hints.languages.isNotEmpty() || hints.multi) hints
        else hints.copy(languages = hints.languages + titleLanguage)
    }

    var score = 0.0
    score -= resolutionStep(preferences.mode) * preferences.resolution.order.indexOf(resolution).coerceAtLeast(0)
    score += swarmScore(source, seeders, preferences.mode)
    score += sizeScore(source.sizeBytes, resolution, target?.isEpisode == true, preferences.mode)
    score += languageScore(hints, preferences.audioLanguages)
    if (source.cached) score += CACHED_BONUS
    if (preferences.continuityHash != null &&
        source.infoHash.equals(preferences.continuityHash, ignoreCase = true)
    ) {
        score += CONTINUITY_BONUS
    }
    if (theatrical) score -= THEATRICAL_PENALTY
    score -= when (match) {
        ReleaseMatch.Match -> 0.0
        ReleaseMatch.Unknown -> UNKNOWN_RELEASE_PENALTY
        ReleaseMatch.Unrecognized -> UNRECOGNIZED_PENALTY
        ReleaseMatch.WrongYear -> WRONG_YEAR_PENALTY
        ReleaseMatch.WrongEpisode -> WRONG_EPISODE_PENALTY
    }
    if (deadSwarm) score -= DEAD_SWARM_PENALTY

    return SourceAssessment(
        match = match,
        resolution = resolution,
        seeders = seeders,
        theatricalCopy = theatrical,
        deadSwarm = deadSwarm,
        score = score,
    )
}

/**
 * A step away from the preferred resolution costs about what an order of magnitude more peers
 * is worth in each mode, so Most seeded holds the resolution as firmly as Balanced does.
 */
private fun resolutionStep(mode: StreamSelectionMode): Double = when (mode) {
    StreamSelectionMode.Seeders -> SEEDERS_RESOLUTION_STEP
    else -> RESOLUTION_STEP
}

/**
 * A source with no peer count still needs a value: a direct or debrid link has no swarm at
 * all and counts as comfortably healthy, a torrent whose provider did not say counts as thin.
 */
private fun swarmScore(source: StreamSource, seeders: Int?, mode: StreamSelectionMode): Double {
    val peers = seeders ?: if (source.isTorrentOnly()) UNKNOWN_SWARM_PEERS else DIRECT_LINK_PEERS
    val health = log10(1.0 + peers.coerceAtLeast(0))
    return when (mode) {
        StreamSelectionMode.Balanced -> BALANCED_SWARM_WEIGHT * flattened(health)
        StreamSelectionMode.Seeders -> SEEDERS_SWARM_WEIGHT * health
        StreamSelectionMode.Quality -> QUALITY_SWARM_WEIGHT * health -
            if (seeders != null && seeders < THIN_SWARM) QUALITY_THIN_PENALTY else 0.0
    }
}

/**
 * Past a healthy swarm, more peers barely change what the viewer sees — 120 and 400 seeders
 * both start at once and never stall — so Balanced lets the size decide there. Below it,
 * every doubling still counts in full.
 */
private fun flattened(health: Double): Double {
    val healthy = log10(1.0 + HEALTHY_SWARM)
    return if (health <= healthy) health else healthy + SATURATED_SLOPE * (health - healthy)
}

/**
 * Size against what the resolution needs for a film or an episode of typical length.
 *
 * Measured against a floor rather than against the other candidates, so one 80 GB remux in
 * the list does not make every ordinary encode look tiny.
 */
private fun sizeScore(sizeBytes: Long, resolution: ResolutionTier, episode: Boolean, mode: StreamSelectionMode): Double {
    if (sizeBytes <= 0) return -UNKNOWN_SIZE_PENALTY
    val hours = if (episode) EPISODE_HOURS else FILM_HOURS
    val floor = resolution.floorGigabytesPerHour() * hours * GIGABYTE
    val ratio = sizeBytes / floor
    if (ratio < 1.0) return -UNDERSIZED_WEIGHT * ln(1.0 / ratio)
    return when (mode) {
        StreamSelectionMode.Balanced -> -BALANCED_SIZE_WEIGHT * ln(ratio)
        StreamSelectionMode.Seeders -> -SEEDERS_SIZE_WEIGHT * ln(ratio)
        StreamSelectionMode.Quality -> QUALITY_SIZE_WEIGHT * ln(ratio)
    }
}

/** Roughly the smallest file per hour that still looks like its resolution. */
private fun ResolutionTier.floorGigabytesPerHour(): Double = when (this) {
    ResolutionTier.Uhd -> 1.2
    ResolutionTier.Qhd -> 0.8
    ResolutionTier.FullHd -> 0.35
    ResolutionTier.Hd -> 0.2
    ResolutionTier.Low -> 0.1
    ResolutionTier.Unknown -> 0.3
}

// ── Release name ───────────────────────────────────────────────────────────

/**
 * Whether this release is the title being asked for, judged on its name.
 *
 * Addons answer by IMDb id, so a release that names a different title is a translated
 * release ("I Griffin" for Family Guy, "Os Dinossauros" for The Dinosaurs — dubbed, with the
 * dub as the default track), a mislabelled file, or a pack that holds something else. The
 * file that will actually play is checked as well as the release: one "Fight Club" pack in
 * the wild contains a 2026 film instead.
 */
internal fun StreamSource.releaseMatch(target: ReleaseTarget): ReleaseMatch = releaseCheck(target).match

/** [ReleaseMatch], plus the language of the translated title the release was named with. */
internal data class ReleaseCheck(val match: ReleaseMatch, val titleLanguage: String? = null)

internal fun StreamSource.releaseCheck(target: ReleaseTarget): ReleaseCheck {
    val titles = target.titles.flatMap(::titleVariants).mapNotNull { tokensOrNull(it) }
    val translated = target.translatedTitles.flatMap { (title, language) ->
        titleVariants(title).mapNotNull { variant -> tokensOrNull(variant)?.let { it to language } }
    }
    if (titles.isEmpty() && translated.isEmpty()) return ReleaseCheck(ReleaseMatch.Unknown)
    val (release, files) = releaseLines()
    if (release == null) return ReleaseCheck(ReleaseMatch.Unknown)

    val releaseTokens = matchTokens(release)
    val names = listOf(releaseTokens) + files.map(::matchTokens)
    val titleTokens = (titles + translated.map { it.first }).flatten().toSet()

    // What plays is the file, so a file that plainly belongs to something else outweighs a
    // release named after the right title. Only a stated episode or year counts as plain:
    // positive evidence, as opposed to the title merely not being found.
    (if (files.isEmpty()) listOf(release) else files)
        .firstNotNullOfOrNull { contradiction(it, target, titleTokens) }
        ?.let { return ReleaseCheck(it) }

    if (names.any { tokens -> titles.any { titleMatches(it, tokens) } }) return ReleaseCheck(ReleaseMatch.Match)
    // A translated title is the same show, named the way another market names it.
    translated.firstOrNull { (title, _) -> names.any { tokens -> titleMatches(title, tokens) } }
        ?.let { (_, language) -> return ReleaseCheck(ReleaseMatch.Match, titleLanguage = language) }
    // Release names lead with the title and then describe the file. Words ahead of the first
    // year, episode or resolution are therefore the name the uploader gave it.
    val leading = releaseTokens.takeWhile { !isReleaseBoundary(it) }
    if (titles.any { title -> abbreviates(leading, title) }) return ReleaseCheck(ReleaseMatch.Match)
    // A name that opens straight onto "1080p WEB-DL" has no title to compare.
    return ReleaseCheck(if (leading.any(::isDescriptiveWord)) ReleaseMatch.Unrecognized else ReleaseMatch.Unknown)
}

/**
 * How uploaders shorten a title: its initials ("FG", "HIMYM", "GoT" — articles included,
 * as they are written) or its opening words ("How.I.S01E01"). Only the start of the name is
 * compared, so a word that happens to share initials further in counts for nothing.
 */
private fun abbreviates(leading: List<String>, title: List<String>): Boolean {
    if (leading.isEmpty() || title.size < 2) return false
    val initials = title.joinToString("") { it.take(1) }
    if (leading.first() == initials) return true
    return leading.size >= 2 && leading.size < title.size &&
        leading.indices.all { sameWord(leading[it], title[it]) }
}

private fun tokensOrNull(title: String): List<String>? = matchTokens(title).takeIf { it.isNotEmpty() }

/**
 * A title, and the parts either side of a colon or dash when each is a name in its own right.
 * Releases routinely drop one half: "Conversations with a Killer: The Ted Bundy Tapes" ships as
 * "Conversations.With.A.Killer.S01E01" and as "The.Ted.Bundy.Tapes.2018". A part of a single
 * word ("Mission") is too common to identify anything, so only longer parts count.
 */
private fun titleVariants(title: String): List<String> {
    val parts = title.split(TITLE_SEPARATORS).map(String::trim).filter { part ->
        part.isNotEmpty() && matchTokens(part).count { it !in STOP_WORDS } >= 2
    }
    return (listOf(title) + parts).distinct()
}

/** Where the title part of a release name ends. */
private fun isReleaseBoundary(token: String): Boolean =
    YEAR_TOKEN.matches(token) ||
        EPISODE_TOKEN.matches(token) ||
        RESOLUTION_TOKEN.matches(token) ||
        token in TECHNICAL_WORDS

/**
 * The release name and the names of the file(s) inside it.
 *
 * Torrentio-style text is the release on the first line, the file within a pack on the next,
 * then a line of statistics (`👤 47 💾 950 MB ⚙️ 1337x`) and sometimes a line of flags.
 */
private fun StreamSource.releaseLines(): Pair<String?, List<String>> {
    // Only the title carries a release name. A name is the provider's own label
    // ("Torrentio\n1080p"), which says nothing about which title this is.
    val lines = title.orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .toList()
    val descriptive = lines.takeWhile { line -> STATS_MARKERS.none { it in line } }
    val file = fileName()
    val release = descriptive.firstOrNull() ?: file
    val files = (descriptive.drop(1) + listOfNotNull(file))
        .map { it.substringAfterLast('/') }
        .filter { it != release }
        .distinct()
    return release to files
}

/** [ReleaseMatch.WrongEpisode] or [ReleaseMatch.WrongYear] when [text] says so, otherwise null. */
private fun contradiction(text: String, target: ReleaseTarget, titleTokens: Set<String>): ReleaseMatch? {
    val lower = text.lowercase()
    if (target.isEpisode) {
        val markers = episodeMarkers(lower)
        val wrong = markers.isNotEmpty() && markers.none { it.covers(target.season!!, target.episode!!) }
        return ReleaseMatch.WrongEpisode.takeIf { wrong }
    }
    val year = target.year ?: return null
    val years = YEAR.findAll(lower)
        .map { it.value }
        .filter { it !in titleTokens }
        .mapNotNull(String::toIntOrNull)
        .toList()
    val wrong = years.isNotEmpty() && years.none { kotlin.math.abs(it - year) <= 1 }
    return ReleaseMatch.WrongYear.takeIf { wrong }
}

private data class EpisodeMarker(val season: Int, val first: Int, val last: Int) {
    fun covers(season: Int, episode: Int) = this.season == season && episode in first..last
}

private fun episodeMarkers(text: String): List<EpisodeMarker> {
    val markers = mutableListOf<EpisodeMarker>()
    SEASON_EPISODE.findAll(text).forEach { match ->
        val season = match.groupValues[1].toInt()
        val first = match.groupValues[2].toInt()
        val last = match.groupValues[3].toIntOrNull()?.takeIf { it >= first } ?: first
        markers += EpisodeMarker(season, first, last)
    }
    CROSS_EPISODE.findAll(text).forEach { match ->
        val episode = match.groupValues[2].toInt()
        markers += EpisodeMarker(match.groupValues[1].toInt(), episode, episode)
    }
    return markers
}

/**
 * A title is found in a release when its words appear in order, when it appears with the
 * spaces taken out ("Spider-Man" against "SpiderMan"), or — for longer titles a release may
 * shorten — when most of its words are there, including the first.
 */
internal fun titleMatches(title: List<String>, release: List<String>): Boolean {
    if (title.isEmpty() || release.isEmpty()) return false
    if (containsRun(release, title)) return true
    val withoutArticle = title.dropWhile { it in LEADING_ARTICLES }
    if (withoutArticle.isNotEmpty() && withoutArticle.size < title.size && containsRun(release, withoutArticle)) {
        return true
    }
    val squashed = title.joinToString("")
    if (squashed.length >= MIN_SQUASHED_LENGTH && squashed in release.joinToString("")) return true
    val significant = title.filter { it !in STOP_WORDS }
    if (significant.size < 2) return false
    // In the title's own order, so "Guy Family" is not "Family Guy".
    var from = 0
    var found = 0
    for (word in significant) {
        val at = release.subList(from, release.size).indexOfFirst { sameWord(it, word) }
        if (at >= 0) {
            found++
            from += at + 1
        }
    }
    return found >= 2 && found >= significant.size * OVERLAP_THRESHOLD
}

private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
    if (needle.size > haystack.size) return false
    return (0..haystack.size - needle.size).any { start ->
        needle.indices.all { sameWord(haystack[start + it], needle[it]) }
    }
}

/**
 * Equal, or one slip apart in a word long enough for that to be a slip rather than another
 * word: "colour" and "color", "Breaking Bed". Short words must match exactly — "bad" and "bed"
 * are different words, and so are "up" and "us".
 */
internal fun sameWord(a: String, b: String): Boolean {
    if (a == b) return true
    if (minOf(a.length, b.length) < FUZZY_MIN_LENGTH || kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++
            j++
            continue
        }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> {
                i++
                j++
            }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

/**
 * Lowercase words with accents folded, apostrophes dropped ("Schindler's" → "schindlers"),
 * "&" as "and", and runs of single letters joined ("S.H.I.E.L.D." → "shield"). Roman
 * numerals from II up become digits, so "Rocky II" meets "Rocky 2".
 */
internal fun matchTokens(text: String): List<String> {
    val folded = buildString {
        text.lowercase().forEach { char -> append(ACCENT_FOLDS[char] ?: char.toString()) }
    }
    val words = folded
        .replace("&", " and ")
        .replace(APOSTROPHES, "")
        .split(NON_ALPHANUMERIC)
        .filter { it.isNotEmpty() }
    val joined = mutableListOf<String>()
    var letters = StringBuilder()
    for (word in words) {
        if (word.length == 1 && word[0].isLetter()) {
            letters.append(word)
            continue
        }
        if (letters.isNotEmpty()) {
            joined += letters.toString()
            letters = StringBuilder()
        }
        joined += ROMAN_NUMERALS[word] ?: word
    }
    if (letters.isNotEmpty()) joined += letters.toString()
    return joined
}

/** A word that names something, as opposed to describing the encode or the source. */
private fun isDescriptiveWord(token: String): Boolean =
    token.length >= 3 && token.all { it.isLetter() } && token !in TECHNICAL_WORDS

// ── Release properties ─────────────────────────────────────────────────────

internal fun StreamSource.resolutionTier(): ResolutionTier {
    // The provider's own label first: Torrentio's "Torrentio\n4k DV | HDR" is more reliable
    // than a release name that mentions "RM4K" as the source of a 1080p encode.
    for (text in listOf(name, title, fileName())) {
        val lower = text?.lowercase() ?: continue
        RESOLUTION_PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(lower) }?.let { return it.first }
    }
    return ResolutionTier.Unknown
}

/** A word of the title itself is not a marker: "Cam" (2018) is a film, not a recording of one. */
private fun StreamSource.isTheatricalCopy(titleWords: Set<String>): Boolean {
    val (release, _) = releaseLines()
    // .ts is a transport-stream container, not the TS (telesync) release tag.
    val tokens = release?.lowercase()?.removeSuffix(".ts")?.split(NON_ALPHANUMERIC)?.toSet() ?: return false
    return tokens.any { it in THEATRICAL_MARKERS && it !in titleWords }
}

internal fun StreamSource.isTorrentOnly(): Boolean = url.isNullOrBlank() && !infoHash.isNullOrBlank()

/** One torrent offered by two addons is the same source; a direct link is its own. */
private fun StreamSource.duplicateKey(): String? =
    infoHash?.takeIf { it.isNotBlank() && url.isNullOrBlank() }?.let { "${it.lowercase()}#${fileIdx ?: "-"}" }

// ── Weights ────────────────────────────────────────────────────────────────

private const val RESOLUTION_STEP = 1.5
private const val SEEDERS_RESOLUTION_STEP = 2.7
private const val BALANCED_SWARM_WEIGHT = 1.4
private const val SEEDERS_SWARM_WEIGHT = 2.5
private const val QUALITY_SWARM_WEIGHT = 0.6
private const val THIN_SWARM = 5
private const val QUALITY_THIN_PENALTY = 1.5
private const val UNKNOWN_SWARM_PEERS = 8
private const val DIRECT_LINK_PEERS = 40
private const val CACHED_BONUS = 1.0
private const val CONTINUITY_BONUS = 1.0
private const val UNDERSIZED_WEIGHT = 1.2
private const val BALANCED_SIZE_WEIGHT = 0.2
private const val HEALTHY_SWARM = 60
private const val SATURATED_SLOPE = 0.2
private const val SEEDERS_SIZE_WEIGHT = 0.03
private const val QUALITY_SIZE_WEIGHT = 0.6
private const val UNKNOWN_SIZE_PENALTY = 0.25
private const val THEATRICAL_PENALTY = 3.0
private const val UNKNOWN_RELEASE_PENALTY = 0.6
private const val UNRECOGNIZED_PENALTY = 1.5
private const val WRONG_YEAR_PENALTY = 4.0
private const val WRONG_EPISODE_PENALTY = 6.0
private const val FUZZY_MIN_LENGTH = 5
private const val DEAD_SWARM_PENALTY = 5.0
private const val EPISODE_HOURS = 0.75
private const val FILM_HOURS = 2.0
private const val GIGABYTE = 1024.0 * 1024.0 * 1024.0
private const val MIN_SQUASHED_LENGTH = 5
private const val OVERLAP_THRESHOLD = 0.6

private val STATS_MARKERS = listOf("👤", "💾", "⚙️", "⚙")

private val RESOLUTION_PATTERNS: List<Pair<ResolutionTier, Regex>> = listOf(
    ResolutionTier.Uhd to Regex("(?<![a-z0-9])(?:4320p|2160p|4k|uhd|8k)(?![a-z0-9])"),
    ResolutionTier.Qhd to Regex("(?<![a-z0-9])1440p(?![a-z0-9])"),
    ResolutionTier.FullHd to Regex("(?<![a-z0-9])(?:1080[pi]|fhd|fullhd)(?![a-z0-9])"),
    ResolutionTier.Hd to Regex("(?<![a-z0-9])720p(?![a-z0-9])"),
    ResolutionTier.Low to Regex("(?<![a-z0-9])(?:576p|480p|360p|240p|sd|dvdrip|dvd)(?![a-z0-9])"),
)

private val THEATRICAL_MARKERS = setOf(
    "cam", "camrip", "hdcam", "hqcam", "ts", "hdts", "telesync", "tc", "hdtc", "telecine",
    "scr", "screener", "dvdscr", "bdscr", "workprint",
)

private val YEAR = Regex("(?<!\\d)(?:19[2-9]\\d|20[0-4]\\d)(?!\\d)")
private val YEAR_TOKEN = Regex("19[2-9]\\d|20[0-4]\\d")
private val EPISODE_TOKEN = Regex("s\\d{1,2}(?:e\\d{1,3})*|\\d{1,2}x\\d{2,3}|e\\d{1,3}")
private val RESOLUTION_TOKEN = Regex("\\d{3,4}[pi]|4k|8k|uhd|fhd")
private val SEASON_EPISODE = Regex("(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?:(?:[ ._]*-[ ._]*e?|[ ._]*e)(\\d{1,3})(?![a-z0-9]))?(?!\\d)")
private val CROSS_EPISODE = Regex("(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![a-z0-9])")
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
private val APOSTROPHES = Regex("['’`´]")

private val TITLE_SEPARATORS = Regex("\\s*[:–—]\\s*|\\s+-\\s+")
private val LEADING_ARTICLES = setOf("the", "a", "an")
private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "in", "on", "at", "to", "for", "with")
private val ROMAN_NUMERALS = mapOf(
    "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9",
)

private val ACCENT_FOLDS: Map<Char, String> = buildMap {
    "àáâãäåāăą".forEach { put(it, "a") }
    "çćĉċč".forEach { put(it, "c") }
    "ďđ".forEach { put(it, "d") }
    "èéêëēĕėęě".forEach { put(it, "e") }
    "ĝğġģ".forEach { put(it, "g") }
    "ìíîïĩīĭįı".forEach { put(it, "i") }
    "ñńņňŉ".forEach { put(it, "n") }
    "òóôõöøōŏő".forEach { put(it, "o") }
    "ŕŗř".forEach { put(it, "r") }
    "śŝşš".forEach { put(it, "s") }
    "ţťŧ".forEach { put(it, "t") }
    "ùúûüũūŭůűų".forEach { put(it, "u") }
    "ýÿŷ".forEach { put(it, "y") }
    "źżž".forEach { put(it, "z") }
    "ł".forEach { put(it, "l") }
    put('ß', "ss")
    put('æ', "ae")
    put('œ', "oe")
    put('þ', "th")
    put('·', " ")
}

/** Words every release carries regardless of title: sources, codecs, audio, packaging. */
private val TECHNICAL_WORDS = setOf(
    "web", "webdl", "webrip", "webmux", "bluray", "bdrip", "brrip", "bdremux", "remux", "hdrip",
    "dvdrip", "hdtv", "pdtv", "uhd", "hdr", "sdr", "dolby", "vision", "atmos", "truehd", "dts",
    "aac", "eac", "ddp", "flac", "opus", "hevc", "avc", "xvid", "divx", "mkv", "mp4", "avi",
    "proper", "repack", "rerip", "extended", "unrated", "uncut", "remastered", "imax", "internal",
    "limited", "complete", "season", "seasons", "episode", "episodes", "series", "collection",
    "multi", "dual", "audio", "subs", "sub", "subtitles", "english", "eng", "ita", "spa", "fre",
    "ger", "rus", "jpn", "kor", "chi", "hin", "nordic", "amzn", "dsnp", "hmax", "atvp", "pcok",
    "hulu", "itunes", "max", "bbc", "itv", "cbs", "nbc", "abc", "fox", "amc", "netflix", "disney",
    "apple", "hbo", "mixed", "torrentio", "source", "video", "movie", "film", "edition", "cut",
    "version", "directors", "final", "integrale", "mesc", "sample", "sdh",
)
