package utils

import (
	"regexp"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestValidMediaType(t *testing.T) {
	assert.True(t, ValidMediaType("movie"))
	assert.True(t, ValidMediaType("tv"))
	assert.False(t, ValidMediaType(""))
	assert.False(t, ValidMediaType("person"))
	assert.False(t, ValidMediaType("Movie"))
}

func TestFirstNonEmpty(t *testing.T) {
	assert.Equal(t, "second", FirstNonEmpty("", "second", "third"))
	assert.Empty(t, FirstNonEmpty("", ""))
}

func TestParseMediaYear(t *testing.T) {
	assert.Equal(t, 2026, ParseMediaYear("2026-07-28"))
	assert.Zero(t, ParseMediaYear(""))
	assert.Zero(t, ParseMediaYear("bad"))
	assert.Zero(t, ParseMediaYear("xx26-07-28"))
}

func TestRewriteTMDBImageURL(t *testing.T) {
	previous := LocalAddr()
	SetLocalAddr("192.0.2.10:7000")
	t.Cleanup(func() { SetLocalAddr(previous) })

	assert.Equal(
		t,
		"http://192.0.2.10:7000/api/img/w500/poster.jpg",
		RewriteTMDBImageURL("https://image.tmdb.org/t/p/w500/poster.jpg"),
	)
	assert.Equal(t, "/poster.jpg", RewriteTMDBImageURL("/poster.jpg"))
	assert.Equal(t, "https://example.com/poster.jpg", RewriteTMDBImageURL("https://example.com/poster.jpg"))
}

func TestNewUUID(t *testing.T) {
	value := NewUUID()
	assert.Regexp(
		t,
		regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`),
		value,
	)
	assert.NotEqual(t, value, NewUUID())
}
