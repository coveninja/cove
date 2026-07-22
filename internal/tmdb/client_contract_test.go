package tmdb

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSearchByKeywordsEncodesQueryAndMapsResults(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/search/keyword":
			assert.Equal(t, "rock & roll", r.URL.Query().Get("query"))
			fmt.Fprint(w, `{"results":[{"id":11},{"id":22}]}`)
		case "/discover/movie":
			assert.Equal(t, "11|22", r.URL.Query().Get("with_keywords"))
			fmt.Fprint(w, `{"results":[{"id":1,"title":"Movie","poster_path":"/movie.jpg"},{"id":2,"title":"No poster"}]}`)
		case "/discover/tv":
			fmt.Fprint(w, `{"results":[{"id":3,"name":"Series","poster_path":"/tv.jpg"}]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	items, err := New("api-key").SearchByKeywords("rock & roll")
	require.NoError(t, err)
	require.Len(t, items, 2)
	assert.Equal(t, []string{"/search/keyword", "/discover/movie", "/discover/tv"}, paths)
	assert.Equal(t, "movie", items[0].MediaType)
	assert.Equal(t, "http://127.0.0.1:6969/api/img/w500/movie.jpg", items[0].PosterURL)
	assert.Equal(t, "tv", items[1].MediaType)
}

func TestSearchByKeywordsReportsKeywordLookupStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	_, err := New("key").SearchByKeywords("anything")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "HTTP 429")
}

func TestSuggestKeywordsEncodesCapsAndReportsStatus(t *testing.T) {
	var returnError atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "crime & punishment", r.URL.Query().Get("query"))
		if returnError.Load() {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[`)
		for i := 0; i < 12; i++ {
			if i > 0 {
				fmt.Fprint(w, ",")
			}
			fmt.Fprintf(w, `{"id":%d,"name":"keyword-%d"}`, i, i)
		}
		fmt.Fprint(w, `]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	keywords, err := New("key").SuggestKeywords("crime & punishment")
	require.NoError(t, err)
	require.Len(t, keywords, 10)
	assert.Equal(t, "keyword-9", keywords[9].Name)

	returnError.Store(true)
	_, err = New("key").SuggestKeywords("crime & punishment")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "HTTP 503")
}

func TestGetEpisodesCachedMapsStillAndRetriesFailures(t *testing.T) {
	var hits atomic.Int32
	var fail atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		wantPath := "/tv/42/season/2"
		if fail.Load() {
			wantPath = "/tv/99/season/2"
		}
		assert.Equal(t, wantPath, r.URL.Path)
		assert.Equal(t, "key", r.URL.Query().Get("api_key"))
		if fail.Load() {
			http.Error(w, "upstream", http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"episodes":[{"episode_number":1,"name":"One","still_path":"/one.jpg"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	client := New("key")
	episodes, err := client.GetEpisodesCached(42, 2)
	require.NoError(t, err)
	require.Len(t, episodes, 1)
	assert.Equal(t, "http://127.0.0.1:6969/api/img/w300/one.jpg", episodes[0].StillPath)
	_, err = client.GetEpisodesCached(42, 2)
	require.NoError(t, err)
	assert.Equal(t, int32(1), hits.Load(), "cache hit should avoid a second request")

	fail.Store(true)
	for i := 0; i < 2; i++ {
		_, err = client.GetEpisodesCached(99, 2)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "HTTP 502")
	}
	assert.Equal(t, int32(3), hits.Load(), "failed responses must not be cached")
}

func TestDiscoverBuildsQueryAndMapsResults(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/discover/movie", r.URL.Path)
		q := r.URL.Query()
		assert.Equal(t, "key", q.Get("api_key"))
		assert.Equal(t, "2", q.Get("page"))
		assert.Equal(t, "28|12", q.Get("with_genres"))
		assert.Equal(t, "99,100", q.Get("without_keywords"))
		assert.Equal(t, "7.5", q.Get("vote_average.gte"))
		assert.Equal(t, "US", q.Get("certification_country"))
		assert.Equal(t, "PG-13", q.Get("certification.lte"))
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"page":2,"total_pages":4,"results":[{"id":7,"title":"Mapped","poster_path":"/poster.jpg"},{"id":8,"title":"No poster"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	result, err := New("key").Discover(DiscoverParams{
		MediaType: "movie", Page: 2, WithGenres: []int{28, 12},
		WithoutKeywords: []int{99, 100}, MinVoteAverage: 7.5,
		CertCountry: "US", CertLTE: "PG-13",
	})
	require.NoError(t, err)
	assert.Equal(t, 4, result.TotalPages)
	require.Len(t, result.Results, 2)
	assert.Equal(t, "movie", result.Results[0].MediaType)
	assert.Equal(t, "http://127.0.0.1:6969/api/img/w500/poster.jpg", result.Results[0].PosterURL)
	assert.Empty(t, result.Results[1].PosterURL)

	_, err = New("key").Discover(DiscoverParams{MediaType: "person"})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "invalid media type")
}

func TestSearchPeopleFiltersUnrenderableEntries(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "Zoë Example", r.URL.Query().Get("query"))
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":[
			{"id":1,"name":"Visible","profile_path":"/person.jpg","known_for":[
				{"id":10,"media_type":"movie","poster_path":"/movie.jpg"},
				{"id":11,"media_type":"person","poster_path":"/ignored.jpg"},
				{"id":12,"media_type":"tv"}]},
			{"id":2,"name":"No profile","profile_path":""}
		]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	people, err := New("key").SearchPeople("Zoë.Example")
	require.NoError(t, err)
	require.Len(t, people, 1)
	assert.Equal(t, "http://127.0.0.1:6969/api/img/w500/person.jpg", people[0].ProfileURL)
	require.Len(t, people[0].KnownFor, 1)
	assert.Equal(t, "http://127.0.0.1:6969/api/img/w500/movie.jpg", people[0].KnownFor[0].PosterURL)
}

func TestFindByIMDBIDSelectsRequestedMediaType(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.True(t, strings.HasPrefix(r.URL.Path, "/find/tt"))
		id, _ := strconv.Atoi(strings.TrimPrefix(r.URL.Path, "/find/tt"))
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"movie_results":[{"id":%d,"poster_path":"/movie.jpg"}],"tv_results":[{"id":%d,"poster_path":"/tv.jpg"}]}`, id, id+1)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	movie, err := New("key").FindByIMDBId(context.Background(), "tt41", "movie")
	require.NoError(t, err)
	assert.Equal(t, 41, movie.ID)
	assert.Equal(t, "movie", movie.MediaType)
	tv, err := New("key").FindByIMDBId(context.Background(), "tt41", "tv")
	require.NoError(t, err)
	assert.Equal(t, 42, tv.ID)
	assert.Equal(t, "tv", tv.MediaType)
}

func TestClientMethodsRejectNonOKResponsesBeforeDecoding(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	tests := []struct {
		name string
		call func(*Client) error
	}{
		{"search people", func(c *Client) error { _, err := c.SearchPeople("name"); return err }},
		{"get person", func(c *Client) error { _, err := c.GetPerson(7); return err }},
		{"discover by provider", func(c *Client) error { _, err := c.DiscoverByProvider("movie", 8, 4); return err }},
		{"get seasons", func(c *Client) error { _, err := c.GetSeasons(9); return err }},
		{"get episodes", func(c *Client) error { _, err := c.GetEpisodes(9, 2); return err }},
		{"genre list", func(c *Client) error { _, err := c.GenreList("tv"); return err }},
		{"discover", func(c *Client) error { _, err := c.Discover(DiscoverParams{MediaType: "tv"}); return err }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.call(New("key"))
			require.Error(t, err)
			assert.Contains(t, err.Error(), "502")
		})
	}
}
