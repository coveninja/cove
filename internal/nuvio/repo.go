package nuvio

import (
	"context"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// githubURLPattern accepts github.com/owner/repo, with or without a scheme,
// trailing slash, .git suffix, or an explicit /tree/<branch>.
var githubURLPattern = regexp.MustCompile(`^(?:https?://)?(?:www\.)?github\.com/([^/]+)/([^/]+?)(?:\.git)?(?:/tree/([^/]+))?/?$`)

// rawGithubUsercontentPattern accepts a direct link to a file in a repo on
// raw.githubusercontent.com — the form some community plugin directories hand
// users directly via a "copy manifest URL" button, rather than a
// github.com/owner/repo URL. Matches both the short branch form
// (.../owner/repo/main/manifest.json) and the long ref form GitHub also
// serves (.../owner/repo/refs/heads/main/manifest.json).
var rawGithubUsercontentPattern = regexp.MustCompile(`^(?:https?://)?raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$`)

func parseGitHubURL(raw string) (owner, name, branch string, err error) {
	m := githubURLPattern.FindStringSubmatch(strings.TrimSpace(raw))
	if m == nil {
		return "", "", "", fmt.Errorf("not a github.com/owner/repo URL")
	}
	return m[1], m[2], m[3], nil
}

// parseRawGithubUsercontentURL extracts owner/repo/branch/path from a direct
// raw.githubusercontent.com file URL. The returned path lets AddRepo fetch
// exactly what the user pasted instead of assuming "manifest.json" — most
// links do point at manifest.json, but there's no reason to require it.
func parseRawGithubUsercontentURL(raw string) (owner, name, branch, path string, ok bool) {
	m := rawGithubUsercontentPattern.FindStringSubmatch(strings.TrimSpace(raw))
	if m == nil {
		return "", "", "", "", false
	}
	return m[1], m[2], m[3], m[4], true
}

func (m *Manager) fetchRaw(ctx context.Context, owner, name, branch, path string) ([]byte, error) {
	url := fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s/%s", owner, name, branch, path)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	res, err := m.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d fetching %s", res.StatusCode, path)
	}
	// Cap at maxFetchBody (20 MiB) — repo manifests and scraper JS are small
	// by design; an oversized response is almost certainly an error or a
	// malicious repo, not a legitimate file. readCapped errors rather than
	// silently truncating so callers see a clear message instead of a baffling
	// parse error downstream.
	return readCapped(res.Body)
}

// resolveBranchAndManifest tries the given branch (if any), falling back to
// main then master, and returns whichever branch actually had a manifest.json.
func (m *Manager) resolveBranchAndManifest(ctx context.Context, owner, name, branch string) (string, []byte, error) {
	candidates := []string{branch}
	if branch == "" {
		candidates = []string{"main", "master"}
	}
	var lastErr error
	for _, b := range candidates {
		data, err := m.fetchRaw(ctx, owner, name, b, "manifest.json")
		if err == nil {
			return b, data, nil
		}
		lastErr = err
	}
	return "", nil, lastErr
}

