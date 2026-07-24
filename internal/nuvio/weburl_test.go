package nuvio

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/andybalholm/brotli"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestRunScraper_CompressedResponses covers the exact real-world failure
// reported against production: a scraper hitting an origin that compresses
// its JSON response with gzip, brotli, or deflate despite the scraper
// setting its own Accept-Encoding header (which disables Go's transparent
// decompression). Previously only gzip was handled; brotli/deflate produced
// garbage bytes that broke JSON.parse with a confusing "invalid character"
// error instead of a clear decode failure or, ideally, a successful parse.
func TestRunScraper_CompressedResponses(t *testing.T) {
	// These exercise the decode path against a loopback httptest server, which
	// the production SSRF-safe transport refuses — swap in a plain transport
	// for the duration of the test. Also reset the shared client so the new
	// transport takes effect (sharedScraperClient is built lazily from
	// scraperTransport; without the reset the cached production client persists).
	useLocalScraperTransport(t)

	body := `{"ok":true,"streams":[{"name":"n","title":"t","url":"http://example.com/s.m3u8"}]}`

	cases := []struct {
		name     string
		encoding string
		encode   func(string) []byte
	}{
		{"gzip", "gzip", func(s string) []byte {
			var buf bytes.Buffer
			w := gzip.NewWriter(&buf)
			w.Write([]byte(s))
			w.Close()
			return buf.Bytes()
		}},
		{"brotli", "br", func(s string) []byte {
			var buf bytes.Buffer
			w := brotli.NewWriter(&buf)
			w.Write([]byte(s))
			w.Close()
			return buf.Bytes()
		}},
		{"deflate (zlib-wrapped)", "deflate", func(s string) []byte {
			var buf bytes.Buffer
			w := zlib.NewWriter(&buf)
			w.Write([]byte(s))
			w.Close()
			return buf.Bytes()
		}},
		{"deflate (raw)", "deflate", func(s string) []byte {
			var buf bytes.Buffer
			w, _ := flate.NewWriter(&buf, flate.DefaultCompression)
			w.Write([]byte(s))
			w.Close()
			return buf.Bytes()
		}},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			encoded := c.encode(body)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Encoding", c.encoding)
				w.Write(encoded)
			}))
			defer srv.Close()

			code := `
				async function getStreams() {
					const res = await fetch("` + srv.URL + `", { headers: { "Accept-Encoding": "gzip, deflate, br" } });
					const data = await res.json();
					return data.streams;
				}
				module.exports = { getStreams };
			`
			streams, err := runScraper(context.Background(), "test-"+c.name, code, 5*time.Second, 1, "movie", "Test", 2020, "tt0000001", nil, nil)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if len(streams) != 1 || streams[0].URL != "http://example.com/s.m3u8" {
				t.Fatalf("unexpected streams: %+v", streams)
			}
		})
	}
}

func TestDecodeBodyNormalizesEncodingAndReportsCorruption(t *testing.T) {
	var compressed bytes.Buffer
	writer := gzip.NewWriter(&compressed)
	_, err := writer.Write([]byte(`{"ok":true}`))
	require.NoError(t, err)
	require.NoError(t, writer.Close())

	decoded, err := decodeBody(&http.Response{
		Body:   io.NopCloser(bytes.NewReader(compressed.Bytes())),
		Header: http.Header{"Content-Encoding": []string{" GZip "}},
	})
	require.NoError(t, err)
	assert.JSONEq(t, `{"ok":true}`, string(decoded))

	for _, testCase := range []struct {
		name     string
		encoding string
	}{
		{"gzip", "gzip"},
		{"deflate", "deflate"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			_, decodeErr := decodeBody(&http.Response{
				Body:   io.NopCloser(strings.NewReader("not compressed")),
				Header: http.Header{"Content-Encoding": []string{testCase.encoding}},
			})
			require.Error(t, decodeErr)
			assert.Contains(t, decodeErr.Error(), testCase.name+" decode")
		})
	}
}

// TestRunScraper_WebGlobals covers URL, URLSearchParams, and the `global`
// alias that real scrapers reference (e.g. Castle's `URLSearchParams is not
// defined`, mallumv/dvdplay's `global is not defined` from production logs)
// but which goja doesn't provide out of the box.
func TestRunScraper_WebGlobals(t *testing.T) {
	code := `
		function getStreams() {
			var results = [];

			var u = new URL("/path?a=1&b=2#frag", "https://example.com:8443");
			results.push(u.hostname === "example.com");
			results.push(u.protocol === "https:");
			results.push(u.host === "example.com:8443");
			results.push(u.port === "8443");
			results.push(u.pathname === "/path");
			results.push(u.search === "?a=1&b=2");
			results.push(u.hash === "#frag");
			results.push(u.origin === "https://example.com:8443");
			results.push(u.href === "https://example.com:8443/path?a=1&b=2#frag");
			results.push(u.toString() === u.href);
			results.push(u.toJSON() === u.href);
			results.push(u.searchParams.get("a") === "1");

			var p = new URLSearchParams("x=1&y=2");
			p.append("z", "3");
			p.append("z", "4");
			results.push(p.get("x") === "1");
			results.push(p.get("z") === "3");
			results.push(p.getAll("z").join(",") === "3,4");
			results.push(p.has("y") === true);
			p.set("x", "updated");
			results.push(p.get("x") === "updated");
			p.delete("y");
			results.push(p.has("y") === false);
			results.push(p.get("missing") === null);
			results.push(p.toString() === "x=updated&z=3&z=4");

			var fromObject = new URLSearchParams({page: 2});
			results.push(fromObject.get("page") === "2");

			var empty = new URL("https://example.com");
			results.push(empty.search === "");
			results.push(empty.hash === "");

			var controller = new AbortController();
			results.push(controller.signal.aborted === false);
			controller.abort();
			results.push(controller.signal.aborted === true);

			results.push(typeof global !== "undefined");

			results.push(btoa("hello") === "aGVsbG8=");
			results.push(atob("aGVsbG8=") === "hello");
			results.push(atob("not-base64") === "");

			var invalidRejected = false;
			try { new URL("http://[invalid"); } catch (_) { invalidRejected = true; }
			results.push(invalidRejected);

			var ok = results.every(function (r) { return r === true; });
			return [{ name: ok ? "PASS" : "FAIL", title: JSON.stringify(results), url: "http://example.com/x" }];
		}
		module.exports = { getStreams };
	`
	streams, err := runScraper(context.Background(), "test-webglobals", code, 5*time.Second, 1, "movie", "Test", 2020, "tt0000001", nil, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(streams) != 1 {
		t.Fatalf("expected 1 result, got %d", len(streams))
	}
	if !strings.EqualFold(streams[0].Name, "PASS") {
		t.Fatalf("web globals check failed: %s", streams[0].Title)
	}
}
