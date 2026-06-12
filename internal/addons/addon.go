package addons

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
)

type ManifestResource struct {
	Name       string   `json:"name"`
	Types      []string `json:"types"`
	IDPrefixes []string `json:"idPrefixes"`
}

type Manifest struct {
	ID          string             `json:"id"`
	Name        string             `json:"name"`
	Description string             `json:"description"`
	Version     string             `json:"version"`
	Resources   []ManifestResource `json:"resources"`
	Types       []string           `json:"types"`
}

type Stream struct {
	Name      string `json:"name"`
	Title     string `json:"title"`
	URL       string `json:"url"`
	InfoHash  string `json:"infoHash"`
	AddonName string `json:"addonName"`
}

type Addon struct {
	URL      string
	Manifest Manifest
}

var httpClient = &http.Client{}

func addonRequest(url string) (*http.Response, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "application/json")
	return httpClient.Do(req)
}

func FetchManifest(addonURL string) (Manifest, error) {
	res, err := addonRequest(addonURL + "/manifest.json")
	if err != nil {
		return Manifest{}, err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			log.Println(err)
		}
	}(res.Body)

	var manifest Manifest
	err = json.NewDecoder(res.Body).Decode(&manifest)
	if err != nil {
		return Manifest{}, err
	}
	return manifest, nil
}

func FetchStreams(addonURL string, mediaType string, imdbID string) ([]Stream, error) {
	url := fmt.Sprintf("%s/stream/%s/%s.json", addonURL, mediaType, imdbID)

	res, err := addonRequest(url)
	if err != nil {
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			log.Println(err)
		}
	}(res.Body)

	var data struct {
		Streams []Stream `json:"streams"`
	}
	err = json.NewDecoder(res.Body).Decode(&data)
	if err != nil {
		return nil, err
	}
	return data.Streams, nil
}
