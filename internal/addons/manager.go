package addons

import "sync"

// in memory for now, will move to SQLite later
var configuredAddons []Addon
var mu sync.RWMutex

func AddAddon(url string) (Addon, error) {
	manifest, err := FetchManifest(url)
	if err != nil {
		return Addon{}, err
	}
	addon := Addon{URL: url, Manifest: manifest}
	mu.Lock()
	configuredAddons = append(configuredAddons, addon)
	mu.Unlock()
	return addon, nil
}

func GetAddons() []Addon {
	mu.RLock()
	defer mu.RUnlock()
	return configuredAddons
}

func GetAllStreams(mediaType string, imdbID string) ([]Stream, error) {
	mu.RLock()
	addons := configuredAddons
	mu.RUnlock()

	var allStreams []Stream
	for _, addon := range addons {
		streams, err := FetchStreams(addon.URL, mediaType, imdbID)
		if err != nil {
			continue // don't fail if one addon is down
		}
		// tag each stream with which addon it came from
		for i := range streams {
			streams[i].AddonName = addon.Manifest.Name
		}
		allStreams = append(allStreams, streams...)
	}
	return allStreams, nil
}
