package com.coveninja.cove.ui.components.player

import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.UNKNOWN_LANGUAGE
import com.coveninja.cove.ui.state.canonicalLanguage
import com.coveninja.cove.ui.state.languageName

/** Tracks sharing a language, in the order the file lists them. */
internal data class TrackGroup(
    val languageLabel: String,
    val tracks: List<MediaTrack>,
    /** The language's two-letter code, or its raw tag when unrecognised; empty when unknown. */
    val code: String = "",
)

/**
 * Groups tracks by language for the picker.
 *
 * A release can carry several subtitles in one language — full, signs-and-songs,
 * SDH, or regional cuts like es-419 next to es-ES — and a flat list of those is
 * unreadable. Regional variants collapse into their base language so they sit
 * together, with whatever distinguishes them left on the individual entries.
 *
 * Ordering is alphabetical with unknown languages last, deliberately independent
 * of what is currently selected: a menu that reorders itself between openings is
 * harder to use than one that is merely long.
 */
internal fun groupTracksByLanguage(
    tracks: List<MediaTrack>,
    /**
     * The viewer's languages, two-letter codes in order. They lead the menu in that order —
     * still independent of what is selected, so the menu keeps its shape between openings.
     */
    preferred: List<String> = emptyList(),
): List<TrackGroup> =
    tracks
        // Every spelling of a language is one group: "en", "eng" and "en-US" used to be three
        // sections each headed "English", which also gave the television duplicate list keys.
        .groupBy { it.languageCode() }
        .map { (code, grouped) -> TrackGroup(languageName(code), grouped, code) }
        .sortedWith(
            compareBy(
                { preferred.indexOf(it.code).let { index -> if (index < 0) Int.MAX_VALUE else index } },
                { it.languageLabel == UNKNOWN_LANGUAGE },
                { it.languageLabel.lowercase() },
            ),
        )

/** What a filtered menu shows, and how many tracks it is keeping out of sight. */
internal data class TrackMenu(val groups: List<TrackGroup>, val hiddenTracks: Int)

/**
 * Only the viewer's languages, for a menu that would otherwise bury them.
 *
 * A release can carry eighty subtitle tracks, and English and Swedish sat 29th and 37th among
 * them. Kept regardless of language: the track that is selected, so the menu always shows what
 * is on screen, and tracks with no language at all, which could be anything. With no
 * preference there is nothing to filter by and everything is shown.
 */
internal fun filterTrackGroups(
    groups: List<TrackGroup>,
    preferred: List<String>,
    selectedId: Int?,
): TrackMenu {
    if (preferred.isEmpty()) return TrackMenu(groups, 0)
    val shown = groups.filter { group ->
        group.code in preferred || group.code.isEmpty() || group.tracks.any { it.id == selectedId }
    }
    return TrackMenu(shown, groups.filter { it !in shown }.sumOf { it.tracks.size })
}

/** The track's language as one code whatever form it came in, or its raw base tag. */
internal fun MediaTrack.languageCode(): String = canonicalLanguage(language) ?: baseLanguage()

/**
 * The part before any region subtag, lowercased. `es-419` and `es-ES` are both
 * Spanish; keeping them apart would defeat the grouping.
 */
internal fun MediaTrack.baseLanguage(): String =
    language
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.substringBefore('-')
        ?.lowercase()
        .orEmpty()

/**
 * What to show for a track inside its language group.
 *
 * The track's own title is the useful thing — "Signs & Songs", "Latin America",
 * "SDH". Plenty of releases title nothing at all, though, and the fallback used to be
 * the track's position in the file: a menu offering "Track 2" and "Track 3" tells a
 * viewer nothing about which one to pick. The codec and channel layout do, and they
 * are the difference that usually matters between two tracks of the same language —
 * the 5.1 original against the stereo downmix.
 */
internal fun MediaTrack.detailLabel(): String {
    val name = title.trim().takeIf { it.isNotEmpty() }
        ?: language?.trim()?.takeIf { it.contains('-') }
    val technical = listOfNotNull(
        codec.trim().takeIf { it.isNotEmpty() },
        channels.trim().takeIf { it.isNotEmpty() && it != "unknown" }?.let(::channelLabel),
    )
    return when {
        name != null && technical.isEmpty() -> name
        name != null -> "$name · ${technical.joinToString(" ")}"
        technical.isNotEmpty() -> technical.joinToString(" ")
        // Nothing is known about it beyond that it exists. Still better than nothing:
        // the id is at least stable within this file.
        else -> "Track $id"
    }
}

/**
 * mpv's channel layout names, tidied for a menu.
 *
 * It reports layouts the way FFmpeg spells them — "5.1(side)", "7.1(wide)" — where the
 * qualifier in brackets describes speaker placement nobody chooses a track by. "mono"
 * and "stereo" are left as words because that is how people say them.
 */
private fun channelLabel(channels: String): String =
    channels.substringBefore('(').trim().ifEmpty { channels }

/**
 * The short words that say what a track is *for*, beyond its language.
 *
 * These are the facts a release encodes in flags rather than in the title, and until now
 * Cove read none of them: a forced subtitle track carrying only the alien dialogue looked
 * identical in the menu to the full one beside it, and picking wrong meant missing every
 * line of the film. Ordered by how much they change what the viewer would get.
 *
 * [MediaTrack.isDefault] is deliberately last and deliberately included — on its own it is
 * weak information, but it is what explains why a particular track was already selected.
 */
internal fun MediaTrack.badges(): List<String> = buildList {
    if (forced) add("Forced")
    if (hearingImpaired) add("SDH")
    if (visualImpaired) add("Audio description")
    if (external) add("Add-on")
    // Only worth saying for subtitles, and only because it explains why the appearance
    // settings do nothing to them: mpv can move and scale a picture but not restyle it.
    if (bitmap) add("Image")
    if (isDefault) add("Default")
}
