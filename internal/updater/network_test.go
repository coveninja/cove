package updater

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type updaterRoundTripFunc func(*http.Request) (*http.Response, error)

func (f updaterRoundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func updaterResponse(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     make(http.Header),
	}
}

func resetUpdaterGlobals(t *testing.T) {
	t.Helper()
	oldAPIClient := apiClient
	oldDownloadClient := downloadClient
	oldExecutablePath := executablePath
	oldScheduleRestart := scheduleRestart
	mu.Lock()
	oldPendingURL := pendingURL
	oldPendingChecksumURL := pendingChecksumURL
	pendingURL = ""
	pendingChecksumURL = ""
	mu.Unlock()

	t.Cleanup(func() {
		apiClient = oldAPIClient
		downloadClient = oldDownloadClient
		executablePath = oldExecutablePath
		scheduleRestart = oldScheduleRestart
		mu.Lock()
		pendingURL = oldPendingURL
		pendingChecksumURL = oldPendingChecksumURL
		mu.Unlock()
	})
}

func setPendingUpdate(assetURL, checksumURL string) {
	mu.Lock()
	pendingURL = assetURL
	pendingChecksumURL = checksumURL
	mu.Unlock()
}

func TestFetchLatestHandlesGitHubResponses(t *testing.T) {
	tests := []struct {
		name       string
		status     int
		body       string
		wantNil    bool
		wantErr    string
		wantTag    string
		wantAssets int
	}{
		{name: "no releases", status: http.StatusNotFound, body: `{}`, wantNil: true},
		{name: "API error", status: http.StatusBadGateway, body: `{}`, wantErr: "GitHub API returned 502"},
		{name: "malformed JSON", status: http.StatusOK, body: `{`, wantErr: "unexpected EOF"},
		{
			name:       "latest release",
			status:     http.StatusOK,
			body:       `{"tag_name":"v2.0.0","name":"Cove 2","assets":[{"name":"cove.zip","browser_download_url":"https://download.invalid/cove.zip"}]}`,
			wantTag:    "v2.0.0",
			wantAssets: 1,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resetUpdaterGlobals(t)
			apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
				assert.Equal(t, "application/vnd.github+json", r.Header.Get("Accept"))
				assert.Equal(t, "2022-11-28", r.Header.Get("X-GitHub-Api-Version"))
				assert.Equal(t, "/repos/coveninja/cove/releases/latest", r.URL.Path)
				return updaterResponse(tt.status, tt.body), nil
			})}

			release, err := fetchLatest()
			if tt.wantErr != "" {
				require.Error(t, err)
				assert.Contains(t, err.Error(), tt.wantErr)
				return
			}
			require.NoError(t, err)
			if tt.wantNil {
				assert.Nil(t, release)
				return
			}
			require.NotNil(t, release)
			assert.Equal(t, tt.wantTag, release.TagName)
			assert.Len(t, release.Assets, tt.wantAssets)
		})
	}
}

func TestCheckForPlatformSelectsOnlyVerifiedNewerAsset(t *testing.T) {
	resetUpdaterGlobals(t)
	wantAsset := assetNameFor("windows", "amd64")
	apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return updaterResponse(http.StatusOK, `{
			"tag_name":"v1.2.0",
			"name":"Cove 1.2",
			"assets":[
				{"name":"`+wantAsset+`","browser_download_url":"https://download.invalid/archive"},
				{"name":"`+wantAsset+`.sha256","browser_download_url":"https://download.invalid/checksum"}
			]
		}`), nil
	})}

	result, err := checkForPlatform("v1.1.0", "windows", "amd64")
	require.NoError(t, err)
	assert.Equal(t, &CheckResult{
		Available:      true,
		CurrentVersion: "v1.1.0",
		LatestVersion:  "v1.2.0",
		ReleaseName:    "Cove 1.2",
	}, result)

	mu.Lock()
	assert.Equal(t, "https://download.invalid/archive", pendingURL)
	assert.Equal(t, "https://download.invalid/checksum", pendingChecksumURL)
	mu.Unlock()
}

