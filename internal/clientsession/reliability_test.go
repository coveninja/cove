package clientsession

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) {
	return 0, errors.New("read failed")
}

func sessionRequest(mux *http.ServeMux, method string, body io.Reader) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, "/api/client-session", body)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func TestClientSessionPathFailure(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	require.NoError(t, os.WriteFile(blocker, []byte("file"), 0o600))
	utils.SetDataDir(blocker)
	mux := http.NewServeMux()
	SetupHandlers(mux)

	rec := sessionRequest(mux, http.MethodGet, nil)
	assert.Equal(t, http.StatusInternalServerError, rec.Code)
	assert.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
}

func TestClientSessionGetRejectsUnreadableAndCorruptFiles(t *testing.T) {
	t.Run("read failure", func(t *testing.T) {
		utils.SetDataDir(t.TempDir())
		path, err := filePath()
		require.NoError(t, err)
		require.NoError(t, os.Mkdir(path, 0o700))
		mux := http.NewServeMux()
		SetupHandlers(mux)

		rec := sessionRequest(mux, http.MethodGet, nil)
		assert.Equal(t, http.StatusInternalServerError, rec.Code)
	})

	t.Run("invalid stored JSON", func(t *testing.T) {
		utils.SetDataDir(t.TempDir())
		path, err := filePath()
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte("{"), 0o600))
		mux := http.NewServeMux()
		SetupHandlers(mux)

		rec := sessionRequest(mux, http.MethodGet, nil)
		assert.Equal(t, http.StatusInternalServerError, rec.Code)
		assert.Contains(t, rec.Body.String(), "stored session is invalid")
	})
}

func TestClientSessionPostReadAndWriteFailures(t *testing.T) {
	t.Run("body read failure", func(t *testing.T) {
		utils.SetDataDir(t.TempDir())
		mux := http.NewServeMux()
		SetupHandlers(mux)
		req := httptest.NewRequest(http.MethodPost, "/api/client-session", nil)
		req.Body = io.NopCloser(failingReader{})
		rec := httptest.NewRecorder()

		mux.ServeHTTP(rec, req)
		assert.Equal(t, http.StatusBadRequest, rec.Code)
	})

	t.Run("atomic write failure", func(t *testing.T) {
		utils.SetDataDir(t.TempDir())
		path, err := filePath()
		require.NoError(t, err)
		require.NoError(t, os.Mkdir(path, 0o700))
		mux := http.NewServeMux()
		SetupHandlers(mux)

		rec := sessionRequest(mux, http.MethodPost, strings.NewReader(`{"token":"value"}`))
		assert.Equal(t, http.StatusInternalServerError, rec.Code)

		matches, err := filepath.Glob(filepath.Join(filepath.Dir(path), "session.json.tmp-*"))
		require.NoError(t, err)
		assert.Empty(t, matches, "failed atomic write should clean up its temporary file")
	})
}

func TestClientSessionDeleteReportsFilesystemFailure(t *testing.T) {
	utils.SetDataDir(t.TempDir())
	path, err := filePath()
	require.NoError(t, err)
	require.NoError(t, os.Mkdir(path, 0o700))
	require.NoError(t, os.WriteFile(filepath.Join(path, "child"), []byte("data"), 0o600))
	mux := http.NewServeMux()
	SetupHandlers(mux)

	rec := sessionRequest(mux, http.MethodDelete, nil)
	assert.Equal(t, http.StatusInternalServerError, rec.Code)
	_, err = os.Stat(path)
	assert.NoError(t, err, "failed deletion must not be reported as successful")
}

func TestConcurrentClientSessionReadsObserveCompleteJSON(t *testing.T) {
	utils.SetDataDir(t.TempDir())
	mux := http.NewServeMux()
	SetupHandlers(mux)

	const payloadSize = 128 << 10
	payloads := []string{
		`{"token":"` + strings.Repeat("a", payloadSize) + `"}`,
		`{"token":"` + strings.Repeat("b", payloadSize) + `"}`,
	}
	seed := sessionRequest(mux, http.MethodPost, strings.NewReader(payloads[0]))
	require.Equal(t, http.StatusNoContent, seed.Code, seed.Body.String())

	var wg sync.WaitGroup
	errs := make(chan string, 64)
	for i := 0; i < 8; i++ {
		payload := payloads[i%len(payloads)]
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 12; j++ {
				rec := sessionRequest(mux, http.MethodPost, strings.NewReader(payload))
				if rec.Code != http.StatusNoContent {
					errs <- "POST: " + rec.Body.String()
					return
				}
			}
		}()
	}
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 24; j++ {
				rec := sessionRequest(mux, http.MethodGet, nil)
				if rec.Code != http.StatusOK {
					errs <- "GET: " + rec.Body.String()
					return
				}
				if !json.Valid(rec.Body.Bytes()) {
					errs <- "GET returned incomplete JSON"
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
}
