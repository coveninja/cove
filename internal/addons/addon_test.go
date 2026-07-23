package addons

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNormalizeAddonURL(t *testing.T) {
	tests := []struct {
		input string
		want  string
	}{
		{"https://addon.example/manifest.json", "https://addon.example"},
		{"https://addon.example/", "https://addon.example"},
		{"https://addon.example", "https://addon.example"},
		{"https://example.com/some/path/manifest.json", "https://example.com/some/path"},
		{"https://example.com/some/path/", "https://example.com/some/path"},
		{"https://example.com/some/path", "https://example.com/some/path"},
		{"  https://example.com/manifest.json  ", "https://example.com"},
	}
	for _, tc := range tests {
		t.Run(tc.input, func(t *testing.T) {
			assert.Equal(t, tc.want, normalizeAddonURL(tc.input))
		})
	}
}

func TestManifestResourceUnmarshal(t *testing.T) {
	var stringResource ManifestResource
	require.NoError(t, json.Unmarshal([]byte(`"stream"`), &stringResource))
	assert.Equal(t, "stream", stringResource.Name)

	var objectResource ManifestResource
	require.NoError(t, json.Unmarshal([]byte(`{"name":"subtitles","types":["movie"],"idPrefixes":["tt"]}`), &objectResource))
	assert.Equal(t, "subtitles", objectResource.Name)
	assert.Equal(t, []string{"movie"}, objectResource.Types)
	assert.Equal(t, []string{"tt"}, objectResource.IDPrefixes)

	var malformed ManifestResource
	assert.Error(t, json.Unmarshal([]byte(`42`), &malformed))
}

func TestDetectKind(t *testing.T) {
	assert.Equal(t, KindProvider, detectKind(Manifest{Resources: []ManifestResource{{Name: "stream"}}}))
	assert.Equal(t, KindSubtitle, detectKind(Manifest{Resources: []ManifestResource{{Name: "subtitles"}}}))
	assert.Equal(t, KindProvider, detectKind(Manifest{}))
	assert.Equal(t, KindProvider, detectKind(Manifest{Resources: []ManifestResource{
		{Name: "subtitles"},
		{Name: "stream"},
	}}), "stream capability must take precedence for mixed manifests")
}
