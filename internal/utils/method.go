package utils

// MethodGuard returns a handler that enforces a single allowed HTTP method.
// Mismatches get a 405 with an Allow header; matching requests are forwarded
// to next unchanged.
//
// Composition order matters: MethodGuard must be wrapped by CorsMiddleware,
// never the reverse:
//
//	utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, h))
//
// CorsMiddleware intercepts OPTIONS and replies 204 before the wrapped handler
// runs. If MethodGuard were on the outside it would see OPTIONS, decide it
// doesn't match the allowed method, and respond 405 — killing every CORS
// preflight. The canonical shape in the codebase is the getOnly closure at
// internal/tmdb/tmdb.go, which this replaces.

import "net/http"

func MethodGuard(method string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != method {
			w.Header().Set("Allow", method)
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		next(w, r)
	}
}
