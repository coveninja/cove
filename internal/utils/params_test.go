package utils

import (
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestParseMediaType(t *testing.T) {
	tests := []struct {
		name      string
		query     string
		wantValue string
		wantOk    bool
	}{
		{"movie", "?type=movie", "movie", true},
		{"tv", "?type=tv", "tv", true},
		{"absent", "", "", false},
		{"empty string", "?type=", "", false},
		{"uppercase Movie", "?type=Movie", "Movie", false},
		{"garbage", "?type=film", "film", false},
		{"series", "?type=series", "series", false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "/"+tc.query, nil)
			v, ok := ParseMediaType(req)
			assert.Equal(t, tc.wantOk, ok)
			assert.Equal(t, tc.wantValue, v)
		})
	}
}

func TestParseLimit(t *testing.T) {
	const def = 20
	const max = 100
	tests := []struct {
		name  string
		query string
		want  int
	}{
		{"absent", "", def},
		{"empty string", "?limit=", def},
		{"garbage", "?limit=abc", def},
		{"zero", "?limit=0", def},
		{"negative", "?limit=-1", def},
		{"one", "?limit=1", 1},
		{"within range", "?limit=50", 50},
		{"at max", "?limit=100", max},
		{"over max", "?limit=101", max},
		{"way over max", "?limit=99999", max},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "/"+tc.query, nil)
			assert.Equal(t, tc.want, ParseLimit(req, def, max))
		})
	}
}

func TestSmallBodyLimit(t *testing.T) {
	// Verify the constant value matches the literal used in the 12 call sites.
	assert.Equal(t, 64<<10, SmallBodyLimit)
}
