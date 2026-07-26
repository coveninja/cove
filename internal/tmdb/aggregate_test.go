package tmdb

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/addons"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ── Catalog cache ─────────────────────────────────────────────────────────────

func TestCatalogCache_HitAndSeparation(t *testing.T) {
	c := New("key")

	// cold cache must miss
	_, _, ok := c.catalogCacheGet("k1")
	assert.False(t, ok, "empty cache should miss")

	medias := []Media{{ID: 1, Title: "Alpha"}, {ID: 2, Title: "Beta"}}
	c.catalogCacheSet("k1", medias, 42)

	got, nextSkip, ok := c.catalogCacheGet("k1")
	assert.True(t, ok)
	assert.Equal(t, medias, got)
	assert.Equal(t, 42, nextSkip)

	// a different key must not collide
	_, _, ok = c.catalogCacheGet("k2")
	assert.False(t, ok)
}

func TestCatalogCache_DeepCopiesNestedMediaFields(t *testing.T) {
	c := New("key")
	source := []Media{{ID: 1, Images: []string{"one"}, GenreIDs: []int{7}}}
	c.catalogCacheSet("nested", source, 0)
	source[0].Images[0] = "source-mutated"
	source[0].GenreIDs[0] = 99

	first, _, ok := c.catalogCacheGet("nested")
	require.True(t, ok)
	assert.Equal(t, []string{"one"}, first[0].Images)
	assert.Equal(t, []int{7}, first[0].GenreIDs)
	first[0].Images[0] = "caller-mutated"
	first[0].GenreIDs[0] = 42

	second, _, ok := c.catalogCacheGet("nested")
	require.True(t, ok)
	assert.Equal(t, []string{"one"}, second[0].Images)
	assert.Equal(t, []int{7}, second[0].GenreIDs)
}

func TestCatalogCache_ExpiredEntryIsMiss(t *testing.T) {
	c := New("key")
	// Plant an already-expired entry directly so we don't have to wait for TTL.
	c.catalogCacheMu.Lock()
	c.catalogCache["stale"] = catalogPageEntry{
		medias:   []Media{{ID: 9}},
		nextSkip: 5,
		expires:  time.Now().Add(-time.Second),
	}
	c.catalogCacheMu.Unlock()

	_, _, ok := c.catalogCacheGet("stale")
	assert.False(t, ok, "expired entry should be a miss")
}

func TestCatalogCache_SetSweepsExpiredEntries(t *testing.T) {
	c := New("key")
	// Plant an expired entry that the next catalogCacheSet call should sweep.
	c.catalogCacheMu.Lock()
	c.catalogCache["old"] = catalogPageEntry{expires: time.Now().Add(-time.Second)}
	c.catalogCacheMu.Unlock()

	c.catalogCacheSet("new", []Media{{ID: 1}}, 0)

	c.catalogCacheMu.Lock()
	_, stillThere := c.catalogCache["old"]
	c.catalogCacheMu.Unlock()
	assert.False(t, stillThere, "catalogCacheSet should sweep expired entries inline")
}

// ── Search ────────────────────────────────────────────────────────────────────

