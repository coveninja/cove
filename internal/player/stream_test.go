package player

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func response(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(body)),
	}
}

func TestStreamRegistryClonesRefreshesAndExpiresEntries(t *testing.T) {
	p := &Player{streamHeaders: utils.NewTTLCache[string, streamHeaderEntry](0)}
	original := map[string]string{"Referer": "https://catalog.example/"}
	p.rememberStream("https://cdn.example/video.mkv", original)
	original["Referer"] = "https://mutated.example/"

	headers, known := p.lookupStream("https://cdn.example/video.mkv")
	if !known || headers["Referer"] != "https://catalog.example/" {
		t.Fatalf("lookup = %v, %v", headers, known)
	}
	headers["Referer"] = "https://caller-mutated.example/"
	headersAgain, known := p.lookupStream("https://cdn.example/video.mkv")
	if !known || headersAgain["Referer"] != "https://catalog.example/" {
		t.Fatalf("stored headers were mutable through lookup: %v", headersAgain)
	}

	// TTLCache: use Set with a negative TTL to store an already-expired entry.
	p.streamHeaders.Set("https://expired.example/video", streamHeaderEntry{}, -time.Second)
	if _, known := p.lookupStream("https://expired.example/video"); known {
		t.Fatal("expired stream remained authorized")
	}
	// TTLCache.Get does not sweep expired entries — they are swept by the next
	// Set call. Len() still counts them until swept; that is expected.

	lenBefore := p.streamHeaders.Len()
	p.rememberStream("", map[string]string{"ignored": "true"})
	if p.streamHeaders.Len() != lenBefore {
		t.Fatalf("empty URL changed registry size from %d to %d", lenBefore, p.streamHeaders.Len())
	}
}

func TestProbeURLUsesHEADAndRequiredHeaders(t *testing.T) {
	p := &Player{probeClient: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Method != http.MethodHead {
			t.Fatalf("method = %s, want HEAD", r.Method)
		}
		if got := r.Header.Get("Referer"); got != "https://catalog.example/" {
			t.Fatalf("Referer = %q", got)
		}
		resp := response(http.StatusOK, "")
		resp.ContentLength = 12345
		return resp, nil
	})}}

	alive, size := p.probeURL(context.Background(), "https://cdn.example/video", map[string]string{
		"Referer": "https://catalog.example/",
	})
	if !alive || size != 12345 {
		t.Fatalf("probeURL = %v, %d", alive, size)
	}
}

func TestProbeURLFallsBackToRangeGET(t *testing.T) {
	requests := 0
	p := &Player{probeClient: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		requests++
		if requests == 1 {
			if r.Method != http.MethodHead {
				t.Fatalf("first method = %s, want HEAD", r.Method)
			}
			return response(http.StatusMethodNotAllowed, "HEAD unsupported"), nil
		}
		if r.Method != http.MethodGet || r.Header.Get("Range") != "bytes=0-0" {
			t.Fatalf("fallback request = %s Range=%q", r.Method, r.Header.Get("Range"))
		}
		resp := response(http.StatusPartialContent, "x")
		resp.Header.Set("Content-Range", "bytes 0-0/987654")
		return resp, nil
	})}}

	alive, size := p.probeURL(context.Background(), "https://cdn.example/video", nil)
	if !alive || size != 987654 || requests != 2 {
		t.Fatalf("probeURL = %v, %d after %d requests", alive, size, requests)
	}
}

func TestProbeURLRejectsFailuresAndUnknownLengths(t *testing.T) {
	tests := []struct {
		name      string
		transport roundTripFunc
		wantAlive bool
	}{
		{
			name: "server error",
			transport: func(*http.Request) (*http.Response, error) {
				return response(http.StatusBadGateway, "bad gateway"), nil
			},
		},
		{
			name: "transport error",
			transport: func(*http.Request) (*http.Response, error) {
				return nil, errors.New("dial failed")
			},
		},
		{
			name: "unknown content length is still alive",
			transport: func(*http.Request) (*http.Response, error) {
				resp := response(http.StatusNoContent, "")
				resp.ContentLength = -1
				return resp, nil
			},
			wantAlive: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := &Player{probeClient: &http.Client{Transport: tt.transport}}
			alive, size := p.probeURL(context.Background(), "https://cdn.example/video", nil)
			if alive != tt.wantAlive || size != 0 {
				t.Fatalf("probeURL = %v, %d", alive, size)
			}
		})
	}
}

func TestPlaybackQueryParsing(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/play?season=2&episode=7&fileIdx=3", nil)
	season, episode := parseSeasonEpisode(req)
	if season == nil || *season != 2 || episode == nil || *episode != 7 {
		t.Fatalf("season/episode = %v/%v", season, episode)
	}
	if idx := parseFileIdx(req); idx == nil || *idx != 3 {
		t.Fatalf("fileIdx = %v", idx)
	}

	bad := httptest.NewRequest(http.MethodGet, "/api/play?season=x&episode=&fileIdx=-1", nil)
	season, episode = parseSeasonEpisode(bad)
	if season != nil || episode != nil || parseFileIdx(bad) != nil {
		t.Fatalf("invalid query was accepted: %v/%v/%v", season, episode, parseFileIdx(bad))
	}
}

func TestFormatSpeed(t *testing.T) {
	tests := map[int64]string{
		0:           "0 B/s",
		999:         "999 B/s",
		1536:        "1.5 KB/s",
		2 * 1048576: "2.0 MB/s",
	}
	for input, want := range tests {
		if got := formatSpeed(input); got != want {
			t.Fatalf("formatSpeed(%d) = %q, want %q", input, got, want)
		}
	}
}
