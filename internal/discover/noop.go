//go:build !discover

// Package discover provides personalized media recommendations.
// This stub is compiled when Cove is built without the proprietary
// recommendation engine. Personalized rows are empty; the genre list
// still works via TMDB. Build with -tags discover to enable.
//
// Custom discovery algorithms (an HTTP endpoint the user points Settings at)
// still work in this build: without the proprietary engine there's no taste
// profile to send, so a configured algorithm just scores a plain popularity
// pool instead of nothing. The request/response JSON shape matches the
// proprietary build's contract (same field names, just empty profile arrays),
// so a single algorithm implementation works against either edition.
package discover

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/coveninja/cove/internal/utils"
)

type TasteProvider interface {
	TasteSignals() []library.TasteSignal
	Generation() uint64
}

type Service struct {
	tmdb     *tmdb.Client
	settings *settings.Store
}

func New(t *tmdb.Client, lib TasteProvider, st *settings.Store) *Service {
	return &Service{tmdb: t, settings: st}
}

func (s *Service) SetupHandlers(mux *http.ServeMux) {
	empty := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte("[]"))
	}
	mux.HandleFunc("/api/discover", utils.CorsMiddleware(s.handleRecommend))
	mux.HandleFunc("/api/discover/genres", utils.CorsMiddleware(empty))
	mux.HandleFunc("/api/discover/keywords", utils.CorsMiddleware(empty))
	mux.HandleFunc("/api/discover/genre", utils.CorsMiddleware(empty))
	mux.HandleFunc("/api/discover/keyword", utils.CorsMiddleware(empty))
	mux.HandleFunc("/api/discover/insights", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(struct{}{})
	}))
	mux.HandleFunc("/api/genres", utils.CorsMiddleware(s.handleGenreList))
	mux.HandleFunc("/api/discover/algorithm/test", utils.CorsMiddleware(s.handleTestAlgorithm))
}

// GET /api/discover?type=movie|tv&limit=20 — no personalization without the
// proprietary engine, but a configured custom algorithm still gets a plain
// popularity pool to score rather than an empty row.
func (s *Service) handleRecommend(w http.ResponseWriter, r *http.Request) {
	mt := r.URL.Query().Get("type")
	if mt != "movie" && mt != "tv" {
		mt = "movie"
	}
	limit := 20
	if v, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && v > 0 && v <= 60 {
		limit = v
	}

	var algorithm, customURL string
	if s.settings != nil {
		cfg := s.settings.Get()
		algorithm, customURL = cfg.DiscoveryAlgorithm, cfg.CustomAlgorithmURL
	}
	if algorithm != "custom" || customURL == "" {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte("[]"))
		return
	}

	res, err := s.tmdb.Discover(tmdb.DiscoverParams{
		MediaType:    mt,
		SortBy:       "popularity.desc",
		MinVoteCount: 50,
	})
	if err != nil || res == nil {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte("[]"))
		return
	}
	candidates := make([]tmdb.Media, 0, len(res.Results))
	for _, m := range res.Results {
		if m.PosterURL != "" {
			candidates = append(candidates, m)
		}
	}

	scores, err := fetchCustomScoresOSS(customURL, mt, candidates)
	if err != nil {
		log.Println("discover: custom algorithm failed:", err)
		scores = map[int]float64{} // no fallback ranker without the proprietary engine; keep TMDB order
	}
	sort.SliceStable(candidates, func(i, j int) bool { return scores[candidates[i].ID] > scores[candidates[j].ID] })
	if len(candidates) > limit {
		candidates = candidates[:limit]
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(candidates)
}

func (s *Service) handleGenreList(w http.ResponseWriter, r *http.Request) {
	mt := r.URL.Query().Get("type")
	if mt != "movie" && mt != "tv" {
		http.Error(w, "type must be movie or tv", http.StatusBadRequest)
		return
	}
	genres, err := s.tmdb.GenreList(mt)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(genres)
}

// ── Custom discovery algorithm (OSS-build variant) ────────────────────────────
//
// Self-contained duplicate of the proprietary build's algorithm.go contract:
// same JSON shape, but with no taste-profile machinery to draw on (Profile,
// Taste, rankTaste, etc. only exist in the -tags discover build), so the
// profile fields are always empty arrays rather than populated ones.

type ossTaste struct {
	ID    int     `json:"id"`
	Name  string  `json:"name"`
	Score float64 `json:"score"`
}

type ossAlgorithmProfile struct {
	TopGenres      []ossTaste `json:"top_genres"`
	DislikedGenres []ossTaste `json:"disliked_genres"`
	TopKeywords    []ossTaste `json:"top_keywords"`
	TopPeople      []ossTaste `json:"top_people"`
}

type ossAlgorithmRequest struct {
	MediaType  string              `json:"media_type"`
	Profile    ossAlgorithmProfile `json:"profile"`
	Candidates []tmdb.Media        `json:"candidates"`
}

type ossAlgorithmResponse struct {
	Scores map[string]float64 `json:"scores"`
}

var ossAlgorithmClient = &http.Client{Timeout: 5 * time.Second}

func fetchCustomScoresOSS(url, mediaType string, candidates []tmdb.Media) (map[int]float64, error) {
	body, err := json.Marshal(ossAlgorithmRequest{
		MediaType: mediaType,
		Profile: ossAlgorithmProfile{
			TopGenres:      []ossTaste{},
			DislikedGenres: []ossTaste{},
			TopKeywords:    []ossTaste{},
			TopPeople:      []ossTaste{},
		},
		Candidates: candidates,
	})
	if err != nil {
		return nil, fmt.Errorf("custom algorithm: encode request: %w", err)
	}

	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("custom algorithm: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	res, err := ossAlgorithmClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("custom algorithm: request failed: %w", err)
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("custom algorithm: returned status %d", res.StatusCode)
	}

	var parsed ossAlgorithmResponse
	if err := json.NewDecoder(res.Body).Decode(&parsed); err != nil {
		return nil, fmt.Errorf("custom algorithm: decode response: %w", err)
	}

	scores := make(map[int]float64, len(parsed.Scores))
	for idStr, score := range parsed.Scores {
		id, err := strconv.Atoi(idStr)
		if err != nil {
			continue
		}
		scores[id] = score
	}
	return scores, nil
}

// POST /api/discover/algorithm/test {"url": "https://..."}
func (s *Service) handleTestAlgorithm(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		URL string `json:"url"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.URL == "" {
		http.Error(w, "invalid body: expected {\"url\": \"...\"}", http.StatusBadRequest)
		return
	}

	sample := []tmdb.Media{
		{ID: 1, Title: "Sample Movie One", GenreIDs: []int{28, 12}, Rating: 7.2, Popularity: 42},
		{ID: 2, Title: "Sample Movie Two", GenreIDs: []int{35}, Rating: 6.5, Popularity: 18},
	}
	_, err := fetchCustomScoresOSS(body.URL, "movie", sample)
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		json.NewEncoder(w).Encode(map[string]any{"ok": false, "error": err.Error()})
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"ok": true})
}