func TestSearch_MergesAndFiltersNoPoster(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(r.URL.Path, "/search/movie"):
			fmt.Fprint(w, `{"results":[
				{"id":1,"title":"Alpha","poster_path":"/a.jpg","popularity":9.0},
				{"id":2,"title":"NoPoster","popularity":5.0}
			]}`)
		case strings.Contains(r.URL.Path, "/search/tv"):
			fmt.Fprint(w, `{"results":[{"id":3,"name":"Beta","poster_path":"/b.jpg","popularity":7.0}]}`)
		default:
			fmt.Fprint(w, `{"results":[]}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	results, err := New("key").Search("alpha")
	require.NoError(t, err)
	// Every returned entry must have a poster (no-poster entries are dropped).
	for _, m := range results {
		assert.NotEmpty(t, m.PosterURL, "posterless entries should be filtered out")
	}
	// MediaType is set from the search endpoint name.
	var mediaTypes []string
	for _, m := range results {
		mediaTypes = append(mediaTypes, m.MediaType)
	}
	assert.Contains(t, mediaTypes, "movie")
	assert.Contains(t, mediaTypes, "tv")
}

func TestSearchKeepsMovieAndTVWithSameNumericID(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "/search/movie") {
			fmt.Fprint(w, `{"results":[{"id":7,"title":"Movie","poster_path":"/movie.jpg","popularity":2}]}`)
		} else {
			fmt.Fprint(w, `{"results":[{"id":7,"name":"Series","poster_path":"/tv.jpg","popularity":1}]}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	results, err := New("key").Search("collision")
	require.NoError(t, err)
	require.Len(t, results, 2)
	assert.Equal(t, "movie", results[0].MediaType)
	assert.Equal(t, "tv", results[1].MediaType)
}

func TestSearch_DecodeErrorPropagates(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// Return malformed JSON on a 200 so the decode step errors.
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{not valid json`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").Search("anything")
	require.Error(t, err)
}

// ── SearchProviders ───────────────────────────────────────────────────────────

func TestSearchProviders_FiltersByNameSubstringAndAbsolutisesLogo(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[
			{"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.png","display_priority":1},
			{"provider_id":9,"provider_name":"Amazon Prime","logo_path":"/prime.png","display_priority":2}
		]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	providers, err := New("key").SearchProviders("netflix")
	require.NoError(t, err)
	require.Len(t, providers, 1)
	assert.Equal(t, "Netflix", providers[0].Name)
	assert.NotEmpty(t, providers[0].LogoURL)
}

func TestSearchProviders_EmptyQueryReturnsNilWithoutHittingUpstream(t *testing.T) {
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hits++
		http.Error(w, "should not be called", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	providers, err := New("key").SearchProviders("")
	require.NoError(t, err)
	assert.Nil(t, providers)
	assert.Equal(t, 0, hits, "empty query must not hit upstream")
}

func TestSearchProviders_NonOKResponseSwallowedSilently(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	// SearchProviders swallows upstream errors; caller gets empty results, not an error.
	providers, err := New("key").SearchProviders("any")
	require.NoError(t, err)
	assert.Empty(t, providers)
}

// ── MultiSearch ───────────────────────────────────────────────────────────────

func TestSplitSearchTitles_PreservesInterleavedOrderAndDeduplicates(t *testing.T) {
	movie1 := Media{ID: 1, MediaType: "movie"}
	tv2 := Media{ID: 2, MediaType: "tv"}
	movie3 := Media{ID: 3, MediaType: "movie"}

	movies, tv, titleOrder := splitSearchTitles(
		[]Media{tv2, movie1, tv2},
		[]Media{movie3, movie1, {ID: 4, MediaType: "person"}},
	)

	assert.Equal(t, []Media{movie1, movie3}, movies)
	assert.Equal(t, []Media{tv2}, tv)
	assert.Equal(t, []string{"tv:2", "movie:1", "movie:3"}, titleOrder)
}

func TestSplitSearchTitles_EmptySlicesSerializeAsArrays(t *testing.T) {
	movies, tv, titleOrder := splitSearchTitles(nil, nil)

	assert.NotNil(t, movies)
	assert.NotNil(t, tv)
	assert.NotNil(t, titleOrder)

	payload, err := json.Marshal(SearchResults{
		Movies:     movies,
		TV:         tv,
		People:     []Person{},
		Providers:  []Provider{},
		TitleOrder: titleOrder,
	})
	require.NoError(t, err)
	assert.JSONEq(t, `{
		"movies": [],
		"tv": [],
		"people": [],
		"providers": [],
		"title_order": []
	}`, string(payload))
}

func TestMultiSearch_SectionsResultsByType(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(r.URL.Path, "/search/movie"):
			fmt.Fprint(w, `{"results":[{"id":1,"title":"Film","poster_path":"/f.jpg","popularity":5.0}]}`)
		case strings.Contains(r.URL.Path, "/search/tv"):
			fmt.Fprint(w, `{"results":[{"id":2,"name":"Show","poster_path":"/s.jpg","popularity":10.0}]}`)
		default:
			fmt.Fprint(w, `{"results":[]}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	results, err := New("key").MultiSearch("Film")
	require.NoError(t, err)
	// Each section is always a non-nil slice (marshals as [] not null).
	assert.NotNil(t, results.Movies)
	assert.NotNil(t, results.TV)
	assert.NotNil(t, results.People)
	assert.NotNil(t, results.Providers)
	assert.Equal(t, []string{"tv:2", "movie:1"}, results.TitleOrder)
}

func TestMultiSearch_SearchErrorPropagates(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// Malformed JSON on 200 triggers a decode error inside Search.
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{bad`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").MultiSearch("anything")
	require.Error(t, err)
}

// ── ProviderTitles ────────────────────────────────────────────────────────────

func TestProviderTitles_BlendsMovieAndTVSortedByPopularity(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(r.URL.Path, "/discover/movie"):
			fmt.Fprint(w, `{"results":[{"id":1,"title":"MovieA","poster_path":"/m.jpg","popularity":8.0}]}`)
		case strings.Contains(r.URL.Path, "/discover/tv"):
			fmt.Fprint(w, `{"results":[{"id":2,"name":"ShowA","poster_path":"/s.jpg","popularity":10.0}]}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	titles, err := New("key").ProviderTitles(8, 5)
	require.NoError(t, err)
	require.Len(t, titles, 2)
	// ShowA has higher popularity — it must come first.
	assert.Equal(t, 2, titles[0].ID, "higher-popularity title should be first")
}

func TestProviderTitles_UpstreamErrorsSwallowedReturnsEmpty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "server error", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	// Both DiscoverByProvider calls fail → empty list, no error.
	titles, err := New("key").ProviderTitles(8, 5)
	require.NoError(t, err)
	assert.Empty(t, titles)
}

// ── GetImages ─────────────────────────────────────────────────────────────────

func TestGetImages_AbsolutisesAllImageTypes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/movie/42/images", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{
			"backdrops":[{"file_path":"/bd.jpg"}],
			"logos":[{"file_path":"/logo.png"}],
			"posters":[{"file_path":"/poster.jpg"}]
		}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	images, err := New("key").GetImages(42, "movie")
	require.NoError(t, err)
	require.Len(t, images.Backdrops, 1)
	assert.Contains(t, images.Backdrops[0].URL, "bd.jpg")
	require.Len(t, images.Logos, 1)
	assert.Contains(t, images.Logos[0].URL, "logo.png")
	require.Len(t, images.Posters, 1)
	assert.Contains(t, images.Posters[0].URL, "poster.jpg")
}

