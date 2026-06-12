package tmdb

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
)

type Media struct {
	ID         int      `json:"id"`
	Title      string   `json:"title"`
	Name       string   `json:"name"`
	Overview   string   `json:"overview"`
	Released   string   `json:"release_date"`
	FirstAir   string   `json:"first_air_date"`
	PosterURL  string   `json:"poster_path"`
	Rating     float64  `json:"vote_average"`
	MediaType  string   `json:"media_type"`
	TrailerURL string   `json:"trailer_url"`
	Images     []string `json:"images"`
}

type MediaDetails struct {
	ImdbID string `json:"imdb_id"`
}

type Details struct {
	Genres []struct {
		Name string `json:"name"`
	} `json:"genres"`
	Runtime        int   `json:"runtime"`
	EpisodeRunTime []int `json:"episode_run_time"`
	Credits        struct {
		Cast []struct {
			Name string `json:"name"`
		} `json:"cast"`
	} `json:"credits"`
	ReleaseDates struct {
		Results []struct {
			ISO31661     string `json:"iso_3166_1"`
			ReleaseDates []struct {
				Certification string `json:"certification"`
			} `json:"release_dates"`
		} `json:"results"`
	} `json:"release_dates"`
	ContentRatings struct {
		Results []struct {
			ISO31661 string `json:"iso_3166_1"`
			Rating   string `json:"rating"`
		} `json:"results"`
	} `json:"content_ratings"`
	Keywords struct {
		Keywords []struct {
			Name string `json:"name"`
		} `json:"keywords"` // movies
		Results []struct {
			Name string `json:"name"`
		} `json:"results"` // tv shows
	} `json:"keywords"`
}

type searchResponse struct {
	Results []Media `json:"results"`
}

const baseURL = "https://api.themoviedb.org/3"
const imageBase = "https://image.tmdb.org/t/p/w500"

func Search(query string, apiKey string) ([]Media, error) {
	url := fmt.Sprintf("%s/search/multi?api_key=%s&query=%s", baseURL, apiKey, query)
	res, err := http.Get(url)
	if err != nil {
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			fmt.Println(err)
		}
	}(res.Body)

	var data searchResponse
	err = json.NewDecoder(res.Body).Decode(&data)
	if err != nil {
		fmt.Println(err)
		return nil, err
	}

	// prepend full image URL
	for i := range data.Results {
		data.Results[i].PosterURL = imageBase + data.Results[i].PosterURL
	}

	// filter out people and results without posters
	var filtered []Media
	for _, m := range data.Results {
		if m.MediaType == "person" || m.PosterURL == imageBase {
			continue
		}
		filtered = append(filtered, m)
	}
	return filtered, nil
}

func GetIMDBId(tmdbID int, apiKey string) (string, error) {
	url := fmt.Sprintf("%s/movie/%d?api_key=%s", baseURL, tmdbID, apiKey)
	res, err := http.Get(url)
	if err != nil {
		return "", err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			fmt.Println(err)
		}
	}(res.Body)

	var details MediaDetails
	err = json.NewDecoder(res.Body).Decode(&details)
	if err != nil {
		fmt.Println(err)
		return "", err
	}
	return details.ImdbID, nil
}

func GetTrailer(tmdbID int, mediaType string, apiKey string) (string, error) {
	url := fmt.Sprintf("%s/%s/%d/videos?api_key=%s", baseURL, mediaType, tmdbID, apiKey)
	res, err := http.Get(url)
	if err != nil {
		return "", err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			log.Println(err)
		}
	}(res.Body)

	var data struct {
		Results []struct {
			Key  string `json:"key"`
			Type string `json:"type"`
			Site string `json:"site"`
		} `json:"results"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return "", err
	}
	for _, v := range data.Results {
		if v.Type == "Trailer" && v.Site == "YouTube" {
			return fmt.Sprintf("https://www.youtube.com/embed/%s", v.Key), nil
		}
	}
	return "", nil
}

func GetImages(tmdbID int, mediaType string, apiKey string) ([]string, error) {
	url := fmt.Sprintf("%s/%s/%d/images?api_key=%s", baseURL, mediaType, tmdbID, apiKey)
	res, err := http.Get(url)
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
		Backdrops []struct {
			FilePath string `json:"file_path"`
		} `json:"backdrops"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}

	var urls []string
	for i, b := range data.Backdrops {
		if i >= 5 {
			break
		} // limit to 5
		urls = append(urls, imageBase+b.FilePath)
	}
	return urls, nil
}

