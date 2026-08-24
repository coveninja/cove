package com.coveninja.cove.backend.http

/**
 * Whether a probe response looks like media rather than a web page.
 *
 * A dead or unresolved provider link is very often a live HTML page — an interstitial, a
 * "file not found" template, a download portal that needs another click. Those answer 200, so
 * status alone judged them playable and the picker offered them; mpv then opened one, found no
 * container in it and said "Failed to recognize file format", which reads as Cove being broken
 * rather than the source being wrong.
 *
 * Deliberately a denylist of document types rather than an allowlist of media ones: hosts serve
 * video under every kind of vague or wrong content type, and refusing everything unrecognised
 * would throw away far more working sources than it saved.
 */
internal fun looksLikePlayableContentType(contentType: String?): Boolean {
    val value = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
    if (value.isEmpty()) return true
    return DOCUMENT_TYPES.none(value::startsWith)
}

private val DOCUMENT_TYPES = listOf(
    "text/html",
    "application/xhtml",
)