func TestGetImages_NonOKReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "not found", http.StatusNotFound)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").GetImages(1, "movie")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "404")
}

// ── GetVideos ─────────────────────────────────────────────────────────────────

func TestGetVideos_ReturnsResultsForTVShow(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/tv/100/videos", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[{"key":"abc123","site":"YouTube","name":"Trailer"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	videos, err := New("key").GetVideos(100, "tv")
	require.NoError(t, err)
	require.Len(t, videos.Results, 1)
	assert.Equal(t, "abc123", videos.Results[0].Key)
	assert.Equal(t, "YouTube", videos.Results[0].Site)
	assert.Equal(t, "https://www.youtube.com/embed/abc123", videos.Results[0].EmbedURL)
}

func TestGetVideos_NonOKReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "server error", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").GetVideos(1, "movie")
	require.Error(t, err)
}

// ── GetMediaByID ──────────────────────────────────────────────────────────────

func TestGetMediaByID_SetsMediaTypeAndAbsolutisesPoster(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/movie/99":
			fmt.Fprint(w, `{"id":99,"title":"Inception","poster_path":"/poster.jpg"}`)
		case "/tv/100":
			fmt.Fprint(w, `{"id":100,"name":"Stranger Things","poster_path":"/tv.jpg"}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	movie, err := New("key").GetMediaByID(99, "movie")
	require.NoError(t, err)
	assert.Equal(t, 99, movie.ID)
	assert.Equal(t, "movie", movie.MediaType)
	assert.Contains(t, movie.PosterURL, "poster.jpg")

	show, err := New("key").GetMediaByID(100, "tv")
	require.NoError(t, err)
	assert.Equal(t, "tv", show.MediaType)
	assert.Contains(t, show.PosterURL, "tv.jpg")
}

func TestGetMediaByID_NonOKReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "not found", http.StatusNotFound)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").GetMediaByID(1, "movie")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "404")
}

// ── GetSimilar ────────────────────────────────────────────────────────────────

func TestGetSimilar_FiltersPosterlessAndSetsMediaType(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/movie/7/recommendations", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[
			{"id":1,"title":"A","poster_path":"/a.jpg"},
			{"id":2,"title":"NoPoster"},
			{"id":3,"title":"C","poster_path":"/c.jpg"}
		]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	similar, err := New("key").GetSimilar(7, "movie")
	require.NoError(t, err)
	require.Len(t, similar, 2, "entry without poster_path must be dropped")
	assert.Equal(t, "movie", similar[0].MediaType)
}

func TestGetSimilar_DecodeErrorPropagates(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// Non-200 body is not JSON — the decode step will error.
		http.Error(w, "upstream error", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").GetSimilar(1, "movie")
	require.Error(t, err)
}

// ── GetLogos ──────────────────────────────────────────────────────────────────

func TestGetLogos_CappedAtThreeAndAbsolutised(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/movie/5/images", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"logos":[
			{"file_path":"/l1.png","vote_average":8.0},
			{"file_path":"/l2.png","vote_average":7.0},
			{"file_path":"/l3.png","vote_average":6.0},
			{"file_path":"/l4.png","vote_average":5.0}
		]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	logos, err := New("key").GetLogos(5, "movie")
	require.NoError(t, err)
	require.Len(t, logos, 3, "GetLogos must cap at 3")
	assert.Contains(t, logos[0], "l1.png")
}

func TestGetLogos_DecodeErrorPropagates(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "error", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").GetLogos(1, "movie")
	require.Error(t, err)
}

// ── ResolveMeta ───────────────────────────────────────────────────────────────

func TestResolveMeta_TMDBPrefix(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"id":42,"title":"Dune","poster_path":"/dune.jpg"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	m := New("key").ResolveMeta(context.Background(), addons.StremioMeta{
		Type: "movie",
		ID:   "tmdb:42",
	})
	require.NotNil(t, m)
	assert.Equal(t, 42, m.ID)
	assert.Equal(t, "movie", m.MediaType)
}

func TestResolveMeta_SeriesMappedToTV(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"id":10,"name":"Show","poster_path":"/s.jpg"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	// "series" from Stremio is translated to "tv" for TMDB.
	m := New("key").ResolveMeta(context.Background(), addons.StremioMeta{
		Type: "series",
		ID:   "tmdb:10:1:2", // episode suffix is stripped
	})
	require.NotNil(t, m)
	assert.Equal(t, 10, m.ID)
	assert.Equal(t, "tv", m.MediaType)
}

func TestResolveMeta_IMDBPrefix(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasPrefix(r.URL.Path, "/find/"):
			// FindByIMDBId — return a movie result so the resolver picks id 77.
			fmt.Fprint(w, `{"movie_results":[{"id":77,"poster_path":"/m.jpg"}],"tv_results":[]}`)
		default:
			// GetMediaByID for the resolved TMDB id.
			fmt.Fprint(w, `{"id":77,"title":"Top Gun","poster_path":"/m.jpg"}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	m := New("key").ResolveMeta(context.Background(), addons.StremioMeta{
		Type: "movie",
		ID:   "tt0094675",
	})
	require.NotNil(t, m)
	assert.Equal(t, 77, m.ID)
	assert.Equal(t, "movie", m.MediaType)
}

func TestResolveMeta_UnsupportedInputsReturnNil(t *testing.T) {
	c := New("key")
	ctx := context.Background()

	tests := []struct {
		name string
		meta addons.StremioMeta
	}{
		{"unsupported type", addons.StremioMeta{Type: "channel", ID: "tmdb:1"}},
		{"unknown id prefix", addons.StremioMeta{Type: "movie", ID: "unknown:123"}},
		{"tmdb non-numeric id", addons.StremioMeta{Type: "movie", ID: "tmdb:notanumber"}},
		{"tmdb zero id", addons.StremioMeta{Type: "movie", ID: "tmdb:0"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			assert.Nil(t, c.ResolveMeta(ctx, tt.meta))
		})
	}
}

func TestResolveMeta_UpstreamErrorReturnsNil(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "not found", http.StatusNotFound)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	m := New("key").ResolveMeta(context.Background(), addons.StremioMeta{
		Type: "movie",
		ID:   "tmdb:99",
	})
	assert.Nil(t, m, "failed GetMediaByID must not panic and must return nil")
}

