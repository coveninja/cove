package utils

// WriteJSON encodes v as JSON into w, setting Content-Type beforehand.
// Encode errors are logged rather than silently swallowed — five near-duplicate
// helpers across the codebase each handled this differently (supabase and
// profiles swallowed the error with _; library, discover, and trakt logged it);
// this shared version normalises onto always logging so a mis-typed response
// body doesn't vanish without a trace.
//
// The caller is responsible for writing an error status before calling WriteJSON
// when the response represents a failure; this helper unconditionally sets
// Content-Type and calls Encode, which implies a 200 if the caller hasn't
// written a status yet.

import (
	"encoding/json"
	"log"
	"net/http"
)

func WriteJSON(w http.ResponseWriter, v any) {
	WriteJSONStatus(w, http.StatusOK, v)
}

// WriteJSONStatus writes a JSON response with an explicit HTTP status.
func WriteJSONStatus(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Println("utils: json encode:", err)
	}
}
