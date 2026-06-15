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
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

		// Answer preflight and stop — don't call next
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next(w, r)
	}
}