// ── SetupHandlers ─────────────────────────────────────────────────────────────

// newTestAddonMgr returns an empty addon manager suitable for handler-level
// tests. addons.New uses utils.SafeTransport (which blocks loopback), so tests
// here are limited to validation paths and handlers that only need the TMDB
// client (which uses the plain http.Client and can reach the httptest.Server).
func newTestAddonMgr() *addons.Manager {
	return addons.New("__tmdb_test__", nil)
}

func TestSetupHandlers_MissingParamsReturn400(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// Should not be reached for any of the 400 cases below.
		http.Error(w, "unexpected upstream call", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	tests := []struct {
		desc string
		path string
	}{
		{"keywords missing q", "/api/keywords"},
		{"search missing q", "/api/search"},
		{"multi-search missing q", "/api/search/multi"},
		{"person non-integer id", "/api/person?id=bad"},
		{"person non-positive id", "/api/person?id=0"},
		{"provider non-integer id", "/api/provider?id=notanint"},
		{"provider non-positive id", "/api/provider?id=-1"},
		{"images missing id and type", "/api/images"},
		{"images invalid media type", "/api/images?id=1&type=actor"},
		{"images id=0", "/api/images?id=0&type=movie"},
		{"videos missing id and type", "/api/videos"},
		{"videos invalid media type", "/api/videos?id=1&type=studio"},
		{"videos id=0", "/api/videos?id=0&type=tv"},
		{"media missing id and type", "/api/media"},
		{"media invalid media type", "/api/media?id=1&type=person"},
		{"media id=0", "/api/media?id=0&type=movie"},
		{"details invalid id", "/api/details?id=1junk&type=movie"},
		{"details invalid media type", "/api/details?id=1&type=person"},
		{"similar invalid id", "/api/similar?id=0&type=movie"},
		{"similar invalid media type", "/api/similar?id=1&type=person"},
		{"logos invalid id", "/api/logos?id=-1&type=movie"},
		{"logos invalid media type", "/api/logos?id=1&type=person"},
		{"imdb invalid id", "/api/imdb?id=bad"},
		{"seasons invalid id", "/api/tv/seasons?id=0"},
		{"quality/batch missing ids", "/api/quality/batch"},
		{"catalog missing addonId", "/api/catalog"},
		{"catalog missing catalogId", "/api/catalog?addonId=x&catalogType=movie"},
	}
	for _, tt := range tests {
		t.Run(tt.desc, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, tt.path, nil)
			mux.ServeHTTP(rec, req)
			assert.Equal(t, http.StatusBadRequest, rec.Code, "path: %s", tt.path)
		})
	}
}

