// Package utils holds small helpers shared across internal packages: per-OS
// config-directory resolution, crash-safe atomic file writes for the JSON
// stores, SRT-to-VTT subtitle conversion, and CorsMiddleware, which wraps
// nearly every HTTP handler in the app and auto-answers OPTIONS requests
// with 204 before the wrapped handler ever runs.
package utils

import (
	"net/http"
	"strings"
)

// localAddr records the address the main HTTP listener was bound to.
// Defaults to 127.0.0.1:6969 so test/standalone use works without a call to
// SetLocalAddr. Only written once at server startup before handlers serve.
var localAddr = "127.0.0.1:6969"

// SetLocalAddr records the bind address for image-proxy URL builders
// (imgURL in internal/tmdb and rewritePosterURL in internal/library).
// Call before any HTTP handlers serve requests.
func SetLocalAddr(addr string) {
	if addr != "" {
		localAddr = addr
	}
}

// LocalAddr returns the recorded bind address for constructing backend-relative URLs.
func LocalAddr() string {
	return localAddr
}

// allowedOrigins are the only browser origins permitted to call the API:
// the Qt shell's static server (:5174) and the Vite dev server (:5173).
// Requests without an Origin header (the shell's probe, mpv, curl) are not
// browser-CORS requests and pass through untouched.
var allowedOrigins = map[string]bool{
	"http://127.0.0.1:5174": true,
	"http://localhost:5174": true,
	"http://127.0.0.1:5173": true,
	"http://localhost:5173": true,
}

// maxBodyBytes caps request bodies app-wide; the largest legitimate payload
// is a profile's library/settings JSON, well under 1 MiB.
const maxBodyBytes = 1 << 20

func SrtToVTT(srt string) string {
	// SRT timestamps use commas; VTT uses dots. That's the only difference.
	vtt := strings.ReplaceAll(srt, ",", ".")
	return "WEBVTT\n\n" + vtt
}

func CorsMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Add("Vary", "Origin")
		origin := r.Header.Get("Origin")

		if origin != "" {
			// Same-origin: the page was served by this server itself (the
			// embedded web UI on Android loads from the backend's own address).
			// A hostile page on another origin can never match r.Host here, so
			// this keeps the CSRF posture of the allowlist intact.
			sameOrigin := origin == "http://"+r.Host

			if allowedOrigins[origin] || sameOrigin {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, Origin, X-Requested-With")
				w.Header().Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, X-Total-Count")
			} else {
				// Unknown origin: never emit ACAO (browser blocks the read),
				// and refuse state-changing methods outright so a hostile
				// page can't fire blind CSRF-style writes at the local API.
				switch r.Method {
				case http.MethodGet, http.MethodHead:
				default:
					http.Error(w, "forbidden origin", http.StatusForbidden)
					return
				}
			}
		}

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		}

		next(w, r)
	}
}