// AddRepo fetches and parses a repo's manifest.json and persists it with every
// scraper Enabled=false — nothing is downloaded or executed until the user
// opts in per-scraper. Accepts either a github.com/owner/repo URL or a direct
// raw.githubusercontent.com link to the manifest file itself (the form some
// community plugin directories hand users via a "copy" button).
func (m *Manager) AddRepo(ctx context.Context, rawURL string) (Repo, error) {
	var owner, name, resolvedBranch string
	var data []byte

	if o, n, b, path, ok := parseRawGithubUsercontentURL(rawURL); ok {
		owner, name, resolvedBranch = o, n, b
		var err error
		data, err = m.fetchRaw(ctx, owner, name, resolvedBranch, path)
		if err != nil {
			return Repo{}, fmt.Errorf("could not fetch manifest: %w", err)
		}
	} else {
		var branch string
		var err error
		owner, name, branch, err = parseGitHubURL(rawURL)
		if err != nil {
			return Repo{}, fmt.Errorf("not a github.com/owner/repo URL or a raw.githubusercontent.com manifest link")
		}
		resolvedBranch, data, err = m.resolveBranchAndManifest(ctx, owner, name, branch)
		if err != nil {
			return Repo{}, fmt.Errorf("could not fetch manifest.json: %w", err)
		}
	}

	entries, err := parseManifest(data)
	if err != nil {
		return Repo{}, fmt.Errorf("could not parse manifest.json: %w", err)
	}

	scrapers := make([]Scraper, 0, len(entries))
	for _, e := range entries {
		s := newScraper(e)
		s.Enabled = false
		scrapers = append(scrapers, s)
	}

	repo := Repo{
		ID:        owner + "/" + name,
		Owner:     owner,
		Name:      name,
		Branch:    resolvedBranch,
		URL:       rawURL,
		Enabled:   false,
		Scrapers:  scrapers,
		FetchedAt: time.Now(),
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	for i, r := range m.repos {
		if r.ID == repo.ID {
			m.repos[i] = repo
			m.invalidateStreamCache()
			return repo, m.saveL()
		}
	}
	m.repos = append(m.repos, repo)
	m.invalidateStreamCache()
	return repo, m.saveL()
}

// RemoveRepo deletes a repo and all its cached scraper code.
func (m *Manager) RemoveRepo(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i, r := range m.repos {
		if r.ID == id {
			m.repos = append(m.repos[:i], m.repos[i+1:]...)
			m.invalidateStreamCache()
			return m.saveL()
		}
	}
	return fmt.Errorf("repo not found")
}

// SetRepoEnabled is the master on/off switch for a repo. It does not itself
// fetch any scraper code — per-scraper enable does that lazily.
func (m *Manager) SetRepoEnabled(id string, enabled bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i, r := range m.repos {
		if r.ID == id {
			m.repos[i].Enabled = enabled
			m.invalidateStreamCache()
			return m.saveL()
		}
	}
	return fmt.Errorf("repo not found")
}

// SetScraperEnabled toggles one scraper within a repo. On first enable it
// lazily fetches and caches the scraper's JS; a fetch/parse failure is stored
// in CodeErr and the scraper is refused enable rather than silently switched
// on with no code behind it.
func (m *Manager) SetScraperEnabled(ctx context.Context, repoID, scraperID string, enabled bool) error {
	m.mu.Lock()
	var repoIdx, scraperIdx = -1, -1
	for i, r := range m.repos {
		if r.ID != repoID {
			continue
		}
		repoIdx = i
		for j, s := range r.Scrapers {
			if s.ID == scraperID {
				scraperIdx = j
				break
			}
		}
		break
	}
	if repoIdx == -1 || scraperIdx == -1 {
		m.mu.Unlock()
		return fmt.Errorf("scraper not found")
	}

	if !enabled {
		m.repos[repoIdx].Scrapers[scraperIdx].Enabled = false
		m.invalidateStreamCache()
		err := m.saveL()
		m.mu.Unlock()
		return err
	}

	repo := m.repos[repoIdx]
	scraper := repo.Scrapers[scraperIdx]
	needsFetch := scraper.Code == "" || scraper.CodeErr != ""
	m.mu.Unlock()

	if needsFetch {
		code, fetchErr := m.fetchRaw(ctx, repo.Owner, repo.Name, repo.Branch, scraper.Filename)

		m.mu.Lock()
		defer m.mu.Unlock()
		// Re-find in case the slice changed while the network call was in flight.
		for i, r := range m.repos {
			if r.ID != repoID {
				continue
			}
			for j, s := range r.Scrapers {
				if s.ID != scraperID {
					continue
				}
				if fetchErr != nil {
					m.repos[i].Scrapers[j].Enabled = false
					m.repos[i].Scrapers[j].CodeErr = fetchErr.Error()
					m.invalidateStreamCache()
					if err := m.saveL(); err != nil {
						return fmt.Errorf("could not fetch scraper code: %v; save state: %w", fetchErr, err)
					}
					return fmt.Errorf("could not fetch scraper code: %w", fetchErr)
				}
				now := time.Now()
				m.repos[i].Scrapers[j].Code = string(code)
				m.repos[i].Scrapers[j].CodeFetchedAt = &now
				m.repos[i].Scrapers[j].CodeErr = ""
				m.repos[i].Scrapers[j].Enabled = true
				m.invalidateStreamCache()
				return m.saveL()
			}
		}
		return fmt.Errorf("scraper not found")
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	m.repos[repoIdx].Scrapers[scraperIdx].Enabled = true
	m.invalidateStreamCache()
	return m.saveL()
}

// RefreshRepo refetches manifest.json and, for any currently-enabled scraper,
// its JS too — matching Nuvio's own repo-refresh semantics.
func (m *Manager) RefreshRepo(ctx context.Context, id string) error {
	m.mu.RLock()
	var repo Repo
	found := false
	for _, r := range m.repos {
		if r.ID == id {
			repo = r
			found = true
			break
		}
	}
	m.mu.RUnlock()
	if !found {
		return fmt.Errorf("repo not found")
	}

	manifestPath := "manifest.json"
	if _, _, _, path, ok := parseRawGithubUsercontentURL(repo.URL); ok {
		manifestPath = path
	}
	data, err := m.fetchRaw(ctx, repo.Owner, repo.Name, repo.Branch, manifestPath)
	if err != nil {
		return m.recordRepoRefreshError(id, err)
	}
	entries, err := parseManifest(data)
	if err != nil {
		return m.recordRepoRefreshError(id, fmt.Errorf("could not parse manifest.json: %w", err))
	}

	// Preserve Enabled/Code for scrapers that still exist; refetch code for
	// ones that were enabled.
	prevByID := make(map[string]Scraper, len(repo.Scrapers))
	for _, s := range repo.Scrapers {
		prevByID[s.ID] = s
	}
	newScrapers := make([]Scraper, 0, len(entries))
	for _, e := range entries {
		s := newScraper(e)
		if prev, ok := prevByID[e.ID]; ok && prev.Enabled {
			code, fetchErr := m.fetchRaw(ctx, repo.Owner, repo.Name, repo.Branch, e.Filename)
			if fetchErr != nil {
				s.CodeErr = fetchErr.Error()
				s.Enabled = false
			} else {
				now := time.Now()
				s.Code = string(code)
				s.CodeFetchedAt = &now
				s.Enabled = true
			}
		}
		newScrapers = append(newScrapers, s)
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	for i, r := range m.repos {
		if r.ID == id {
			m.repos[i].Scrapers = newScrapers
			m.repos[i].FetchedAt = time.Now()
			m.repos[i].FetchErr = ""
		}
	}
	m.invalidateStreamCache()
	return m.saveL()
}

// recordRepoRefreshError keeps refresh failures visible after restart. FetchErr
// is user-facing state, so updating it only in memory would make the error
// disappear as soon as Cove or the active profile restarted.
func (m *Manager) recordRepoRefreshError(id string, refreshErr error) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := range m.repos {
		if m.repos[i].ID != id {
			continue
		}
		m.repos[i].FetchErr = refreshErr.Error()
		if saveErr := m.saveL(); saveErr != nil {
			return fmt.Errorf("%w; save state: %v", refreshErr, saveErr)
		}
		return refreshErr
	}
	return refreshErr
}
