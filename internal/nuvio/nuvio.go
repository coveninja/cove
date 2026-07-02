// Package nuvio adds support for Nuvio-style native JS scraper plugins — a
// different ecosystem than the Stremio-compatible addons in internal/addons.
// A "repo" is a GitHub repository publishing a manifest.json that lists
// scraper entries; each scraper is a JS file executed in an embedded goja VM
// (see runtime.go) to produce direct stream URLs.
//
// This is deliberately kept separate from addons.Manager: internal/tmdb's
// batch quality-probe endpoint fans addons.Manager.GetAllStreams out across
// every title in a discovery grid, and must not incur goja startup + arbitrary
// third-party network calls per grid tile. Only internal/player's single-title
// /api/streams handler calls into this package.
package nuvio

import (
	"encoding/json"
	"time"
)

// ScraperManifestEntry is one scraper's entry in a repo's manifest.json.
type ScraperManifestEntry struct {
	ID                     string   `json:"id"`
	Name                   string   `json:"name"`
	Description            string   `json:"description,omitempty"`
	Version                string   `json:"version,omitempty"`
	Filename               string   `json:"filename"`
	SupportedTypes         []string `json:"supportedTypes,omitempty"`
	Logo                   string   `json:"logo,omitempty"`
	ContentLanguage        []string `json:"contentLanguage,omitempty"`
	SupportsExternalPlayer bool     `json:"supportsExternalPlayer,omitempty"`
}

// Scraper is a manifest entry plus Cove-local state. Enabled is Cove's own
// per-scraper opt-in toggle — deliberately independent of whatever "enabled"
// value ships in the upstream manifest, since that reflects the repo author's
// default, not the user's consent to run third-party JS.
//
// ScraperManifestEntry's fields are duplicated here (not embedded) on
// purpose: Go's encoding/json flattens anonymous embedded structs onto the
// parent object, but tygo does not — an embedded field would generate a TS
// type with a nested `scraperManifestEntry` property that doesn't match the
// actual flat JSON the backend sends.
type Scraper struct {
	ID                     string     `json:"id"`
	Name                   string     `json:"name"`
	Description            string     `json:"description,omitempty"`
	Version                string     `json:"version,omitempty"`
	Filename               string     `json:"filename"`
	SupportedTypes         []string   `json:"supportedTypes,omitempty"`
	Logo                   string     `json:"logo,omitempty"`
	ContentLanguage        []string   `json:"contentLanguage,omitempty"`
	SupportsExternalPlayer bool       `json:"supportsExternalPlayer,omitempty"`
	Enabled                bool       `json:"enabled"`
	Code                   string     `json:"code,omitempty"` // cached JS source; empty until first enable
	CodeFetchedAt          *time.Time `json:"codeFetchedAt,omitempty"`
	CodeErr                string     `json:"codeErr,omitempty"` // last fetch/parse error, surfaced in UI
}

func newScraper(e ScraperManifestEntry) Scraper {
	return Scraper{
		ID:                     e.ID,
		Name:                   e.Name,
		Description:            e.Description,
		Version:                e.Version,
		Filename:               e.Filename,
		SupportedTypes:         e.SupportedTypes,
		Logo:                   e.Logo,
		ContentLanguage:        e.ContentLanguage,
		SupportsExternalPlayer: e.SupportsExternalPlayer,
	}
}

// Repo is a user-added GitHub repository publishing Nuvio-style scrapers.
type Repo struct {
	ID        string    `json:"id"` // "owner/repo"
	Owner     string    `json:"owner"`
	Name      string    `json:"repo"`
	Branch    string    `json:"branch"`
	URL       string    `json:"url"` // original user-pasted URL, for display
	Enabled   bool      `json:"enabled"`
	Scrapers  []Scraper `json:"scrapers"`
	FetchedAt time.Time `json:"fetchedAt"`
	FetchErr  string    `json:"fetchErr,omitempty"`
}

// rawManifest tolerates the two shapes seen across real repos: a bare array
// of entries, or an object wrapping them under "scrapers" or "providers".
func parseManifest(data []byte) ([]ScraperManifestEntry, error) {
	var arr []ScraperManifestEntry
	if err := json.Unmarshal(data, &arr); err == nil {
		return arr, nil
	}

	var obj struct {
		Scrapers  []ScraperManifestEntry `json:"scrapers"`
		Providers []ScraperManifestEntry `json:"providers"`
	}
	if err := json.Unmarshal(data, &obj); err != nil {
		return nil, err
	}
	if len(obj.Scrapers) > 0 {
		return obj.Scrapers, nil
	}
	return obj.Providers, nil
}