func TestCheckForPlatformSkipsUnsupportedAndUnusableReleases(t *testing.T) {
	t.Run("unsupported platform", func(t *testing.T) {
		resetUpdaterGlobals(t)
		apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			t.Fatal("unsupported platforms must not call GitHub")
			return nil, nil
		})}
		result, err := checkForPlatform("v1.0.0", "android", "arm64")
		require.NoError(t, err)
		assert.False(t, result.Available)
	})

	t.Run("development build", func(t *testing.T) {
		resetUpdaterGlobals(t)
		apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			t.Fatal("development builds must not call GitHub")
			return nil, nil
		})}
		result, err := checkForPlatform("v1.0.0-dirty", "windows", "amd64")
		require.NoError(t, err)
		assert.False(t, result.Available)
	})

	tests := []struct {
		name   string
		tag    string
		assets func(string) string
	}{
		{name: "same version", tag: "v1.0.0", assets: func(want string) string {
			return `[{"name":"` + want + `","browser_download_url":"https://download.invalid/archive"}]`
		}},
		{name: "missing archive", tag: "v2.0.0", assets: func(want string) string {
			return `[{"name":"` + want + `.sha256","browser_download_url":"https://download.invalid/checksum"}]`
		}},
		{name: "missing checksum", tag: "v2.0.0", assets: func(want string) string {
			return `[{"name":"` + want + `","browser_download_url":"https://download.invalid/archive"}]`
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resetUpdaterGlobals(t)
			wantAsset := assetNameFor("windows", "amd64")
			apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
				return updaterResponse(http.StatusOK, `{
					"tag_name":"`+tt.tag+`",
					"name":"Release",
					"assets":`+tt.assets(wantAsset)+`
				}`), nil
			})}

			result, err := checkForPlatform("v1.0.0", "windows", "amd64")
			require.NoError(t, err)
			assert.False(t, result.Available)
			mu.Lock()
			assert.Empty(t, pendingURL)
			assert.Empty(t, pendingChecksumURL)
			mu.Unlock()
		})
	}
}

func TestFetchChecksumValidatesAndNormalizesDigest(t *testing.T) {
	digest := strings.Repeat("AB", sha256.Size)
	tests := []struct {
		name    string
		status  int
		body    string
		want    string
		wantErr string
	}{
		{name: "hash and filename", status: http.StatusOK, body: digest + "  cove.zip\n", want: strings.ToLower(digest)},
		{name: "HTTP error", status: http.StatusServiceUnavailable, wantErr: "HTTP 503"},
		{name: "empty", status: http.StatusOK, body: "", wantErr: "malformed checksum"},
		{name: "wrong length", status: http.StatusOK, body: "abcd", wantErr: "malformed checksum"},
		{name: "not hex", status: http.StatusOK, body: strings.Repeat("z", sha256.Size*2), wantErr: "malformed checksum"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resetUpdaterGlobals(t)
			apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
				return updaterResponse(tt.status, tt.body), nil
			})}

			got, err := fetchChecksum("https://download.invalid/checksum")
			if tt.wantErr != "" {
				require.Error(t, err)
				assert.Contains(t, err.Error(), tt.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, tt.want, got)
		})
	}
}

