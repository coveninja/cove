package utils

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSrtToVTT(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{
			name:  "empty",
			input: "",
			want:  "WEBVTT\n\n",
		},
		{
			name:  "single cue — comma to dot",
			input: "1\n00:00:01,000 --> 00:00:03,500\nHello world\n",
			want:  "WEBVTT\n\n1\n00:00:01.000 --> 00:00:03.500\nHello world\n",
		},
		{
			name:  "multi-line cue",
			input: "1\n00:00:01,000 --> 00:00:03,000\nLine one\nLine two\n",
			want:  "WEBVTT\n\n1\n00:00:01.000 --> 00:00:03.000\nLine one\nLine two\n",
		},
		{
			name:  "preserves commas in dialogue",
			input: "1\n00:00:01,000 --> 00:00:02,000\nHello, world\n",
			want:  "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nHello, world\n",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.want, SrtToVTT(tc.input))
		})
	}
}

func TestLocalAddr(t *testing.T) {
	previous := localAddr
	t.Cleanup(func() { localAddr = previous })

	SetLocalAddr("192.0.2.10:7000")
	assert.Equal(t, "192.0.2.10:7000", LocalAddr())

	SetLocalAddr("")
	assert.Equal(t, "192.0.2.10:7000", LocalAddr(), "an empty address must be ignored")
}

func TestConfigPath(t *testing.T) {
	previous := dataDirOverride.Load()
	t.Cleanup(func() { dataDirOverride.Store(previous) })
	dataDirOverride.Store(nil)

	t.Run("platform config directory", func(t *testing.T) {
		configHome := t.TempDir()
		t.Setenv("XDG_CONFIG_HOME", configHome)
		t.Setenv("AppData", configHome)
		t.Setenv("HOME", configHome)

		userConfigDir, err := os.UserConfigDir()
		require.NoError(t, err)

		path, err := ConfigPath("settings.json")
		require.NoError(t, err)
		assert.Equal(t, filepath.Join(userConfigDir, "cove", "settings.json"), path)
		info, err := os.Stat(filepath.Dir(path))
		require.NoError(t, err)
		assert.True(t, info.IsDir())
	})

	t.Run("override and nested filename", func(t *testing.T) {
		override := t.TempDir()
		SetDataDir(override)

		path, err := ConfigPath("mpv/mpv.conf")
		require.NoError(t, err)
		assert.Equal(t, filepath.Join(override, "mpv", "mpv.conf"), path)

		SetDataDir("")
		unchanged, err := ConfigPath("settings.json")
		require.NoError(t, err)
		assert.Equal(t, filepath.Join(override, "settings.json"), unchanged)
	})
}

func TestConfigPathRejectsEscapes(t *testing.T) {
	previous := dataDirOverride.Load()
	t.Cleanup(func() { dataDirOverride.Store(previous) })
	dataDirOverride.Store(nil)

	for _, filename := range []string{"", ".", "..", "../outside.json", filepath.Join(string(filepath.Separator), "tmp", "outside.json")} {
		t.Run(filename, func(t *testing.T) {
			_, err := ConfigPath(filename)
			assert.Error(t, err)
		})
	}
}

func TestAtomicWriteFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "test.json")
	data := []byte(`{"ok":true}`)

	require.NoError(t, AtomicWriteFile(path, data, 0o644))

	got, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.Equal(t, data, got)

	// Overwrite produces the new content
	data2 := []byte(`{"ok":false}`)
	require.NoError(t, AtomicWriteFile(path, data2, 0o644))
	got2, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.Equal(t, data2, got2)

	// No temp files left behind
	entries, err := os.ReadDir(dir)
	require.NoError(t, err)
	for _, e := range entries {
		assert.False(t, strings.Contains(e.Name(), ".tmp-"), "stray temp file: %s", e.Name())
	}
}

func TestAtomicWriteFileFailuresLeaveNoTemporaryFile(t *testing.T) {
	t.Run("missing parent", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "missing", "settings.json")
		assert.Error(t, AtomicWriteFile(path, []byte("data"), 0o600))
	})

	t.Run("destination is directory", func(t *testing.T) {
		parent := t.TempDir()
		path := filepath.Join(parent, "settings.json")
		require.NoError(t, os.Mkdir(path, 0o755))

		assert.Error(t, AtomicWriteFile(path, []byte("data"), 0o600))
		entries, err := os.ReadDir(parent)
		require.NoError(t, err)
		require.Len(t, entries, 1)
		assert.Equal(t, "settings.json", entries[0].Name())
		assert.True(t, entries[0].IsDir())
	})
}

