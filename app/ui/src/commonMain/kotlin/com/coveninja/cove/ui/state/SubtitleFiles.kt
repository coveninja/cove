package com.coveninja.cove.ui.state

/**
 * Reading a subtitle file the viewer supplied — dropped on the player, or chosen from
 * the subtitle menu.
 *
 * Pure on purpose: nothing here touches the filesystem. The platform hands over a path
 * and these decide whether it is worth giving to mpv and what to call it once it is a
 * track in the menu, which is the whole of the logic and the only part a test can reach.
 */

/**
 * What mpv will read as a subtitle. Deliberately a list of what it handles rather than
 * everything that exists: an unknown extension handed to `sub-add` fails inside the
 * player, where the viewer sees nothing but a file that did not appear.
 *
 * `idx` is the half of a VobSub pair to offer — mpv finds the `.sub` beside it, and a
 * `.sub` on its own is MicroDVD, which it also reads.
 */
val SUBTITLE_FILE_EXTENSIONS: List<String> = listOf(
    "srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "smi", "mpl2", "ttml", "dfxp", "mks",
)

/** Whether [path] names a file mpv can load as a subtitle. */
fun isSubtitleFile(path: String): Boolean =
    subtitleFileExtension(path) in SUBTITLE_FILE_EXTENSIONS

/**
 * The last segment of [path], which becomes the mpv track title and so the label in the
 * subtitle menu. Kept whole, extension included: the extension is often the only thing
 * telling two files of the same release apart.
 */
fun subtitleFileName(path: String): String {
    val separator = path.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (separator < 0) path else path.substring(separator + 1)
}

/**
 * The language tag a subtitle file names itself with — the `en` of `Movie.2024.en.srt` —
 * or an empty string when it does not name one.
 *
 * Empty is a perfectly good answer: mpv takes it, and the menu files the track under
 * Unknown, which is honest. Guessing is the failure worth avoiding, and [knownLanguageTag]
 * is what keeps `Movie.2024.web.srt` from being filed under a language called WEB.
 */
fun subtitleFileLanguage(path: String): String {
    val name = subtitleFileName(path)
    val extension = subtitleFileExtension(path)
    if (extension.isEmpty()) return ""
    val stem = name.dropLast(extension.length + 1)
    // A file called plainly `en.srt` names its language and nothing else, so the whole
    // stem is the candidate when there is no earlier segment to take it from.
    val segment = if (stem.contains('.')) stem.substringAfterLast('.') else stem
    return knownLanguageTag(segment).orEmpty()
}

/**
 * The subtitle files among a dropped set, in the order they arrived.
 *
 * A drop carries whatever the viewer had selected, which is often a subtitle and the
 * video beside it. Filtering here rather than refusing the whole drop means the obvious
 * gesture — grab both, drop both — does the obvious thing.
 */
fun subtitleFilesAmong(paths: List<String>): List<String> = paths.filter(::isSubtitleFile)

/** Lowercased, without the dot; empty when the name has no extension at all. */
private fun subtitleFileExtension(path: String): String =
    subtitleFileName(path).substringAfterLast('.', "").lowercase()
