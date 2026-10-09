package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.describedText
import com.coveninja.cove.shared.model.fileName

/**
 * How a candidate names itself: what the row leads with, what it is scanned by, and the file
 * underneath both.
 *
 * Beside the other source heuristics — [seederCount], [audioHints], [codecMetadata] — rather
 * than inside the picker that draws them, because the session reads them too: the release a
 * viewer picked is remembered by these same labels, so a picker-private copy would mean the
 * memory and the row disagreed about what the thing is called.
 */

/**
 * Providers put the resolution in whichever of name/title suits them, usually
 * alongside the release name. Pulling it out gives the row something scannable
 * to lead with; unknown is fine and falls back to a generic icon.
 */
internal fun StreamSource.qualityLabel(): String? {
    val haystack = describedText().lowercase()
    return when {
        "2160" in haystack || "4k" in haystack || "uhd" in haystack -> "4K"
        "1440" in haystack -> "1440p"
        "1080" in haystack -> "1080p"
        "720" in haystack -> "720p"
        "480" in haystack -> "480p"
        else -> null
    }
}

/**
 * The file name, unless the row's own heading already amounts to it.
 *
 * Compared on letters and digits alone so the two spellings of the same release — dots against
 * spaces, with or without the extension — count as the same thing, and the second line appears
 * only where it carries information the first does not.
 */
internal fun StreamSource.distinctFileName(): String? {
    val name = fileName()?.takeIf { it.isNotBlank() } ?: return null
    val heading = displayLabel()
    val bare = name.substringBeforeLast('.')
    return name.takeUnless { heading.comparable() == bare.comparable() || bare.comparable() in heading.comparable() }
}

private fun String.comparable(): String = filter(Char::isLetterOrDigit).lowercase()

/** The first non-blank line of whichever field carries the release name. */
internal fun StreamSource.displayLabel(): String {
    val candidate = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
    return candidate
        ?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.isNotEmpty() }
        ?: "Unnamed source"
}
