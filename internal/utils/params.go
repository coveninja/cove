package utils

// ParseMediaType, ParseLimit, and SmallBodyLimit promote the semantics of
// three private helpers that are scattered across internal/discover and
// internal/player into one shared location so future handlers don't
// re-invent them.
//
// ParseMediaType matches discover.mediaTypeParam exactly: returns the raw
// query value and true only when it equals "movie" or "tv"; any other value
// (absent, empty, "Movie", garbage) returns ("", false).
//
// ParseLimit matches discover.limitParam exactly: returns def when the
// parameter is absent, empty, non-numeric, or <= 0; clamps to max when the
// parsed value exceeds it; otherwise returns the value as-is. The clamped
// range is effectively [1, max] in practice — a value of 0 returns def, and
// callers always pass a positive def — but the helper does not enforce a
// hard minimum of 1 because that is not what the original code does.
//
// SmallBodyLimit is the 64 KiB cap used by the twelve sites that call
// http.MaxBytesReader(w, r.Body, 64<<10). CorsMiddleware applies a looser
// 1 MiB outer cap (maxBodyBytes) — these inner caps are deliberately tighter
// because handler-specific bodies (search queries, stream-select payloads)
// have no business being large.

import (
	"net/http"
	"strconv"
)

// SmallBodyLimit is the 64 KiB inner body cap for handlers that accept
// JSON request bodies. Use it with http.MaxBytesReader instead of the
// bare literal 64<<10.
const SmallBodyLimit = 64 << 10

// ParseMediaType returns the ?type= query parameter and whether it is a
// recognised media type. Only "movie" and "tv" are valid; any other value,
// including an absent or empty parameter, returns ("", false).
func ParseMediaType(r *http.Request) (string, bool) {
	mt := r.URL.Query().Get("type")
	return mt, ValidMediaType(mt)
}

// ParseLimit returns the ?limit= query parameter parsed as a positive
// integer, clamped to [1, max]. Returns def when the parameter is absent,
// cannot be parsed, or is <= 0. Returns max when the parsed value exceeds it.
func ParseLimit(r *http.Request, def, max int) int {
	v, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || v <= 0 {
		return def
	}
	if v > max {
		return max
	}
	return v
}