func TestApplyUpdateRejectsMissingAndUnverifiedDownloads(t *testing.T) {
	t.Run("no pending update", func(t *testing.T) {
		resetUpdaterGlobals(t)
		err := applyUpdate()
		require.Error(t, err)
		assert.Contains(t, err.Error(), "no pending update")
	})

	t.Run("checksum download fails", func(t *testing.T) {
		resetUpdaterGlobals(t)
		setPendingUpdate("https://download.invalid/archive", "https://download.invalid/checksum")
		apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			return updaterResponse(http.StatusServiceUnavailable, ""), nil
		})}
		err := applyUpdate()
		require.Error(t, err)
		assert.Contains(t, err.Error(), "checksum")
		assert.Contains(t, err.Error(), "HTTP 503")
	})

	t.Run("archive download fails", func(t *testing.T) {
		resetUpdaterGlobals(t)
		setPendingUpdate("https://download.invalid/archive", "https://download.invalid/checksum")
		apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			return updaterResponse(http.StatusOK, strings.Repeat("0", sha256.Size*2)), nil
		})}
		downloadClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			return updaterResponse(http.StatusBadGateway, ""), nil
		})}
		err := applyUpdate()
		require.Error(t, err)
		assert.Contains(t, err.Error(), "download: HTTP 502")
	})

	t.Run("checksum mismatch", func(t *testing.T) {
		resetUpdaterGlobals(t)
		setPendingUpdate("https://download.invalid/archive", "https://download.invalid/checksum")
		apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			return updaterResponse(http.StatusOK, strings.Repeat("0", sha256.Size*2)), nil
		})}
		downloadClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
			return updaterResponse(http.StatusOK, "tampered archive"), nil
		})}
		err := applyUpdate()
		require.Error(t, err)
		assert.Contains(t, err.Error(), "checksum mismatch")
	})
}

func TestApplyUpdateVerifiesExtractsAndSchedulesRestart(t *testing.T) {
	resetUpdaterGlobals(t)
	dest := t.TempDir()
	execName := "cove"
	var archive []byte
	expectedPath := filepath.Join(dest, execName)
	if runtime.GOOS == "windows" {
		execName = "cove.exe"
		zipPath := writeZip(t, []archiveEntry{
			{name: execName, body: "new binary", mode: 0o755},
			{name: "web/index.html", body: "new ui", mode: 0o644},
		})
		var err error
		archive, err = os.ReadFile(zipPath)
		require.NoError(t, err)
		expectedPath = filepath.Join(dest, execName+".new")
	} else {
		archive = tarGz(t, []archiveEntry{
			{name: execName, body: "new binary", mode: 0o755},
			{name: "web/index.html", body: "new ui", mode: 0o644},
		})
		expectedPath = filepath.Join(dest, execName)
	}

	sum := sha256.Sum256(archive)
	digest := hex.EncodeToString(sum[:])
	setPendingUpdate("https://download.invalid/archive", "https://download.invalid/checksum")
	apiClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return updaterResponse(http.StatusOK, digest+"  archive\n"), nil
	})}
	downloadClient = &http.Client{Transport: updaterRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return updaterResponse(http.StatusOK, string(archive)), nil
	})}
	executablePath = func() (string, error) {
		return filepath.Join(dest, execName), nil
	}
	var restartScheduled atomic.Bool
	scheduleRestart = func() {
		restartScheduled.Store(true)
	}

	require.NoError(t, applyUpdate())
	binary, err := os.ReadFile(expectedPath)
	require.NoError(t, err)
	assert.Equal(t, "new binary", string(binary))
	web, err := os.ReadFile(filepath.Join(dest, "web", "index.html"))
	require.NoError(t, err)
	assert.Equal(t, "new ui", string(web))
	assert.True(t, restartScheduled.Load())
}

func TestUpdateHandlersEnforceMethodsAndReportState(t *testing.T) {
	resetUpdaterGlobals(t)
	mux := http.NewServeMux()
	SetupHandlers(mux, "dev")

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/update/check", nil))
	require.Equal(t, http.StatusOK, rec.Code)
	var result CheckResult
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &result))
	assert.Equal(t, "dev", result.CurrentVersion)
	assert.False(t, result.Available)

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/update/check", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/update/apply", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/update/apply", nil))
	assert.Equal(t, http.StatusInternalServerError, rec.Code)
	assert.Contains(t, rec.Body.String(), "no pending update")
}