func TestSetupHandlers_CatalogMethodNotAllowed(t *testing.T) {
	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/catalog?addonId=x&catalogType=movie&catalogId=top", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)
}

func TestSetupHandlers_KeywordsHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Contains(t, r.URL.Path, "/search/keyword")
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[{"id":1,"name":"action"},{"id":2,"name":"adventure"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/keywords?q=action", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Header().Get("Content-Type"), "application/json")
}

func TestSetupHandlers_ImagesHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"backdrops":[],"logos":[{"file_path":"/logo.png"}],"posters":[]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/images?id=7&type=movie", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_VideosHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[{"key":"yt123","site":"YouTube"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/videos?id=7&type=movie", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_MediaHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"id":5,"title":"Test Movie","poster_path":"/p.jpg"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/media?id=5&type=movie", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_SearchHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.Contains(r.URL.Path, "/search/keyword"):
			fmt.Fprint(w, `{"results":[]}`)
		case strings.Contains(r.URL.Path, "/discover/"):
			fmt.Fprint(w, `{"results":[]}`)
		default:
			fmt.Fprint(w, `{"results":[{"id":1,"title":"Hit","poster_path":"/h.jpg","popularity":9.0}]}`)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=hit", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Header().Get("Content-Type"), "application/json")
}

func TestSetupHandlers_SimilarHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[{"id":3,"title":"Like","poster_path":"/l.jpg"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/similar?id=7&type=movie", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_LogosHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"logos":[{"file_path":"/logo.png","vote_average":8.0}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/logos?id=5&type=movie", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_PersonHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/person/12", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{
			"id":12,"name":"Jane Doe","biography":"An actor.",
			"profile_path":"/jane.jpg","known_for_department":"Acting",
			"birthday":"1980-01-01","place_of_birth":"New York",
			"combined_credits":{"cast":[
				{"id":5,"title":"Film","media_type":"movie","poster_path":"/f.jpg","popularity":7.0}
			]}
		}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/person?id=12", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Header().Get("Content-Type"), "application/json")
}

