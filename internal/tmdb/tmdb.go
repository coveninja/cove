package tmdb

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	neturl "net/url"
	"sort"
	"strconv"
	"strings"
	"unicode"

	"golang.org/x/text/unicode/norm"
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
	ClipURLs   string   `json:"clip_urls"`
	Images     []string `json:"images"`
	Popularity float64  `json:"popularity"`
}

type MediaDetails struct {
	ImdbID string `json:"imdb_id"`
}

type TVExternalIds struct {
	ImdbID string `json:"imdb_id"`
}

// TVSeason is a season summary returned by /tv/{id}.
type TVSeason struct {
	SeasonNumber int    `json:"season_number"`
	EpisodeCount int    `json:"episode_count"`
	Name         string `json:"name"`
	PosterPath   string `json:"poster_path"`
}

// TVEpisode is a single episode returned by /tv/{id}/season/{n}.
type TVEpisode struct {
	EpisodeNumber int    `json:"episode_number"`
	Name          string `json:"name"`
	Overview      string `json:"overview"`
	StillPath     string `json:"still_path"`
	AirDate       string `json:"air_date"`
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
	OriginCountry    []string   `json:"origin_country"`
	NumberOfSeasons  int        `json:"number_of_seasons"`
	NumberOfEpisodes int        `json:"number_of_episodes"`
	Seasons          []TVSeason `json:"seasons"`
}

type searchResponse struct {
	Results []Media `json:"results"`
}

type Keyword struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

type scoredMedia struct {
	media Media
	score float64
}

const baseURL = "https://api.themoviedb.org/3"
const imageBase = "https://image.tmdb.org/t/p/w500"
const stillBase = "https://image.tmdb.org/t/p/w300"

func SearchByKeywords(query string, apiKey string) ([]Media, error) {
	normalized := normalizeQuery(query)
	kwURL := fmt.Sprintf("%s/search/keyword?api_key=%s&query=%s", baseURL, apiKey, normalized)
	res, err := http.Get(kwURL)
	if err != nil {
		return nil, err
	}
	defer func(Body io.ReadCloser) {
		err := Body.Close()
		if err != nil {
			log.Println(err)
		}
	}(res.Body)

	var kwData struct {
		Results []struct {
			ID int `json:"id"`
		} `json:"results"`
	}
	if err := json.NewDecoder(res.Body).Decode(&kwData); err != nil {
		return nil, err
	}
	if len(kwData.Results) == 0 {
		return nil, nil
	}

	ids := make([]string, 0, 3)
	for i := 0; i < len(kwData.Results) && i < 3; i++ {
		ids = append(ids, strconv.Itoa(kwData.Results[i].ID))
	}
	kwParam := strings.Join(ids, "|")

	var results []Media
	for _, mediaType := range []string{"movie", "tv"} {
		discURL := fmt.Sprintf("%s/discover/%s?api_key=%s&with_keywords=%s&sort_by=popularity.desc",
			baseURL, mediaType, apiKey, kwParam)
		r, err := http.Get(discURL)
		if err != nil {
			continue
		}
		var data searchResponse
		err = json.NewDecoder(r.Body).Decode(&data)
		if err != nil {
			log.Println(err)
			return nil, err
		}
		err = r.Body.Close()
		if err != nil {
			log.Println(err)
			return nil, err
		}

		for i := range data.Results {
			data.Results[i].PosterURL = imageBase + data.Results[i].PosterURL
			data.Results[i].MediaType = mediaType
		}
		for _, m := range data.Results {
			if m.PosterURL != imageBase {
				results = append(results, m)
			}
		}
	}
	return results, nil
}

func normalizeQuery(q string) string {
	q = norm.NFC.String(q)
	q = strings.Map(func(r rune) rune {
		if r == '-' || r == '.' || r == '·' || r == '_' {
			return ' '
		}
		return r
	}, q)
	q = strings.Join(strings.FieldsFunc(q, unicode.IsSpace), " ")
	return strings.TrimSpace(q)
}

func queryVariants(q string) []string {
	seen := map[string]bool{q: true}
	variants := []string{q}

	normalized := normalizeQuery(q)
	if !seen[normalized] {
		seen[normalized] = true
		variants = append(variants, normalized)
	}

	var b strings.Builder
	for _, r := range strings.ToLower(q) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	stripped := b.String()
	if !seen[stripped] {
		seen[stripped] = true
		variants = append(variants, stripped)
	}

	return variants
}

