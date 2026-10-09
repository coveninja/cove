package com.coveninja.cove.shared.model

import kotlinx.serialization.Serializable

/**
 * The subset of Stremio's `behaviorHints` that reaches the UI.
 *
 * Nested rather than flattened because this is the shape the backend already serialises:
 * `/api/v1/streams` answers with addons.AddonStream verbatim, so a flat field here would decode
 * as absent on the desktop and work only on Android, which builds its StreamSource in-process.
 */
@Serializable
data class StreamBehaviorHints(
    /**
     * The name of the file the provider is offering.
     *
     * The one piece of information a release title routinely does not carry: a provider's
     * `title` is a display line with the size and the seeder count in it, and for a torrent
     * holding a whole season the title names the release while only this names the episode.
     */
    val filename: String = "",
)

// Maps to the addons.Stream shape returned by /api/streams.
//
// The backend answers with the richer addons.AddonStream; CoveJson sets
// ignoreUnknownKeys, so this stays a deliberate subset. The second group exists
// because the source picker and the torrent play URL need it — sizeBytes and
// cached to rank and label candidates, fileIdx because a torrent carrying
// several files cannot be narrowed to one without it.
@Serializable
data class StreamSource(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val addonName: String? = null,
    val sizeBytes: Long = 0,
    val fileIdx: Int? = null,
    val cached: Boolean = false,
    val behaviorHints: StreamBehaviorHints? = null,
)

/**
 * The file this source will play, as a person would recognise it, or null if nothing says.
 *
 * Worth showing on its own because it answers questions the release title cannot. Whether the
 * audio is the dub, which group's encode this is, and — for a torrent holding a whole season —
 * which episode is actually being offered are all in the file name and routinely nowhere else.
 *
 * The provider's own hint comes first. Failing that, a direct URL ending in a video file is the
 * next most honest answer: the Nuvio scrapers and most debrid links end in the real name, and
 * reading it off the address is better than showing nothing. Anything that is not plainly a
 * video file name — an opaque token, a path ending in `/stream`, a query string — yields null
 * rather than a guess, because a wrong file name in this row is worse than an absent one.
 */
fun StreamSource.fileName(): String? {
    behaviorHints?.filename?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val leaf = url
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return leaf.takeIf { VIDEO_FILE_NAME.matches(it) }
}

/**
 * Every word a release name carries, file name included.
 *
 * The codec and quality heuristics read this rather than name and title alone: a provider whose
 * display line says only "1080p ⚙️ Provider" very often has `x265` and `WEB-DL` in the file
 * name, and judging the source without it is judging half of it.
 */
fun StreamSource.describedText(): String =
    listOfNotNull(name, title, fileName()).joinToString(" ")

private val VIDEO_FILE_NAME = Regex(
    """^[^/\\]{4,}\.(?:mkv|mp4|m4v|avi|mov|webm|ts|m2ts)$""",
    RegexOption.IGNORE_CASE,
)
