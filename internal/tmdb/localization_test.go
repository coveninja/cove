package tmdb

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return fn(req)
}

func jsonResponse(req *http.Request, body string) *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(body)),
		Request:    req,
	}
}

func TestLocalizedTransportAddsTurkishLanguageAndImageFallbacks(t *testing.T) {
	var gotLanguage, gotImageLanguage string
	transport := &localizedTransport{
		locale: func() string { return "tr" },
		base: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			gotLanguage = req.URL.Query().Get("language")
			gotImageLanguage = req.URL.Query().Get("include_image_language")
			return jsonResponse(req, `{"posters":[{"file_path":"/poster.jpg"}]}`), nil
		}),
	}

	req, err := http.NewRequest(http.MethodGet, "https://api.themoviedb.org/3/movie/1/images", nil)
	if err != nil {
		t.Fatal(err)
	}
	res, err := transport.RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()

	if gotLanguage != "tr-TR" {
		t.Fatalf("language = %q, want tr-TR", gotLanguage)
	}
	if gotImageLanguage != "tr,en,null" {
		t.Fatalf("include_image_language = %q, want tr,en,null", gotImageLanguage)
	}
}

func TestLocalizedTransportFillsMissingTurkishMetadataFromEnglish(t *testing.T) {
	var mu sync.Mutex
	var languages []string
	transport := &localizedTransport{
		locale: func() string { return "tr" },
		base: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			language := req.URL.Query().Get("language")
			mu.Lock()
			languages = append(languages, language)
			mu.Unlock()
			if language == "tr-TR" {
				return jsonResponse(req, `{
					"id": 7,
					"title": "Türkçe Başlık",
					"overview": "",
					"tagline": "",
					"credits": {"cast": [{"id": 2, "name": ""}]}
				}`), nil
			}
			return jsonResponse(req, `{
				"id": 7,
				"title": "English Title",
				"overview": "English overview",
				"tagline": "English tagline",
				"credits": {"cast": [{"id": 2, "name": "Actor Name"}]}
			}`), nil
		}),
	}

	req, err := http.NewRequest(http.MethodGet, "https://api.themoviedb.org/3/movie/7", nil)
	if err != nil {
		t.Fatal(err)
	}
	res, err := transport.RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()

	var got struct {
		Title    string `json:"title"`
		Overview string `json:"overview"`
		Tagline  string `json:"tagline"`
		Credits  struct {
			Cast []struct {
				Name string `json:"name"`
			} `json:"cast"`
		} `json:"credits"`
	}
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got.Title != "Türkçe Başlık" {
		t.Fatalf("localized title was replaced: %q", got.Title)
	}
	if got.Overview != "English overview" || got.Tagline != "English tagline" {
		t.Fatalf("missing English fallback: %#v", got)
	}
	if len(got.Credits.Cast) != 1 || got.Credits.Cast[0].Name != "Actor Name" {
		t.Fatalf("nested fallback was not merged by id: %#v", got.Credits.Cast)
	}
	if len(languages) != 2 || languages[0] != "tr-TR" || languages[1] != "en-US" {
		t.Fatalf("request languages = %#v, want [tr-TR en-US]", languages)
	}
}

func TestLocalizedTransportFallsBackForEmptyTurkishVideos(t *testing.T) {
	transport := &localizedTransport{
		locale: func() string { return "tr" },
		base: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			if req.URL.Query().Get("language") == "tr-TR" {
				return jsonResponse(req, `{"id":7,"results":[]}`), nil
			}
			return jsonResponse(req, `{"id":7,"results":[{"id":"trailer","name":"Official Trailer"}]}`), nil
		}),
	}

	req, err := http.NewRequest(http.MethodGet, "https://api.themoviedb.org/3/movie/7/videos", nil)
	if err != nil {
		t.Fatal(err)
	}
	res, err := transport.RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()

	var got struct {
		Results []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"results"`
	}
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if len(got.Results) != 1 || got.Results[0].ID != "trailer" {
		t.Fatalf("video fallback = %#v", got.Results)
	}
}

func TestClientLocaleNormalizesUnsupportedValues(t *testing.T) {
	active := "TR"
	client := New("", WithLocaleSource(func() string { return active }))
	if got := client.Locale(); got != "tr" {
		t.Fatalf("Locale() = %q, want tr", got)
	}
	active = "de"
	if got := client.Locale(); got != "en" {
		t.Fatalf("unsupported Locale() = %q, want en", got)
	}
}

func TestDetailsCacheIsPartitionedByLocale(t *testing.T) {
	active := "tr"
	var mu sync.Mutex
	var languages []string
	client := New("key", WithLocaleSource(func() string { return active }))
	client.client.Transport.(*localizedTransport).base = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		language := req.URL.Query().Get("language")
		mu.Lock()
		languages = append(languages, language)
		mu.Unlock()
		if language == "tr-TR" {
			return jsonResponse(req, `{"id":5,"title":"Türkçe","overview":"Yerel açıklama"}`), nil
		}
		return jsonResponse(req, `{"id":5,"title":"English","overview":"English overview"}`), nil
	})

	turkish, err := client.GetDetails(5, "movie")
	if err != nil {
		t.Fatal(err)
	}
	active = "en"
	english, err := client.GetDetails(5, "movie")
	if err != nil {
		t.Fatal(err)
	}
	active = "tr"
	turkishAgain, err := client.GetDetails(5, "movie")
	if err != nil {
		t.Fatal(err)
	}

	if turkish.Overview != "Yerel açıklama" || english.Overview != "English overview" {
		t.Fatalf("localized details = %q / %q", turkish.Overview, english.Overview)
	}
	if turkishAgain != turkish {
		t.Fatal("returning to Turkish did not reuse the Turkish cache entry")
	}
	if len(languages) != 2 || languages[0] != "tr-TR" || languages[1] != "en-US" {
		t.Fatalf("request languages = %#v, want one request per locale", languages)
	}
}

func TestEpisodesCacheIsPartitionedByLocale(t *testing.T) {
	active := "tr"
	var hits int
	client := New("key", WithLocaleSource(func() string { return active }))
	client.client.Transport.(*localizedTransport).base = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits++
		name := "Episode"
		if req.URL.Query().Get("language") == "tr-TR" {
			name = "Bölüm"
		}
		return jsonResponse(req, `{"episodes":[{"episode_number":1,"name":"`+name+`"}]}`), nil
	})

	turkish, err := client.GetEpisodesCached(9, 1)
	if err != nil {
		t.Fatal(err)
	}
	active = "en"
	english, err := client.GetEpisodesCached(9, 1)
	if err != nil {
		t.Fatal(err)
	}
	active = "tr"
	if _, err := client.GetEpisodesCached(9, 1); err != nil {
		t.Fatal(err)
	}

	if turkish[0].Name != "Bölüm" || english[0].Name != "Episode" {
		t.Fatalf("localized episodes = %q / %q", turkish[0].Name, english[0].Name)
	}
	if hits != 2 {
		t.Fatalf("hits = %d, want one request per locale", hits)
	}
}
