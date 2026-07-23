package tmdb

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestParseQualityID(t *testing.T) {
	tests := []struct {
		name          string
		input         string
		wantTypedID   string
		wantMediaType string
		wantTmdbID    int
		wantOK        bool
	}{
		{"typed movie", "movie:603", "movie:603", "movie", 603, true},
		{"typed tv", "tv:1396", "tv:1396", "tv", 1396, true},
		{"bare defaults to movie", "603", "movie:603", "movie", 603, true},
		{"whitespace trimmed", " 603 ", "movie:603", "movie", 603, true},
		{"unknown prefix rejected", "foo:603", "", "", 0, false},
		{"invalid number", "movie:abc", "", "", 0, false},
		{"zero id rejected", "movie:0", "", "", 0, false},
		{"empty", "", "", "", 0, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			typedID, mediaType, tmdbID, ok := parseQualityID(tc.input)
			assert.Equal(t, tc.wantOK, ok)
			if tc.wantOK {
				assert.Equal(t, tc.wantTypedID, typedID)
				assert.Equal(t, tc.wantMediaType, mediaType)
				assert.Equal(t, tc.wantTmdbID, tmdbID)
			}
		})
	}
}

func TestQualityCache_HitAndTTLSeparation(t *testing.T) {
	c := New("key")

	_, hit := c.qualityCacheGet("movie:603")
	assert.False(t, hit, "empty cache should miss")

	c.qualityCacheSet("movie:603", "1080p", qualityCacheTTLHit)
	q, hit := c.qualityCacheGet("movie:603")
	assert.True(t, hit)
	assert.Equal(t, "1080p", q)

	// A different typed id (same numeric id, different media type) must not
	// collide with the movie entry.
	_, hit = c.qualityCacheGet("tv:603")
	assert.False(t, hit)
}

func TestQualityCache_ExpiredEntryIsMiss(t *testing.T) {
	c := New("key")
	// Negative TTL — already expired the instant it's set.
	c.qualityCacheSet("movie:1", "720p", -time.Second)
	_, hit := c.qualityCacheGet("movie:1")
	assert.False(t, hit)
}
