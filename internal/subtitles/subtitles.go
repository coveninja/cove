package subtitles

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

type Subtitle struct {
	Language string `json:"language"`
	URL      string `json:"url"`
	Name     string `json:"name"`
}

const apiBase = "https://api.opensubtitles.com/api/v1"

func Search(imdbID string, apiKey string) ([]Subtitle, error) {
	query := url.Values{}
	query.Set("imdb_id", imdbID)
	query.Set("languages", "en")

	req, err := http.NewRequest("GET", apiBase+"/subtitles?"+query.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Api-Key", apiKey)
	req.Header.Set("User-Agent", "cove v1.0")

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	var data struct {
		Data []struct {
			Attributes struct {
				Language string `json:"language"`
				Files    []struct {
					FileID int    `json:"file_id"`
					Name   string `json:"file_name"`
				} `json:"files"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		return nil, err
	}

	var results []Subtitle
	for _, item := range data.Data {
		if len(item.Attributes.Files) == 0 {
			continue
		}
		results = append(results, Subtitle{
			Language: item.Attributes.Language,
			URL:      fmt.Sprintf("/api/subtitles/download?file_id=%d", item.Attributes.Files[0].FileID),
			Name:     item.Attributes.Files[0].Name,
		})
		if len(results) >= 10 {
			break
		}
	}
	return results, nil
}

func Download(fileID string, apiKey string) ([]byte, error) {
	payload := fmt.Sprintf(`{"file_id": %s}`, fileID)
	req, err := http.NewRequest("POST", apiBase+"/download", bytes.NewBufferString(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Api-Key", apiKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "cove v1.0")

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(res.Body)
		return nil, fmt.Errorf("OpenSubtitles API error (Status %d): %s", res.StatusCode, string(body))
	}

	var result struct {
		Link string `json:"link"`
	}
	if err := json.NewDecoder(res.Body).Decode(&result); err != nil {
		return nil, err
	}

	subRes, err := http.Get(result.Link)
	if err != nil {
		return nil, err
	}
	defer subRes.Body.Close()

	if subRes.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("failed to download subtitle file from link")
	}

	return io.ReadAll(subRes.Body)
}

func SrtToVtt(srt []byte) []byte {
	content := string(srt)
	content = strings.TrimPrefix(content, "\xef\xbb\xbf")
	content = strings.ReplaceAll(content, ",", ".")
	return []byte("WEBVTT\n\n" + content)
}