const allowedOrigin = "http://127.0.0.1:5174"

func TestCorsMiddleware_Options_AllowedOrigin(t *testing.T) {
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	req := httptest.NewRequest(http.MethodOptions, "/", nil)
	req.Header.Set("Origin", allowedOrigin)
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusNoContent, rr.Code)
	assert.Equal(t, allowedOrigin, rr.Header().Get("Access-Control-Allow-Origin"))
}

func TestCorsMiddleware_AllowedOrigin_Reflected(t *testing.T) {
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	})
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Origin", allowedOrigin)
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusTeapot, rr.Code)
	assert.Equal(t, allowedOrigin, rr.Header().Get("Access-Control-Allow-Origin"))
}

// No Origin header (curl, mpv, the shell probe) is not a browser CORS
// request — it passes through with no ACAO header and the handler runs.
func TestCorsMiddleware_NoOrigin_PassThrough(t *testing.T) {
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	})
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusTeapot, rr.Code)
	assert.Empty(t, rr.Header().Get("Access-Control-Allow-Origin"))
}

// A disallowed origin gets no ACAO header on reads (browser blocks the read)
// but the handler still runs (GET is not state-changing).
func TestCorsMiddleware_DisallowedOrigin_GET(t *testing.T) {
	ran := false
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		ran = true
		w.WriteHeader(http.StatusTeapot)
	})
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Origin", "https://evil.example")
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.True(t, ran)
	assert.Empty(t, rr.Header().Get("Access-Control-Allow-Origin"))
}

// A disallowed origin is refused outright on state-changing methods, so a
// hostile page can't fire blind CSRF-style writes at the local API.
func TestCorsMiddleware_DisallowedOrigin_POST_Forbidden(t *testing.T) {
	ran := false
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		ran = true
	})
	req := httptest.NewRequest(http.MethodPost, "/", nil)
	req.Header.Set("Origin", "https://evil.example")
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusForbidden, rr.Code)
	assert.False(t, ran, "handler must not run for a forbidden cross-origin write")
}

// A same-origin write is allowed regardless of the allowlist: the embedded
// web UI (Android) is served by the backend itself, so its Origin matches
// the request Host. A hostile page on another origin can never match Host.
func TestCorsMiddleware_SameOrigin_POST_Allowed(t *testing.T) {
	ran := false
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		ran = true
		w.WriteHeader(http.StatusTeapot)
	})
	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:6969/auth/login", nil)
	req.Header.Set("Origin", "http://127.0.0.1:6969")
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusTeapot, rr.Code)
	assert.True(t, ran, "handler must run for a same-origin write")
}

func TestCorsMiddleware_AllowedHeadersAndBodyLimit(t *testing.T) {
	handler := CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		_, err := io.ReadAll(r.Body)
		if err != nil {
			var tooLarge *http.MaxBytesError
			if errors.As(err, &tooLarge) {
				http.Error(w, "too large", http.StatusRequestEntityTooLarge)
				return
			}
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	})

	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(strings.Repeat("x", maxBodyBytes+1)))
	req.Header.Set("Origin", allowedOrigin)
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusRequestEntityTooLarge, rr.Code)
	assert.Equal(t, "Origin", rr.Header().Get("Vary"))
	assert.Equal(t, allowedOrigin, rr.Header().Get("Access-Control-Allow-Origin"))
	assert.Contains(t, rr.Header().Get("Access-Control-Allow-Methods"), http.MethodDelete)
	assert.Contains(t, rr.Header().Get("Access-Control-Allow-Headers"), "Authorization")
	assert.Contains(t, rr.Header().Get("Access-Control-Expose-Headers"), "Content-Range")
}

func TestCorsMiddleware_DisallowedOriginOptionsForbidden(t *testing.T) {
	ran := false
	handler := CorsMiddleware(func(http.ResponseWriter, *http.Request) {
		ran = true
	})
	req := httptest.NewRequest(http.MethodOptions, "/", nil)
	req.Header.Set("Origin", "https://evil.example")
	rr := httptest.NewRecorder()
	handler(rr, req)

	assert.Equal(t, http.StatusForbidden, rr.Code)
	assert.False(t, ran)
}
