package trakt

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"
)

const (
	defaultBaseURL = "https://api.trakt.tv"
	apiVersion     = "2"
	// defaultWriteInterval enforces Trakt's 1 write/sec rate limit with a small pad.
	defaultWriteInterval = 1100 * time.Millisecond
)

// Client is an authenticated HTTP client for the Trakt API.
type Client struct {
	clientID     string
	clientSecret string
	store        *Store
	baseURL      string // overridable in tests via c.baseURL = "http://..."
	httpClient   *http.Client

	// writeMu serializes write requests to respect Trakt's 1/sec write limit.
	writeMu   sync.Mutex
	lastWrite time.Time
	// Per-client timing controls keep tests deterministic without introducing
	// package-global races with asynchronous scrobble/device-flow goroutines.
	writeInterval time.Duration
	sleep         func(time.Duration)

	// refreshMu prevents concurrent token refreshes — only one should proceed.
	refreshMu sync.Mutex
}

func newClient(clientID, clientSecret string, store *Store) *Client {
	return &Client{
		clientID:      clientID,
		clientSecret:  clientSecret,
		store:         store,
		baseURL:       defaultBaseURL,
		httpClient:    &http.Client{Timeout: 30 * time.Second},
		writeInterval: defaultWriteInterval,
		sleep:         time.Sleep,
	}
}

// doRequest performs an HTTP request, setting all required Trakt headers.
// On HTTP 429 it retries once after honouring Retry-After (capped at 60s).
func (c *Client) doRequest(method, path string, body any) (*http.Response, error) {
	var bodyData []byte
	if body != nil {
		var err error
		bodyData, err = json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("trakt: marshal request: %w", err)
		}
	}

	build := func() (*http.Request, error) {
		var reader io.Reader
		if bodyData != nil {
			reader = bytes.NewReader(bodyData)
		}
		req, err := http.NewRequest(method, c.baseURL+path, reader)
		if err != nil {
			return nil, err
		}
		req.Header.Set("trakt-api-key", c.clientID)
		req.Header.Set("trakt-api-version", apiVersion)
		req.Header.Set("Content-Type", "application/json")
		if tok := c.store.Get(); tok.AccessToken != "" {
			req.Header.Set("Authorization", "Bearer "+tok.AccessToken)
		}
		return req, nil
	}

	req, err := build()
	if err != nil {
		return nil, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}

	// 429: back off and retry once.
	if resp.StatusCode == http.StatusTooManyRequests {
		resp.Body.Close()
		wait := time.Second
		if after := resp.Header.Get("Retry-After"); after != "" {
			if secs, err2 := strconv.Atoi(after); err2 == nil && secs > 0 && secs <= 60 {
				wait = time.Duration(secs) * time.Second
			}
		}
		c.sleep(wait)
		req2, err := build()
		if err != nil {
			return nil, err
		}
		return c.httpClient.Do(req2)
	}
	return resp, nil
}

// doWrite serializes concurrent write calls to ≥1.1s apart, then delegates
// to doRequest. Use this for all state-changing Trakt API calls (POST/DELETE).
func (c *Client) doWrite(method, path string, body any) (*http.Response, error) {
	c.writeMu.Lock()
	if elapsed := time.Since(c.lastWrite); elapsed < c.writeInterval {
		c.sleep(c.writeInterval - elapsed)
	}
	c.lastWrite = time.Now()
	c.writeMu.Unlock()

	return c.doRequest(method, path, body)
}

// get is a convenience wrapper for GET requests that decodes JSON into out.
func (c *Client) get(path string, out any) error {
	resp, err := c.doRequest(http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("trakt GET %s: HTTP %d", path, resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// getWithHeaders is like get but also exposes the response headers (for
// pagination — Trakt uses X-Pagination-Page-Count).
func (c *Client) getWithHeaders(path string, out any) (http.Header, error) {
	resp, err := c.doRequest(http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("trakt GET %s: HTTP %d", path, resp.StatusCode)
	}
	hdrs := resp.Header.Clone()
	return hdrs, json.NewDecoder(resp.Body).Decode(out)
}
