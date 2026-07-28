package utils

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestWriteJSON_SetsContentTypeHeader(t *testing.T) {
	rr := httptest.NewRecorder()
	WriteJSON(rr, map[string]string{"hello": "world"})
	assert.Equal(t, "application/json", rr.Header().Get("Content-Type"))
}

func TestWriteJSON_EncodesValue(t *testing.T) {
	type payload struct {
		ID   int    `json:"id"`
		Name string `json:"name"`
	}
	want := payload{ID: 42, Name: "cove"}
	rr := httptest.NewRecorder()
	WriteJSON(rr, want)

	var got payload
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&got))
	assert.Equal(t, want, got)
}

func TestWriteJSON_ImpliedStatus200(t *testing.T) {
	// WriteJSON does not call WriteHeader explicitly; net/http defaults to 200.
	rr := httptest.NewRecorder()
	WriteJSON(rr, struct{}{})
	assert.Equal(t, http.StatusOK, rr.Code)
}

func TestWriteJSON_SliceAndNilRoundtrip(t *testing.T) {
	tests := []struct {
		name string
		v    any
	}{
		{"nil", nil},
		{"empty slice", []int{}},
		{"non-empty slice", []int{1, 2, 3}},
		{"nested map", map[string]any{"ok": true, "n": 7}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rr := httptest.NewRecorder()
			WriteJSON(rr, tc.v)
			assert.Equal(t, "application/json", rr.Header().Get("Content-Type"))
			assert.Greater(t, rr.Body.Len(), 0)
		})
	}
}
