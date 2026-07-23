package player

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakePlayerTMDB struct {
	movieID, tvID string
	err           error
	media         *tmdb.Media
}

func (f *fakePlayerTMDB) GetIMDBId(int) (string, error)   { return f.movieID, f.err }
func (f *fakePlayerTMDB) GetTVIMDBId(int) (string, error) { return f.tvID, f.err }
func (f *fakePlayerTMDB) GetMediaByID(int, string) (*tmdb.Media, error) {
	return f.media, f.err
}

type fakePlayerAddons struct {
	streams      []addons.Stream
	streamErr    error
	subtitles    []addons.Subtitle
	streamType   string
	streamID     string
	subtitleType string
	subtitleID   string
}

func (f *fakePlayerAddons) GetAllStreams(_ context.Context, mediaType, stremioID string) ([]addons.Stream, error) {
	f.streamType, f.streamID = mediaType, stremioID
	return f.streams, f.streamErr
}

func (f *fakePlayerAddons) GetAllSubtitles(_ context.Context, mediaType, stremioID string) []addons.Subtitle {
	f.subtitleType, f.subtitleID = mediaType, stremioID
	return f.subtitles
}

type playerNuvioCall struct {
	mediaType, imdbID, title string
	tmdbID, year             int
	season, episode          *int
}

type fakePlayerNuvio struct {
	enabled bool
	streams []addons.Stream
	call    playerNuvioCall
}

func (f *fakePlayerNuvio) HasEnabledScrapers() bool { return f.enabled }
func (f *fakePlayerNuvio) GetStreams(_ context.Context, mediaType string, tmdbID int, imdbID, title string, year int, season, episode *int) []addons.Stream {
	f.call = playerNuvioCall{mediaType, imdbID, title, tmdbID, year, season, episode}
	return f.streams
}

func handlerPlayer(metadata playerTMDB, providers playerAddons, plugins playerNuvio) (*Player, *http.ServeMux) {
	p := &Player{
		tmdbClient: metadata, addonMgr: providers, nuvioMgr: plugins,
		streamHeaders:  make(map[string]streamHeaderEntry),
		activeTorrents: make(map[string]*torrentState),
	}
	mux := http.NewServeMux()
	p.SetupHandlers(mux)
	return p, mux
}

func serve(mux *http.ServeMux, method, target, body string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, httptest.NewRequest(method, target, strings.NewReader(body)))
	return recorder
}

func TestStreamsHandlerCombinesProvidersAndRegistersURLs(t *testing.T) {
	provider := &fakePlayerAddons{streams: []addons.Stream{{
		Name: "Addon", URL: "https://cdn.example/addon", Headers: map[string]string{"Referer": "https://catalog.example"},
	}}}
	plugin := &fakePlayerNuvio{enabled: true, streams: []addons.Stream{{Name: "Plugin", URL: "https://cdn.example/plugin"}}}
	metadata := &fakePlayerTMDB{tvID: "tt0042", media: &tmdb.Media{Name: "Example Show", FirstAir: "2024-02-03"}}
	p, mux := handlerPlayer(metadata, provider, plugin)

	recorder := serve(mux, http.MethodGet, "/api/streams?id=42&type=tv&season=2&episode=3", "")
	assert.Equal(t, http.StatusOK, recorder.Code)
	var streams []addons.Stream
	require.NoError(t, json.NewDecoder(recorder.Body).Decode(&streams))
	require.Len(t, streams, 2)
	assert.Equal(t, "tv", provider.streamType)
	assert.Equal(t, "tt0042:2:3", provider.streamID)
	assert.Equal(t, "Example Show", plugin.call.title)
	assert.Equal(t, 2024, plugin.call.year)
	require.NotNil(t, plugin.call.season)
	require.NotNil(t, plugin.call.episode)
	assert.Equal(t, 2, *plugin.call.season)
	assert.Equal(t, 3, *plugin.call.episode)

	headers, known := p.lookupStream("https://cdn.example/addon")
	assert.True(t, known)
	assert.Equal(t, "https://catalog.example", headers["Referer"])
	_, known = p.lookupStream("https://cdn.example/plugin")
	assert.True(t, known)
}

func TestSubtitlesHandlerBuildsTVStremioID(t *testing.T) {
	provider := &fakePlayerAddons{subtitles: []addons.Subtitle{{ID: "sub-1", URL: "https://subs.example/sub.vtt"}}}
	_, mux := handlerPlayer(&fakePlayerTMDB{tvID: "tt0099"}, provider, nil)
	recorder := serve(mux, http.MethodGet, "/api/subtitles?id=99&type=tv&season=4&episode=8", "")
	assert.Equal(t, http.StatusOK, recorder.Code)
	assert.Equal(t, "tv", provider.subtitleType)
	assert.Equal(t, "tt0099:4:8", provider.subtitleID)
	var subtitles []addons.Subtitle
	require.NoError(t, json.NewDecoder(recorder.Body).Decode(&subtitles))
	require.Len(t, subtitles, 1)
}

