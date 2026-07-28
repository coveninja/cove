package utils

import (
	"crypto/rand"
	"fmt"
	"strconv"
	"strings"
)

// ValidMediaType reports whether mediaType is one of Cove's title types.
func ValidMediaType(mediaType string) bool {
	return mediaType == "movie" || mediaType == "tv"
}

// FirstNonEmpty returns the first non-empty string, or "" when none exist.
func FirstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

// ParseMediaYear extracts the year from a TMDB-style YYYY-MM-DD date.
// Metadata dates are best-effort, so malformed values return zero.
func ParseMediaYear(date string) int {
	if len(date) < 4 {
		return 0
	}
	year, err := strconv.Atoi(date[:4])
	if err != nil {
		return 0
	}
	return year
}

// RewriteTMDBImageURL routes a legacy absolute TMDB image URL through Cove's
// local image cache. Already-relative and non-TMDB URLs are returned unchanged.
func RewriteTMDBImageURL(value string) string {
	if rest, ok := strings.CutPrefix(value, "https://image.tmdb.org/t/p/"); ok {
		return "http://" + LocalAddr() + "/api/img/" + rest
	}
	return value
}

// NewUUID returns a random RFC 4122 version 4 UUID.
func NewUUID() string {
	var bytes [16]byte
	_, _ = rand.Read(bytes[:])
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	return fmt.Sprintf(
		"%08x-%04x-%04x-%04x-%012x",
		bytes[0:4],
		bytes[4:6],
		bytes[6:8],
		bytes[8:10],
		bytes[10:],
	)
}
