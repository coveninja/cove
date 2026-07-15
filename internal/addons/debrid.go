package addons

import (
	"regexp"
	"strings"
)

// debridEntry describes one known debrid service and how to recognise its
// per-stream annotation markers. Most debrid-capable Stremio addons follow the
// convention of embedding a bracketed abbreviation in the stream Name/Title:
// "[RD+]" for a cached RealDebrid link, "[RD]" for an uncached one.
type debridEntry struct {
	service    string   // human-readable service name returned in Stream.Debrid
	urlSubstrs []string // substrings that identify this service in the addon URL
	// cachedRe matches the "cached/instant" marker form: "[rd+]" or bare "rd+"
	// at a word boundary so "abcrd+" never fires.
	cachedRe *regexp.Regexp
	// uncachedBrkt is the plain bracketed form without "+", e.g. "[rd]".
	uncachedBrkt string
}

// debridTable is built once at package init. The order determines which
// service wins when multiple markers appear in the same text (first match).
var debridTable = func() []debridEntry {
	type raw struct {
		service    string
		abbrev     string // lower-case abbreviation used in markers
		urlSubstrs []string
	}
	rows := []raw{
		{"RealDebrid", "rd", []string{"realdebrid", "real-debrid"}},
		{"AllDebrid", "ad", []string{"alldebrid"}},
		{"Premiumize", "pm", []string{"premiumize"}},
		{"TorBox", "tb", []string{"torbox"}},
		{"Offcloud", "oc", []string{"offcloud"}},
		{"Debrid-Link", "dl", []string{"debrid-link"}},
	}
	entries := make([]debridEntry, len(rows))
	for i, r := range rows {
		a := r.abbrev
		entries[i] = debridEntry{
			service:      r.service,
			urlSubstrs:   r.urlSubstrs,
			cachedRe:     regexp.MustCompile(`\[` + a + `\+\]|\b` + a + `\+`),
			uncachedBrkt: "[" + a + "]",
		}
	}
	return entries
}()

// genericDebridNames are addon name fragments that indicate a generic debrid
// aggregator when no service-specific marker or URL pattern fires.
var genericDebridNames = []string{
	"comet", "knightcrawler", "mediafusion", "debridio", "aiostreams",
}

// classifyStream sets s.Cached and s.Debrid from marker conventions used by
// debrid-capable Stremio addons and
// promotes BehaviorHints.VideoSize into s.SizeBytes when unset. Mutates in
// place; never errors. Conservative: Cached is only set on unambiguous
// signals — a wrong false is much cheaper than a wrong true.
//
// Resolution order (highest to lowest):
//  1. Per-stream marker in stream text ([rd+], rd+, [rd], …)
//  2. Addon URL substring (realdebrid, torbox, …)
//  3. Generic addon-name pattern (comet, mediafusion, …)
//
// ⚡ or the substring "cached" in stream text upgrades Cached to true,
// but only when a service was already corroborated by one of the three tiers
// above — decorative emoji on unrecognised addons must not set Cached.
func classifyStream(s *Stream, addonName, addonURL string) {
	// VideoSize promotion — independent of debrid detection.
	if s.BehaviorHints != nil && s.BehaviorHints.VideoSize > 0 && s.SizeBytes == 0 {
		s.SizeBytes = s.BehaviorHints.VideoSize
	}

	text := strings.ToLower(s.Name + "\n" + s.Title)
	lowerAddonName := strings.ToLower(addonName)
	lowerAddonURL := strings.ToLower(addonURL)

	// --- 1. Per-stream marker detection (highest priority) ---
	service := ""
	cachedByMarker := false
	uncachedByMarker := false

	for _, e := range debridTable {
		if e.cachedRe.MatchString(text) {
			service = e.service
			cachedByMarker = true
			break
		}
		if strings.Contains(text, e.uncachedBrkt) {
			service = e.service
			uncachedByMarker = true
			break
		}
	}

	// --- 2. Addon URL service detection (if no per-stream marker) ---
	if service == "" {
		for _, e := range debridTable {
			for _, sub := range e.urlSubstrs {
				if strings.Contains(lowerAddonURL, sub) {
					service = e.service
					break
				}
			}
			if service != "" {
				break
			}
		}
	}

	// --- 3. Generic addon-name fallback ---
	if service == "" {
		for _, name := range genericDebridNames {
			if strings.Contains(lowerAddonName, name) {
				service = "Debrid"
				break
			}
		}
	}

	if service == "" {
		return // not a debrid stream
	}

	s.Debrid = service

	hasInstantSignal := strings.Contains(text, "⚡") || strings.Contains(text, "cached")

	switch {
	case cachedByMarker:
		// Explicit + marker is unambiguous.
		s.Cached = true
	case uncachedByMarker && hasInstantSignal:
		// Bare marker + ⚡/"cached" — instant-availability symbol wins.
		s.Cached = true
	case hasInstantSignal:
		// No per-stream marker but corroborated service + instant signal.
		s.Cached = true
		// uncachedByMarker without hasInstantSignal → s.Cached stays false (Go zero value).
		// service via URL/name without hasInstantSignal → s.Cached stays false.
	}
}
