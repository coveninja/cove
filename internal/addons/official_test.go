package addons

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ── fetchTimestamps ────────────────────────────────────────────────────────────

func TestFetchTimestamps_HappyPath_Movie(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Movie request: no season/episode params.
		assert.Contains(t, r.URL.RawQuery, "tmdb_id=550")
		assert.NotContains(t, r.URL.RawQuery, "season")
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"intro":[{"start_ms":1000,"end_ms":90000}],"credits":[{"start_ms":3300000,"end_ms":3400000}]}`)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	data, err := fetchTimestamps(&http.Client{}, 550, nil, nil)
	require.NoError(t, err)
	require.NotNil(t, data)
	require.Len(t, data.Intro, 1)
	assert.Equal(t, int64(1000), *data.Intro[0].StartMs)
	assert.Equal(t, int64(90000), *data.Intro[0].EndMs)
	require.Len(t, data.Credits, 1)
}

func TestFetchTimestamps_HappyPath_TVEpisode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Contains(t, r.URL.RawQuery, "season=1")
		assert.Contains(t, r.URL.RawQuery, "episode=2")
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"recap":[{"start_ms":0,"end_ms":30000}],"preview":[{"start_ms":3500000,"end_ms":3600000}]}`)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	season, episode := 1, 2
	data, err := fetchTimestamps(&http.Client{}, 99, &season, &episode)
	require.NoError(t, err)
	require.NotNil(t, data)
	require.Len(t, data.Recap, 1)
	require.Len(t, data.Preview, 1)
}

func TestFetchTimestamps_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	data, err := fetchTimestamps(&http.Client{}, 1, nil, nil)
	require.NoError(t, err)
	require.NotNil(t, data)
	assert.Empty(t, data.Intro)
}

func TestFetchTimestamps_NonOK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "server error", http.StatusInternalServerError)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	_, err := fetchTimestamps(&http.Client{}, 1, nil, nil)
	assert.Error(t, err)
}

func TestFetchTimestamps_MalformedJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{bad json`)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	_, err := fetchTimestamps(&http.Client{}, 1, nil, nil)
	assert.Error(t, err)
}

// ── fetchIntroDBApp ────────────────────────────────────────────────────────────

func TestFetchIntroDBApp_HappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Contains(t, r.URL.RawQuery, "imdb_id=tt1234567")
		assert.Contains(t, r.URL.RawQuery, "season=2")
		assert.Contains(t, r.URL.RawQuery, "episode=3")
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"intro":{"start_ms":5000,"end_ms":85000},"recap":{"start_ms":0,"end_ms":28000},"outro":{"start_ms":3300000,"end_ms":3400000}}`)
	}))
	defer srv.Close()

	origURL := introdDbAppURL
	introdDbAppURL = srv.URL
	t.Cleanup(func() { introdDbAppURL = origURL })

	data, err := fetchIntroDBApp(&http.Client{}, "tt1234567", 2, 3)
	require.NoError(t, err)
	require.NotNil(t, data)
	require.Len(t, data.Intro, 1)
	assert.Equal(t, int64(5000), *data.Intro[0].StartMs)
	require.Len(t, data.Recap, 1)
	require.Len(t, data.Credits, 1) // "outro" maps to credits
}

func TestFetchIntroDBApp_NullFields(t *testing.T) {
	// Only intro set; recap and outro are null.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"intro":{"start_ms":1000,"end_ms":90000},"recap":null,"outro":null}`)
	}))
	defer srv.Close()

	origURL := introdDbAppURL
	introdDbAppURL = srv.URL
	t.Cleanup(func() { introdDbAppURL = origURL })

	data, err := fetchIntroDBApp(&http.Client{}, "tt9999999", 1, 1)
	require.NoError(t, err)
	require.Len(t, data.Intro, 1)
	assert.Empty(t, data.Recap)
	assert.Empty(t, data.Credits)
}

func TestFetchIntroDBApp_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	origURL := introdDbAppURL
	introdDbAppURL = srv.URL
	t.Cleanup(func() { introdDbAppURL = origURL })

	data, err := fetchIntroDBApp(&http.Client{}, "tt0000000", 1, 1)
	require.NoError(t, err)
	require.NotNil(t, data)
	assert.Empty(t, data.Intro)
}

