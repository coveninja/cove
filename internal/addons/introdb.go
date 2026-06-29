package addons

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
)

const introdDbURL = "https://api.theintrodb.org/v3"
const introdDbAppURL = "https://api.introdb.app"

// TimestampSegment is a single timed segment (intro, recap, credits, or preview).
// StartMs or EndMs may be nil: nil start means beginning of file, nil end means end of file.
type TimestampSegment struct {
	StartMs *int64 `json:"start_ms"`
	EndMs   *int64 `json:"end_ms"`
}

// TimestampData holds all segment timestamps for a media item or episode.
type TimestampData struct {
	Intro   []TimestampSegment `json:"intro,omitempty"`
	Recap   []TimestampSegment `json:"recap,omitempty"`
	Credits []TimestampSegment `json:"credits,omitempty"`
	Preview []TimestampSegment `json:"preview,omitempty"`
}

// fetchIntroDBApp calls api.introdb.app/segments for a TV episode using its
// IMDB ID. Covers intro, recap, and outro (mapped to credits). TV-only.
func fetchIntroDBApp(client *http.Client, imdbID string, season, episode int) (*TimestampData, error) {
	url := fmt.Sprintf("%s/segments?imdb_id=%s&season=%d&episode=%d", introdDbAppURL, imdbID, season, episode)
	log.Printf("introdb.app: GET %s", url)

	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Cove/1.0")

	res, err := client.Do(req)
	if err != nil {
		log.Printf("introdb.app: request failed: %v", err)
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		if err := Body.Close(); err != nil {
			log.Println(err)
		}
	}(res.Body)

	log.Printf("introdb.app: HTTP %d", res.StatusCode)
	if res.StatusCode == http.StatusNotFound {
		return &TimestampData{}, nil
	}
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("introdb.app: HTTP %d", res.StatusCode)
	}

	// Response: intro/recap/outro are objects or null. "outro" maps to credits.
	var raw struct {
		Intro *struct {
			StartMs int64 `json:"start_ms"`
			EndMs   int64 `json:"end_ms"`
		} `json:"intro"`
		Recap *struct {
			StartMs int64 `json:"start_ms"`
			EndMs   int64 `json:"end_ms"`
		} `json:"recap"`
		Outro *struct {
			StartMs int64 `json:"start_ms"`
			EndMs   int64 `json:"end_ms"`
		} `json:"outro"`
	}
	if err := json.NewDecoder(res.Body).Decode(&raw); err != nil {
		log.Printf("introdb.app: decode error: %v", err)
		return nil, err
	}

	data := &TimestampData{}
	if raw.Intro != nil {
		data.Intro = []TimestampSegment{{StartMs: &raw.Intro.StartMs, EndMs: &raw.Intro.EndMs}}
	}
	if raw.Recap != nil {
		data.Recap = []TimestampSegment{{StartMs: &raw.Recap.StartMs, EndMs: &raw.Recap.EndMs}}
	}
	if raw.Outro != nil {
		data.Credits = []TimestampSegment{{StartMs: &raw.Outro.StartMs, EndMs: &raw.Outro.EndMs}}
	}
	log.Printf("introdb.app: intro=%d recap=%d credits(outro)=%d", len(data.Intro), len(data.Recap), len(data.Credits))
	return data, nil
}

// mergeTimestamps fills nil/empty slices in base from fill. base takes priority
// for any segment type it already has; this lets theintrodb.org (which covers
// preview) override introdb.app where both have data.
func mergeTimestamps(base, fill *TimestampData) *TimestampData {
	out := *base
	if len(out.Intro) == 0 {
		out.Intro = fill.Intro
	}
	if len(out.Recap) == 0 {
		out.Recap = fill.Recap
	}
	if len(out.Credits) == 0 {
		out.Credits = fill.Credits
	}
	if len(out.Preview) == 0 {
		out.Preview = fill.Preview
	}
	return &out
}

func fetchTimestamps(client *http.Client, tmdbID int, season, episode *int) (*TimestampData, error) {
	url := fmt.Sprintf("%s/media?tmdb_id=%d", introdDbURL, tmdbID)
	if season != nil && episode != nil {
		url += fmt.Sprintf("&season=%d&episode=%d", *season, *episode)
	}
	log.Printf("introdb: GET %s", url)

	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Cove/1.0")

	res, err := client.Do(req)
	if err != nil {
		log.Printf("introdb: request failed: %v", err)
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		if err := Body.Close(); err != nil {
			log.Println(err)
		}
	}(res.Body)

	log.Printf("introdb: HTTP %d", res.StatusCode)
	if res.StatusCode == http.StatusNotFound {
		return &TimestampData{}, nil
	}
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("introdb: HTTP %d", res.StatusCode)
	}

	var data TimestampData
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		log.Printf("introdb: decode error: %v", err)
		return nil, err
	}
	log.Printf("introdb: intro=%d recap=%d credits=%d preview=%d",
		len(data.Intro), len(data.Recap), len(data.Credits), len(data.Preview))
	return &data, nil
}
