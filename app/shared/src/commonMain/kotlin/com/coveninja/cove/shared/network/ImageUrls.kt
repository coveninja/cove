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
