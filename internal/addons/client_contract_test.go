package addons

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestAddonClientFetchContracts(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.NotEmpty(t, r.Header.Get("User-Agent"))
		assert.Equal(t, "application/json", r.Header.Get("Accept"))
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/manifest.json":
			fmt.Fprint(w, `{
				"id":"contract.addon","name":"Contract","version":"1.0.0",
				"resources":["stream",{"name":"subtitles","types":["movie"],"idPrefixes":["tt"]}],
				"types":["movie"]
			}`)
		case "/stream/movie/tt1.json":
			fmt.Fprint(w, `{"streams":[{"name":"Stream","url":"https://video.example/one"}]}`)
		case "/subtitles/movie/tt1.json":
			fmt.Fprint(w, `{"subtitles":[{"id":"one","url":"https://subs.example/one.vtt","lang":"en"}]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	manager := newTestManager(nil)
	manifest, err := manager.FetchManifest(context.Background(), srv.URL)
	require.NoError(t, err)
	assert.Equal(t, "contract.addon", manifest.ID)
	require.Len(t, manifest.Resources, 2)
	assert.Equal(t, "subtitles", manifest.Resources[1].Name)
	assert.Equal(t, []string{"tt"}, manifest.Resources[1].IDPrefixes)

	streams, err := manager.FetchStreams(context.Background(), srv.URL, "movie", "tt1")
	require.NoError(t, err)
	require.Len(t, streams, 1)
	assert.Equal(t, "Stream", streams[0].Name)

	subtitles, err := manager.FetchSubtitles(context.Background(), srv.URL, "movie", "tt1")
	require.NoError(t, err)
	require.Len(t, subtitles, 1)
	assert.Equal(t, "en", subtitles[0].Lang)
}

func TestAddonClientRejectsInvalidResponses(t *testing.T) {
	tests := []struct {
		name    string
		handler http.HandlerFunc
		call    func(*Manager, string) error
	}{
		{
			name: "manifest status",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				http.Error(w, "down", http.StatusBadGateway)
			},
			call: func(m *Manager, base string) error {
				_, err := m.FetchManifest(context.Background(), base)
				return err
			},
		},
		{
			name: "manifest malformed",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				fmt.Fprint(w, `{`)
			},
			call: func(m *Manager, base string) error {
				_, err := m.FetchManifest(context.Background(), base)
				return err
			},
		},
		{
			name: "manifest missing id",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				fmt.Fprint(w, `{"name":"Missing ID"}`)
			},
			call: func(m *Manager, base string) error {
				_, err := m.FetchManifest(context.Background(), base)
				return err
			},
		},
		{
			name: "streams malformed",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				fmt.Fprint(w, `{"streams":`)
			},
			call: func(m *Manager, base string) error {
				_, err := m.FetchStreams(context.Background(), base, "movie", "tt1")
				return err
			},
		},
		{
			name: "subtitles status",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				http.Error(w, "down", http.StatusServiceUnavailable)
			},
			call: func(m *Manager, base string) error {
				_, err := m.FetchSubtitles(context.Background(), base, "movie", "tt1")
				return err
			},
		},
		{
			name: "subtitles malformed",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				fmt.Fprint(w, `{"subtitles":`)
			},
			call: func(m *Manager, base string) error {
				_, err := m.FetchSubtitles(context.Background(), base, "movie", "tt1")
				return err
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			srv := httptest.NewServer(test.handler)
			defer srv.Close()
			err := test.call(newTestManager(nil), srv.URL)
			require.Error(t, err)
		})
	}
}

func TestAddonRequestRejectsMalformedURL(t *testing.T) {
	_, err := newTestManager(nil).addonRequest(context.Background(), "://bad-url")
	require.Error(t, err)
}