func TestFetchIntroDBApp_NonOK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "bad gateway", http.StatusBadGateway)
	}))
	defer srv.Close()

	origURL := introdDbAppURL
	introdDbAppURL = srv.URL
	t.Cleanup(func() { introdDbAppURL = origURL })

	_, err := fetchIntroDBApp(&http.Client{}, "tt1111111", 1, 1)
	assert.Error(t, err)
}

func TestFetchIntroDBApp_MalformedJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{not valid json`)
	}))
	defer srv.Close()

	origURL := introdDbAppURL
	introdDbAppURL = srv.URL
	t.Cleanup(func() { introdDbAppURL = origURL })

	_, err := fetchIntroDBApp(&http.Client{}, "tt2222222", 1, 1)
	assert.Error(t, err)
}

// ── fetchWatchOptions ──────────────────────────────────────────────────────────

func TestFetchWatchOptions_NoAPIKey(t *testing.T) {
	t.Setenv("TMDB_API_KEY", "")
	_, err := fetchWatchOptions("movie", "550")
	assert.ErrorContains(t, err, "TMDB_API_KEY not set")
}

func TestFetchWatchOptions_HappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":{"US":{"flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/n.jpg"},{"provider_id":337,"provider_name":"Disney+","logo_path":"/d.jpg"}],"rent":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/a.jpg"}],"link":"https://jw.link/us"}}}`)
	}))
	defer srv.Close()

	t.Setenv("TMDB_API_KEY", "testkey")
	origBase := watchOptionsBaseURL
	watchOptionsBaseURL = srv.URL
	origClient := watchOptionsClient
	watchOptionsClient = &http.Client{}
	t.Cleanup(func() {
		watchOptionsBaseURL = origBase
		watchOptionsClient = origClient
	})

	opts, err := fetchWatchOptions("movie", "550")
	require.NoError(t, err)
	require.Len(t, opts, 3)
	assert.Equal(t, "flatrate", opts[0].Type)
	assert.Equal(t, "Netflix", opts[0].ProviderName)
	assert.Equal(t, 8, opts[0].ProviderID)
	assert.Equal(t, "flatrate", opts[1].Type)
	assert.Equal(t, "rent", opts[2].Type)
	assert.Equal(t, "https://jw.link/us", opts[2].Link)
}

func TestFetchWatchOptions_NoUSResult(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":{"GB":{"flatrate":[],"link":"https://jw.link/gb"}}}`)
	}))
	defer srv.Close()

	t.Setenv("TMDB_API_KEY", "testkey")
	origBase := watchOptionsBaseURL
	watchOptionsBaseURL = srv.URL
	origClient := watchOptionsClient
	watchOptionsClient = &http.Client{}
	t.Cleanup(func() {
		watchOptionsBaseURL = origBase
		watchOptionsClient = origClient
	})

	opts, err := fetchWatchOptions("movie", "550")
	require.NoError(t, err)
	assert.Empty(t, opts)
}

func TestFetchWatchOptions_EmptyResults(t *testing.T) {
	// US key present but both flatrate and rent are empty → return empty slice (not nil).
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":{"US":{"flatrate":[],"rent":[],"link":"https://jw.link/us"}}}`)
	}))
	defer srv.Close()

	t.Setenv("TMDB_API_KEY", "testkey")
	origBase := watchOptionsBaseURL
	watchOptionsBaseURL = srv.URL
	origClient := watchOptionsClient
	watchOptionsClient = &http.Client{}
	t.Cleanup(func() {
		watchOptionsBaseURL = origBase
		watchOptionsClient = origClient
	})

	opts, err := fetchWatchOptions("tv", "1396")
	require.NoError(t, err)
	assert.NotNil(t, opts, "empty results must return an empty slice, not nil")
	assert.Len(t, opts, 0)
}

func TestFetchWatchOptions_MalformedJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{not json`)
	}))
	defer srv.Close()

	t.Setenv("TMDB_API_KEY", "testkey")
	origBase := watchOptionsBaseURL
	watchOptionsBaseURL = srv.URL
	origClient := watchOptionsClient
	watchOptionsClient = &http.Client{}
	t.Cleanup(func() {
		watchOptionsBaseURL = origBase
		watchOptionsClient = origClient
	})

	_, err := fetchWatchOptions("movie", "550")
	assert.Error(t, err)
}