func (m *Media) DisplayTitle() string {
	if m.Title != "" {
		return m.Title
	}
	return m.Name
}

func (m *Media) DisplayDate() string {
	if m.Released != "" {
		return m.Released
	}
	return m.FirstAir
}

func GetDetails(tmdbID int, mediaType string, apiKey string) (*Details, error) {
	url := fmt.Sprintf("%s/%s/%d?api_key=%s&append_to_response=credits,release_dates,content_ratings,keywords",
		baseURL, mediaType, tmdbID, apiKey)
	res, err := http.Get(url)
	if err != nil {
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			log.Println(err)
		}
	}(res.Body)

	var details Details
	if err := json.NewDecoder(res.Body).Decode(&details); err != nil {
		return nil, err
	}
	return &details, nil
}

func (d *Details) AgeRating() string {
	for _, r := range d.ReleaseDates.Results {
		if r.ISO31661 == "US" {
			for _, rd := range r.ReleaseDates {
				if rd.Certification != "" {
					return rd.Certification
				}
			}
		}
	}
	for _, r := range d.ContentRatings.Results {
		if r.ISO31661 == "US" && r.Rating != "" {
			return r.Rating
		}
	}
	return ""
}

func (d *Details) DisplayRuntime() string {
	if d.Runtime > 0 {
		return fmt.Sprintf("%dh %dm", d.Runtime/60, d.Runtime%60)
	}
	if len(d.EpisodeRunTime) > 0 {
		return fmt.Sprintf("%dm / ep", d.EpisodeRunTime[0])
	}
	return ""
}

func (d *Details) KeywordNames() []string {
	keywords := d.Keywords.Keywords
	if len(keywords) == 0 {
		keywords = d.Keywords.Results
	}
	names := make([]string, 0, min(4, len(keywords)))
	for i, k := range keywords {
		if i >= 4 {
			break
		}
		names = append(names, k.Name)
	}
	return names
}

func GetSimilar(tmdbID int, mediaType string, apiKey string) ([]Media, error) {
	url := fmt.Sprintf("%s/%s/%d/similar?api_key=%s", baseURL, mediaType, tmdbID, apiKey)
	res, err := http.Get(url)
	if err != nil {
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			log.Println(err)
		}
	}(res.Body)

	var data searchResponse
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}

	for i := range data.Results {
		data.Results[i].PosterURL = imageBase + data.Results[i].PosterURL
		data.Results[i].MediaType = mediaType
	}

	// filter out results without posters
	var filtered []Media
	for _, m := range data.Results {
		if m.PosterURL == imageBase {
			continue
		}
		filtered = append(filtered, m)
	}
	if len(filtered) > 6 {
		filtered = filtered[:6]
	}
	return filtered, nil
}

func GetLogos(tmdbID int, mediaType string, apiKey string) ([]string, error) {
	url := fmt.Sprintf("%s/%s/%d/images?api_key=%s&include_image_language=en,null", baseURL, mediaType, tmdbID, apiKey)
	res, err := http.Get(url)
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
		Logos []struct {
			FilePath string  `json:"file_path"`
			VoteAvg  float64 `json:"vote_average"`
		} `json:"logos"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}

	var urls []string
	for i, l := range data.Logos {
		if i >= 3 {
			break
		}
		urls = append(urls, "https://image.tmdb.org/t/p/w500"+l.FilePath)
	}
	return urls, nil
}
