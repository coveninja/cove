package trakt

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// DeviceCodeResponse is the response from POST /oauth/device/code, forwarded
// to the frontend verbatim (only user_code and verification_url are shown to
// the user; device_code and interval are used for polling).
type DeviceCodeResponse struct {
	DeviceCode      string `json:"device_code"`
	UserCode        string `json:"user_code"`
	VerificationURL string `json:"verification_url"`
	ExpiresIn       int    `json:"expires_in"`
	Interval        int    `json:"interval"`
}

// PollStatus is the outcome of a single PollDeviceFlow call.
type PollStatus string

const (
	PollPending    PollStatus = "pending"    // 400 — user hasn't authorized yet
	PollAuthorized PollStatus = "authorized" // 200 — token saved
	PollExpired    PollStatus = "expired"    // 410 — code window closed
	PollDenied     PollStatus = "denied"     // 418 — user rejected
	PollInvalid    PollStatus = "invalid"    // 404/409 — bad code
	PollSlowDown   PollStatus = "slow_down"  // 429 — increase poll interval
)

// PollResult is returned by PollDeviceFlow.
type PollResult struct {
	Status   PollStatus
	Username string // non-empty when Status == PollAuthorized
}

// StartDeviceFlow calls POST /oauth/device/code and returns the code payload.
func (c *Client) StartDeviceFlow() (*DeviceCodeResponse, error) {
	resp, err := c.doWrite(http.MethodPost, "/oauth/device/code", map[string]string{
		"client_id": c.clientID,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("trakt: device/code: HTTP %d", resp.StatusCode)
	}
	var out DeviceCodeResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("trakt: device/code decode: %w", err)
	}
	return &out, nil
}

// PollDeviceFlow calls POST /oauth/device/token once and maps the response
// status to a PollStatus. On 200 it saves the token and fetches the username
// from GET /users/me.
func (c *Client) PollDeviceFlow(deviceCode string) (*PollResult, error) {
	resp, err := c.doWrite(http.MethodPost, "/oauth/device/token", map[string]string{
		"code":          deviceCode,
		"client_id":     c.clientID,
		"client_secret": c.clientSecret,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusBadRequest:
		return &PollResult{Status: PollPending}, nil
	case http.StatusNotFound, http.StatusConflict:
		return &PollResult{Status: PollInvalid}, nil
	case http.StatusGone:
		return &PollResult{Status: PollExpired}, nil
	case http.StatusTeapot:
		return &PollResult{Status: PollDenied}, nil
	case http.StatusTooManyRequests:
		return &PollResult{Status: PollSlowDown}, nil
	case http.StatusOK:
		// fall through to decode
	default:
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("trakt: device/token: HTTP %d: %s", resp.StatusCode, body)
	}

	var tok struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tok); err != nil {
		return nil, fmt.Errorf("trakt: device/token decode: %w", err)
	}

	st := tokenState{
		AccessToken:  tok.AccessToken,
		RefreshToken: tok.RefreshToken,
		ExpiresAt:    time.Now().Add(time.Duration(tok.ExpiresIn) * time.Second),
	}
	// Best-effort: fetch username; empty is acceptable, just cosmetically
	// degraded (the status endpoint will show username="" until next refresh).
	st.Username = c.fetchUsername(tok.AccessToken)

	if err := c.store.Save(st); err != nil {
		return nil, fmt.Errorf("trakt: save token: %w", err)
	}
	return &PollResult{Status: PollAuthorized, Username: st.Username}, nil
}

// fetchUsername calls GET /users/me with the given access token and returns
// the username string. Returns "" on any error — callers treat it as optional.
func (c *Client) fetchUsername(accessToken string) string {
	req, err := http.NewRequest(http.MethodGet, c.baseURL+"/users/me", nil)
	if err != nil {
		return ""
	}
	req.Header.Set("trakt-api-key", c.clientID)
	req.Header.Set("trakt-api-version", apiVersion)
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ""
	}
	var me struct {
		Username string `json:"username"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&me); err != nil {
		return ""
	}
	return me.Username
}

// EnsureValidToken refreshes the access token when it expires within 1 hour.
// A mutex prevents concurrent refresh races — only the first caller proceeds;
// later callers re-check under the lock and skip if another goroutine already
// refreshed.
func (c *Client) EnsureValidToken() error {
	st := c.store.Get()
	if st.AccessToken == "" {
		return fmt.Errorf("trakt: not connected")
	}
	if time.Until(st.ExpiresAt) > time.Hour {
		return nil // plenty of time left
	}

	c.refreshMu.Lock()
	defer c.refreshMu.Unlock()
	// Re-read after acquiring the lock — another goroutine may have refreshed.
	st = c.store.Get()
	if time.Until(st.ExpiresAt) > time.Hour {
		return nil
	}
	return c.doRefreshToken(st.RefreshToken)
}

// doRefreshToken exchanges a refresh token for a new access/refresh pair.
// Must be called with c.refreshMu held.
func (c *Client) doRefreshToken(refreshToken string) error {
	resp, err := c.doWrite(http.MethodPost, "/oauth/token", map[string]string{
		"refresh_token": refreshToken,
		"client_id":     c.clientID,
		"client_secret": c.clientSecret,
		"redirect_uri":  "urn:ietf:wg:oauth:2.0:oob",
		"grant_type":    "refresh_token",
	})
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("trakt: refresh token: HTTP %d", resp.StatusCode)
	}
	var tok struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tok); err != nil {
		return fmt.Errorf("trakt: refresh token decode: %w", err)
	}

	st := c.store.Get()
	st.AccessToken = tok.AccessToken
	if tok.RefreshToken != "" {
		st.RefreshToken = tok.RefreshToken
	}
	st.ExpiresAt = time.Now().Add(time.Duration(tok.ExpiresIn) * time.Second)
	return c.store.Save(st)
}

// RevokeToken calls POST /oauth/revoke for the current access token.
// Best-effort — errors are silently ignored (the sidecar file is cleared
// regardless by the caller).
func (c *Client) RevokeToken() {
	st := c.store.Get()
	if st.AccessToken == "" {
		return
	}
	resp, err := c.doWrite(http.MethodPost, "/oauth/revoke", map[string]string{
		"token":         st.AccessToken,
		"client_id":     c.clientID,
		"client_secret": c.clientSecret,
	})
	if err != nil {
		return
	}
	resp.Body.Close()
}