func TestMediaHandlerValidationAndProviderErrors(t *testing.T) {
	tests := []struct {
		name, target string
	}{
		{"missing id", "/api/streams?type=movie"},
		{"trailing id data", "/api/streams?id=12junk&type=movie"},
		{"zero id", "/api/streams?id=0&type=movie"},
		{"invalid stream type", "/api/streams?id=12&type=book"},
		{"missing episode", "/api/streams?id=12&type=tv&season=1"},
		{"invalid season", "/api/streams?id=12&type=tv&season=x&episode=2"},
		{"negative episode", "/api/streams?id=12&type=tv&season=1&episode=-2"},
		{"invalid subtitle id", "/api/subtitles?id=2x&type=movie"},
		{"invalid subtitle type", "/api/subtitles?id=2&type=book"},
		{"partial subtitle episode", "/api/subtitles?id=2&type=tv&season=1"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, mux := handlerPlayer(&fakePlayerTMDB{movieID: "tt12", tvID: "tt12"}, &fakePlayerAddons{}, nil)
			assert.Equal(t, http.StatusBadRequest, serve(mux, http.MethodGet, tt.target, "").Code)
		})
	}

	_, mux := handlerPlayer(&fakePlayerTMDB{movieID: "tt12"}, &fakePlayerAddons{streamErr: errors.New("provider failed")}, nil)
	assert.Equal(t, http.StatusInternalServerError, serve(mux, http.MethodGet, "/api/streams?id=12&type=movie", "").Code)
	_, mux = handlerPlayer(&fakePlayerTMDB{err: errors.New("metadata failed")}, &fakePlayerAddons{}, nil)
	assert.Equal(t, http.StatusInternalServerError, serve(mux, http.MethodGet, "/api/subtitles?id=12&type=movie", "").Code)
}

func TestProbeHandlerChecksOnlyRegisteredURLs(t *testing.T) {
	requests := 0
	p, mux := handlerPlayer(nil, nil, nil)
	p.probeClient = &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		requests++
		resp := response(http.StatusOK, "")
		resp.ContentLength = 321
		return resp, nil
	})}
	p.rememberStream("https://cdn.example/known", nil)
	recorder := serve(mux, http.MethodPost, "/api/streams/probe", `{"streams":[{"url":"https://cdn.example/known"},{"url":"https://cdn.example/unknown"}],"timeoutMs":50}`)
	assert.Equal(t, http.StatusOK, recorder.Code)
	var result struct {
		Results []struct {
			URL           string `json:"url"`
			Alive         bool   `json:"alive"`
			ContentLength int64  `json:"contentLength"`
		} `json:"results"`
	}
	require.NoError(t, json.NewDecoder(recorder.Body).Decode(&result))
	require.Len(t, result.Results, 2)
	assert.True(t, result.Results[0].Alive)
	assert.Equal(t, int64(321), result.Results[0].ContentLength)
	assert.False(t, result.Results[1].Alive)
	assert.Equal(t, 1, requests)

	assert.Equal(t, http.StatusMethodNotAllowed, serve(mux, http.MethodGet, "/api/streams/probe", "").Code)
	assert.Equal(t, http.StatusBadRequest, serve(mux, http.MethodPost, "/api/streams/probe", `{}`).Code)
}

func TestPlayAndProgressHandlersWithoutTorrentClient(t *testing.T) {
	p, mux := handlerPlayer(nil, nil, nil)
	direct := "https://cdn.example/movie.mkv"
	p.rememberStream(direct, nil)
	recorder := serve(mux, http.MethodGet, "/api/play?url="+direct, "")
	assert.Equal(t, http.StatusTemporaryRedirect, recorder.Code)
	assert.Equal(t, direct, recorder.Header().Get("Location"))
	assert.Equal(t, http.StatusForbidden, serve(mux, http.MethodGet, "/api/play?url=https://cdn.example/unknown", "").Code)
	assert.Equal(t, http.StatusBadRequest, serve(mux, http.MethodGet, "/api/play?url=file:///tmp/movie", "").Code)
	assert.Equal(t, http.StatusBadRequest, serve(mux, http.MethodGet, "/api/play", "").Code)

	recorder = serve(mux, http.MethodGet, "/api/progress?hash=missing", "")
	assert.JSONEq(t, `{"found":false}`, recorder.Body.String())
	assert.Equal(t, http.StatusMethodNotAllowed, serve(mux, http.MethodGet, "/api/prefetch-download", "").Code)
	assert.Equal(t, http.StatusBadRequest, serve(mux, http.MethodPost, "/api/prefetch-download?hash=bad", "").Code)
}

func TestPlayerSmallHelpers(t *testing.T) {
	assert.Equal(t, 42, func() int { value, _ := positiveInt("42"); return value }())
	_, ok := positiveInt("0")
	assert.False(t, ok)
	_, ok = positiveInt("4x")
	assert.False(t, ok)
	assert.Equal(t, "second", firstNonEmpty("", "second", "third"))
	assert.Equal(t, 2026, parseYear("2026-07-22"))
	assert.Equal(t, 0, parseYear("bad"))
}