func TestSetupHandlers_TVSeasonsHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/tv/20", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"seasons":[
			{"season_number":0,"name":"Specials","poster_path":"/sp.jpg"},
			{"season_number":1,"name":"Season 1","poster_path":"/s1.jpg"}
		]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/tv/seasons?id=20", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_TVEpisodesHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/tv/20/season/2", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"episodes":[{"episode_number":1,"name":"Pilot","still_path":"/e1.jpg"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/tv/episodes?id=20&season=2", nil)
	mux.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestSetupHandlers_TVEpisodesBadSeason(t *testing.T) {
	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	for _, path := range []string{
		"/api/tv/episodes?id=20&season=bad",
		"/api/tv/episodes?id=20&season=0",
		"/api/tv/episodes?id=bad&season=1",
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, path, nil)
		mux.ServeHTTP(rec, req)
		assert.Equal(t, http.StatusBadRequest, rec.Code, "path: %s", path)
	}
}

// ── GetPerson ─────────────────────────────────────────────────────────────────

// TestGetPerson_HappyPathBuildsPersonDetails exercises the decode + credit
// filtering path that the existing 502-error test doesn't reach.
func TestGetPerson_HappyPathBuildsPersonDetails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/person/7", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{
			"id":7,"name":"Ada Lovelace","biography":"Pioneer.",
			"profile_path":"/ada.jpg","known_for_department":"Acting",
			"birthday":"1815-12-10","place_of_birth":"London",
			"combined_credits":{"cast":[
				{"id":1,"title":"Movie","media_type":"movie","poster_path":"/m.jpg","popularity":5.0},
				{"id":2,"name":"Show","media_type":"tv","poster_path":"/t.jpg","popularity":9.0},
				{"id":3,"name":"NoPoster","media_type":"tv"},
				{"id":4,"name":"Person","media_type":"person","poster_path":"/p.jpg"}
			]}
		}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	pd, err := New("key").GetPerson(7)
	require.NoError(t, err)
	assert.Equal(t, 7, pd.ID)
	assert.Equal(t, "Ada Lovelace", pd.Name)
	assert.Contains(t, pd.ProfileURL, "ada.jpg")
	// Only movie/tv with a poster_path are included; person and no-poster are dropped.
	assert.Len(t, pd.Credits, 2)
	// Credits are popularity-sorted: Show (9.0) before Movie (5.0).
	assert.Equal(t, 2, pd.Credits[0].ID)
}

func TestGetPersonKeepsMovieAndTVWithSameNumericID(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"id":7,"name":"Actor","combined_credits":{"cast":[
			{"id":5,"title":"Movie","media_type":"movie","poster_path":"/m.jpg","popularity":2},
			{"id":5,"name":"Series","media_type":"tv","poster_path":"/t.jpg","popularity":1}
		]}}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	person, err := New("key").GetPerson(7)
	require.NoError(t, err)
	require.Len(t, person.Credits, 2)
	assert.Equal(t, "movie", person.Credits[0].MediaType)
	assert.Equal(t, "tv", person.Credits[1].MediaType)
}

// ── GetSeasons ────────────────────────────────────────────────────────────────

// TestGetSeasons_FiltersSpecials exercises the season-0 filtering path.
func TestGetSeasons_FiltersSpecialsAndAbsolutisesPoster(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/tv/55", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"seasons":[
			{"season_number":0,"name":"Specials","poster_path":"/sp.jpg"},
			{"season_number":1,"name":"Season 1","poster_path":"/s1.jpg"},
			{"season_number":2,"name":"Season 2"}
		]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	seasons, err := New("key").GetSeasons(55)
	require.NoError(t, err)
	// Season 0 (specials) must be excluded.
	require.Len(t, seasons, 2)
	assert.Equal(t, 1, seasons[0].SeasonNumber)
	assert.Contains(t, seasons[0].PosterPath, "s1.jpg")
	// Season 2 has no poster — PosterPath stays empty.
	assert.Empty(t, seasons[1].PosterPath)
}