func Search(query string, apiKey string) ([]Media, error) {
	variantBoost := []float64{3.0, 1.5, 1.0}

	seen := make(map[int]bool)
	var scored []scoredMedia

	for vi, variant := range queryVariants(query) {
		boost := variantBoost[vi]
		encoded := neturl.QueryEscape(variant)

		for _, mediaType := range []string{"movie", "tv"} {
			url := fmt.Sprintf("%s/search/%s?api_key=%s&query=%s", baseURL, mediaType, apiKey, encoded)
			res, err := http.Get(url)
			if err != nil {
				continue
			}
			var data searchResponse
			err = json.NewDecoder(res.Body).Decode(&data)
			if err != nil {
				log.Println(err)
				return nil, err
			}
			err = res.Body.Close()
			if err != nil {
				log.Println(err)
				return nil, err
			}

			for i := range data.Results {
				data.Results[i].PosterURL = imageBase + data.Results[i].PosterURL
				data.Results[i].MediaType = mediaType
			}
			for _, m := range data.Results {
				if m.PosterURL == imageBase || seen[m.ID] {
					continue
				}
				seen[m.ID] = true
				scored = append(scored, scoredMedia{
					media: m,
					score: m.Popularity * boost,
				})
			}
		}
	}

	sort.Slice(scored, func(i, j int) bool {
		return scored[i].score > scored[j].score
	})

	merged := make([]Media, len(scored))
	for i, s := range scored {
		merged[i] = s.media
	}
	return merged, nil
}

// GetIMDBId returns the IMDB ID for a movie by TMDB ID.
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

// GetTVIMDBId returns the IMDB ID for a TV show by TMDB ID.
func GetTVIMDBId(tmdbID int, apiKey string) (string, error) {
	url := fmt.Sprintf("%s/tv/%d/external_ids?api_key=%s", baseURL, tmdbID, apiKey)
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

	var ext TVExternalIds
	if err := json.NewDecoder(res.Body).Decode(&ext); err != nil {
		return "", err
	}
	return ext.ImdbID, nil
}

// GetSeasons returns the season list for a TV show (skipping specials season 0).
func GetSeasons(tmdbID int, apiKey string) ([]TVSeason, error) {
	url := fmt.Sprintf("%s/tv/%d?api_key=%s", baseURL, tmdbID, apiKey)
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
		Seasons []TVSeason `json:"seasons"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}

	// Filter out season 0 (specials) unless it's the only one
	var filtered []TVSeason
	for _, s := range data.Seasons {
		if s.SeasonNumber > 0 {
			if s.PosterPath != "" {
				s.PosterPath = imageBase + s.PosterPath
			}
			filtered = append(filtered, s)
		}
	}
	return filtered, nil
}

// GetEpisodes returns the episodes for a specific season of a TV show.
func GetEpisodes(tmdbID int, seasonNumber int, apiKey string) ([]TVEpisode, error) {
	url := fmt.Sprintf("%s/tv/%d/season/%d?api_key=%s", baseURL, tmdbID, seasonNumber, apiKey)
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
		Episodes []TVEpisode `json:"episodes"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}

	for i := range data.Episodes {
		if data.Episodes[i].StillPath != "" {
			data.Episodes[i].StillPath = stillBase + data.Episodes[i].StillPath
		}
	}
	return data.Episodes, nil
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

func GetClips(tmdbID int, mediaType string, apiKey string) ([]string, error) {
	url := fmt.Sprintf("%s/%s/%d/videos?api_key=%s", baseURL, mediaType, tmdbID, apiKey)
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
		Results []struct {
			Key  string `json:"key"`
			Type string `json:"type"`
			Site string `json:"site"`
		} `json:"results"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}
	var featurettes []string
	for _, v := range data.Results {
		if v.Type == "Clip" && v.Site == "YouTube" {
			featurettes = append(featurettes, fmt.Sprintf("https://www.youtube.com/embed/%s", v.Key))
		}
	}
	return featurettes, nil
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
		}
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
	url := fmt.Sprintf("%s/%s/%d?api_key=%s&append_to_response=credits,release_dates,content_ratings,keywords,origin_country",
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
	names := make([]string, 0, min(6, len(keywords)))
	for i, k := range keywords {
		if i >= 6 {
			break
		}
		names = append(names, k.Name)
	}
	return names
}

func GetSimilar(tmdbID int, mediaType string, apiKey string) ([]Media, error) {
	url := fmt.Sprintf("%s/%s/%d/recommendations?api_key=%s", baseURL, mediaType, tmdbID, apiKey)
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

	var filtered []Media
	for _, m := range data.Results {
		if m.PosterURL == imageBase {
			continue
		}
		filtered = append(filtered, m)
	}
	if len(filtered) > 12 {
		filtered = filtered[:12]
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

func SuggestKeywords(query string, apiKey string) ([]Keyword, error) {
	normalized := normalizeQuery(query)
	url := fmt.Sprintf("%s/search/keyword?api_key=%s&query=%s", baseURL, apiKey, normalized)
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
		Results []Keyword `json:"results"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}
	if len(data.Results) > 10 {
		data.Results = data.Results[:10]
	}
	return data.Results, nil
}
