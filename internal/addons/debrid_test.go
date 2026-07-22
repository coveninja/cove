package addons

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestClassifyStream(t *testing.T) {
	tests := []struct {
		name       string
		stream     Stream
		addonName  string
		addonURL   string
		wantDebrid string
		wantCached bool
	}{
		{
			name:       "[RD+] in name → cached RealDebrid",
			stream:     Stream{Name: "[RD+] Some.Movie.mkv"},
			wantDebrid: "RealDebrid",
			wantCached: true,
		},
		{
			name:       "[RD] bare bracket → uncached RealDebrid",
			stream:     Stream{Name: "[RD] Some.Movie.mkv"},
			wantDebrid: "RealDebrid",
			wantCached: false,
		},
		{
			name:       "[AD+] cached AllDebrid",
			stream:     Stream{Name: "[AD+] Some.Movie.mkv"},
			wantDebrid: "AllDebrid",
			wantCached: true,
		},
		{
			name:       "[PM+] cached Premiumize",
			stream:     Stream{Name: "[PM+] Some.Movie.mkv"},
			wantDebrid: "Premiumize",
			wantCached: true,
		},
		{
			name:       "[TB+] cached TorBox",
			stream:     Stream{Name: "[TB+] Some.Movie.mkv"},
			wantDebrid: "TorBox",
			wantCached: true,
		},
		{
			name:       "[OC+] cached Offcloud",
			stream:     Stream{Name: "[OC+] Some.Movie.mkv"},
			wantDebrid: "Offcloud",
			wantCached: true,
		},
		{
			name:       "[DL+] cached Debrid-Link",
			stream:     Stream{Name: "[DL+] Some.Movie.mkv"},
			wantDebrid: "Debrid-Link",
			wantCached: true,
		},
		{
			name:       "⚡ with torbox addon URL → cached TorBox",
			stream:     Stream{Name: "⚡ Some.Movie.mkv"},
			addonURL:   "https://torbox.app/stremio/manifest.json",
			wantDebrid: "TorBox",
			wantCached: true,
		},
		{
			name:       "⚡ alone, unknown addon → not classified",
			stream:     Stream{Name: "⚡ Some.Movie.mkv"},
			addonName:  "SomeUnknownAddon",
			addonURL:   "https://example.com/manifest.json",
			wantDebrid: "",
			wantCached: false,
		},
		{
			name:       "cached substring + addonName Comet → generic Debrid cached",
			stream:     Stream{Title: "cached stream"},
			addonName:  "Comet",
			wantDebrid: "Debrid",
			wantCached: true,
		},
		{
			name:       "bare [RD] plus ⚡ in same text → cached wins",
			stream:     Stream{Name: "[RD] ⚡ Some.Movie.mkv"},
			wantDebrid: "RealDebrid",
			wantCached: true,
		},
		{
			name:       "no markers, plain addon → not classified",
			stream:     Stream{Name: "Some.Movie.mkv", Title: "720p BluRay"},
			addonName:  "PlainAddon",
			addonURL:   "https://plainaddon.example.com/manifest.json",
			wantDebrid: "",
			wantCached: false,
		},
		{
			name: "BehaviorHints.VideoSize promotes into SizeBytes when zero",
			stream: Stream{
				Name:          "Some.Movie.mkv",
				BehaviorHints: &StreamBehaviorHints{VideoSize: 123},
				SizeBytes:     0,
			},
			wantDebrid: "",
			wantCached: false,
		},
		{
			name: "BehaviorHints.VideoSize does not overwrite existing SizeBytes",
			stream: Stream{
				Name:          "Some.Movie.mkv",
				BehaviorHints: &StreamBehaviorHints{VideoSize: 123},
				SizeBytes:     456,
			},
			wantDebrid: "",
			wantCached: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			s := tc.stream
			classifyStream(&s, tc.addonName, tc.addonURL)
			assert.Equal(t, tc.wantDebrid, s.Debrid)
			assert.Equal(t, tc.wantCached, s.Cached)
		})
	}
}

func TestClassifyStreamVideoSizePromotion(t *testing.T) {
	t.Run("VideoSize=123 with SizeBytes=0 → SizeBytes becomes 123", func(t *testing.T) {
		s := Stream{
			BehaviorHints: &StreamBehaviorHints{VideoSize: 123},
			SizeBytes:     0,
		}
		classifyStream(&s, "", "")
		assert.Equal(t, int64(123), s.SizeBytes)
	})

	t.Run("VideoSize=123 with SizeBytes=456 → SizeBytes stays 456", func(t *testing.T) {
		s := Stream{
			BehaviorHints: &StreamBehaviorHints{VideoSize: 123},
			SizeBytes:     456,
		}
		classifyStream(&s, "", "")
		assert.Equal(t, int64(456), s.SizeBytes)
	})
}
