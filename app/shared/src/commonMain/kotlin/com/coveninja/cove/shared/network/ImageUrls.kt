package com.coveninja.cove.shared.network

// Image paths from the backend are inconsistent across endpoints:
//   Most routes (/api/library, /api/search/multi, /api/details, /api/media) run
//   paths through utils.RewriteTMDBImageURL and return already-absolute proxied
//   URLs like http://127.0.0.1:6969/api/img/w500/abc.jpg.
//   /api/images returns raw TMDB paths like /abc.jpg.
//
// Passing an already-absolute URL to the proxy builder double-wraps it and the
// backend answers 400. This single helper must be used everywhere an image path
// is converted to a display URL — never build the proxy URL inline.
fun resolveImageUrl(baseUrl: String, path: String?, size: String = "w500"): String? {
    path ?: return null
    // Already-absolute URLs come from the proxy-rewriting endpoints; pass through.
    if (path.startsWith("http")) return path
    // Raw TMDB paths begin with /; the route is /api/img/{size}/{file}.
    return "$baseUrl/api/img/$size$path"
}

/**
 * Produces a directly loadable TMDB CDN URL from every image representation Cove has stored.
 *
 * Legacy Go/WebView builds persisted absolute loopback proxy URLs in library rows. Those URLs
 * name the old process on the current device, not the image, and become dead after migration or
 * sync. Recover the stable TMDB file name from them instead of trying localhost. Current raw
 * paths and CDN URLs are normalized to the requested size; unrelated absolute URLs (YouTube
 * thumbnails, addon artwork) pass through.
 */
fun resolveTmdbImageUrl(path: String?, size: String = "w500"): String? {
    val value = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val file = LEGACY_LOOPBACK_IMAGE.matchEntire(value)?.groupValues?.get(1)
        ?: TMDB_CDN_IMAGE.matchEntire(value)?.groupValues?.get(1)
        ?: value.takeIf { !it.startsWith("http://") && !it.startsWith("https://") }
            ?.trimStart('/')
        ?: return value
    return "https://image.tmdb.org/t/p/$size/${file.trimStart('/')}"
}

private val LEGACY_LOOPBACK_IMAGE = Regex(
    """^https?://(?:127\.0\.0\.1|localhost)(?::\d+)?/api/(?:v1/)?img/[^/]+/(.+)$""",
    RegexOption.IGNORE_CASE,
)
private val TMDB_CDN_IMAGE = Regex(
    """^https?://image\.tmdb\.org/t/p/[^/]+/(.+)$""",
    RegexOption.IGNORE_CASE,
)
