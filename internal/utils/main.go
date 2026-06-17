package utils

import (
	"net/http"
	"strings"
)

func SrtToVTT(srt string) string {
	// SRT timestamps use commas; VTT uses dots. That's the only difference.
	vtt := strings.ReplaceAll(srt, ",", ".")
	return "WEBVTT\n\n" + vtt
}

func CorsMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, Origin, X-Requested-With")
		w.Header().Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, X-Total-Count")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next(w, r)
	}
}
