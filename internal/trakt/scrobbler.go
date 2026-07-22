package trakt

import (
	"fmt"
	"net/http"
)

// ScrobbleRequest is the body of POST /api/trakt/scrobble sent by the frontend.
type ScrobbleRequest struct {
	Action    string  `json:"action"` // "start" | "pause" | "stop"
	TmdbID    int     `json:"tmdb_id"`
	MediaType string  `json:"media_type"` // "movie" | "tv"
	Season    *int    `json:"season"`     // TV only
	Episode   *int    `json:"episode"`    // TV only
	Progress  float64 `json:"progress"`   // 0–100 (percent of runtime)
}

// scrobble sends a single scrobble event to the Trakt API at
// POST /scrobble/{action}. The payload shape differs by media type:
//
//   - movie: {movie:{ids:{tmdb:N}}, progress:P}
//   - tv:    {show:{ids:{tmdb:N}}, episode:{season:S, number:E}, progress:P}
//
// A 404 from Trakt (unknown title) is silently swallowed — it means Trakt
// couldn't match the tmdb ID, which is a Trakt data gap, not a Cove bug.
func (c *Client) scrobble(action string, req ScrobbleRequest) error {
	var payload map[string]any
	if req.MediaType == "movie" {
		payload = map[string]any{
			"movie":    map[string]any{"ids": map[string]any{"tmdb": req.TmdbID}},
			"progress": req.Progress,
		}
	} else {
		season := 0
		episode := 0
		if req.Season != nil {
			season = *req.Season
		}
		if req.Episode != nil {
			episode = *req.Episode
		}
		payload = map[string]any{
			"show":     map[string]any{"ids": map[string]any{"tmdb": req.TmdbID}},
			"episode":  map[string]any{"season": season, "number": episode},
			"progress": req.Progress,
		}
	}

	resp, err := c.doWrite(http.MethodPost, fmt.Sprintf("/scrobble/%s", action), payload)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	// 404 means Trakt doesn't know this title — not actionable from Cove's side.
	if resp.StatusCode == http.StatusNotFound {
		return nil
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("trakt: scrobble/%s: HTTP %d", action, resp.StatusCode)
	}
	return nil
}
