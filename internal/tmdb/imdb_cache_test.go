package tmdb

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// withTestBaseURL points the package-level baseURL at an httptest.Server for
// the duration of the test, restoring the real TMDB URL afterward.
func withTestBaseURL(t *testing.T, srv *httptest.Server) {
	t.Helper()
	orig := baseURL
	baseURL = srv.URL
	t.Cleanup(func() { baseURL = orig })
}

func TestGetIMDBId_CachesAcrossSequentialCalls(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"imdb_id":"tt1234567"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	id1, err := c.GetIMDBId(603)
	require.NoError(t, err)
	assert.Equal(t, "tt1234567", id1)

	id2, err := c.GetIMDBId(603)
	require.NoError(t, err)
	assert.Equal(t, "tt1234567", id2)

	assert.Equal(t, int32(1), atomic.LoadInt32(&hits), "second call should be served from cache")
}

func TestGetIMDBId_ConcurrentCallsCoalesce(t *testing.T) {
	var hits int32
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		<-release // hold every request open until the test releases them together
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"imdb_id":"tt9999999"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	const n = 5
	var wg sync.WaitGroup
	results := make([]string, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i], errs[i] = c.GetIMDBId(603)
		}(i)
	}

	// Give every goroutine a chance to reach the handler before releasing —
	// singleflight only coalesces requests that overlap in time.
	time.Sleep(100 * time.Millisecond)
	close(release)
	wg.Wait()

	for i := 0; i < n; i++ {
		require.NoError(t, errs[i])
		assert.Equal(t, "tt9999999", results[i])
	}
	assert.Equal(t, int32(1), atomic.LoadInt32(&hits), "concurrent calls for the same id should coalesce into one upstream hit")
}

func TestGetIMDBId_MovieAndTVDoNotCollide(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/movie/603" {
			fmt.Fprint(w, `{"imdb_id":"tt_movie"}`)
			return
		}
		fmt.Fprint(w, `{"imdb_id":"tt_tv"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	movieID, err := c.GetIMDBId(603)
	require.NoError(t, err)
	tvID, err := c.GetTVIMDBId(603)
	require.NoError(t, err)

	assert.Equal(t, "tt_movie", movieID)
	assert.Equal(t, "tt_tv", tvID)
}

func TestGetIMDBId_NonOKStatusNotCached(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	_, err := c.GetIMDBId(603)
	assert.Error(t, err)
	_, err = c.GetIMDBId(603)
	assert.Error(t, err)

	// A failed lookup must never be cached — both calls should hit upstream.
	assert.Equal(t, int32(2), atomic.LoadInt32(&hits))
}

func TestGetDetails_CachesAcrossSequentialCalls(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"overview":"a movie","runtime":120}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	d1, err := c.GetDetails(603, "movie")
	require.NoError(t, err)
	assert.Equal(t, 120, d1.Runtime)

	d2, err := c.GetDetails(603, "movie")
	require.NoError(t, err)
	assert.Equal(t, d1, d2)

	assert.Equal(t, int32(1), atomic.LoadInt32(&hits), "second call should be served from cache")
}

func TestGetDetails_MovieAndTVDoNotCollide(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/movie/603" {
			fmt.Fprint(w, `{"runtime":100}`)
			return
		}
		fmt.Fprint(w, `{"runtime":45,"status":"Returning Series"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	movie, err := c.GetDetails(603, "movie")
	require.NoError(t, err)
	tv, err := c.GetDetails(603, "tv")
	require.NoError(t, err)

	assert.Equal(t, 100, movie.Runtime)
	assert.Equal(t, 45, tv.Runtime)
	assert.Equal(t, "Returning Series", tv.Status)
}

func TestGetDetails_ConcurrentCallsCoalesce(t *testing.T) {
	var hits int32
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		<-release
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"runtime":90}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	const n = 5
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := c.GetDetails(42, "movie")
			assert.NoError(t, err)
		}()
	}
	time.Sleep(100 * time.Millisecond)
	close(release)
	wg.Wait()

	assert.Equal(t, int32(1), atomic.LoadInt32(&hits))
}

func TestGetTVIMDBId_EmptyResultNotCached(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"imdb_id":""}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	c := New("key")

	id, err := c.GetTVIMDBId(1396)
	require.NoError(t, err)
	assert.Empty(t, id)

	id, err = c.GetTVIMDBId(1396)
	require.NoError(t, err)
	assert.Empty(t, id)

	assert.Equal(t, int32(2), atomic.LoadInt32(&hits), "an empty id should never be cached")
}
