// Package updater implements self-update via GitHub releases. The check is
// skipped entirely on Linux (the only distribution channels there are
// Flatpak and the AUR PKGBUILD package, both of which own their own updates
// — Flatpak via flatpak/Flathub, PKGBUILD via pacman; self-extracting a
// release tarball over either would corrupt the package manager's view of
// installed files) and on dev builds (non-semver version string). Applying
// an update exits the process with code 42, a sentinel the Qt shell watches
// for to know it should restart the backend rather than treating the exit
// as a crash.
package updater

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

// apiClient bounds GitHub API metadata calls; downloadClient allows much
// longer for the release archive itself on slow connections.
var (
	apiClient       = &http.Client{Timeout: 30 * time.Second}
	downloadClient  = &http.Client{Timeout: 10 * time.Minute}
	executablePath  = os.Executable
	scheduleRestart = func() {
		go func() {
			time.Sleep(250 * time.Millisecond)
			os.Exit(RestartExitCode)
		}()
	}
)

// RestartExitCode is the exit code the Go backend uses to signal the Qt shell
// to restart (picking up new binaries and web assets). The shell connects to
// the backend's finished signal and calls QProcess::startDetached on this code.
const RestartExitCode = 42

const (
	maxArchiveBytes   int64 = 512 << 20 // 512 MiB compressed download
	maxExtractedBytes int64 = 1 << 30   // 1 GiB across all extracted files
	maxArchiveEntries       = 10_000
)

// assetName is the filename this build looks for in GitHub release assets.
// The CI packaging job MUST emit an asset with exactly this name or check()
// silently returns available=false. Both sides are pinned here as the source
// of truth — keep in sync with the "Package" jobs in release.yml.
func assetName() string {
	return assetNameFor(runtime.GOOS, runtime.GOARCH)
}

func assetNameFor(goos, goarch string) string {
	var ext string
	switch goos {
	case "windows":
		ext = ".zip"
	default:
		ext = ".tar.gz"
	}
	return fmt.Sprintf("cove-%s-%s%s", goos, goarch, ext)
}

// pendingURL/pendingChecksumURL are set by check() and consumed by
// applyUpdate(). Storing them server-side avoids accepting a client-supplied
// download URL (SSRF risk).
var (
	mu                 sync.Mutex
	checkMu            sync.Mutex
	applyMu            sync.Mutex
	pendingURL         string
	pendingChecksumURL string
)

// CheckResult is the JSON payload returned by GET /api/update/check.
type CheckResult struct {
	Available      bool   `json:"available"`
	CurrentVersion string `json:"current_version"`
	LatestVersion  string `json:"latest_version"`
	ReleaseName    string `json:"release_name"`
}

type ghRelease struct {
	TagName string    `json:"tag_name"`
	Name    string    `json:"name"`
	Assets  []ghAsset `json:"assets"`
}

type ghAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
}

// newerThan returns true when latest is strictly greater than current using
// numeric field comparison so "v0.10.0" > "v0.9.0" resolves correctly.
func newerThan(latest, current string) bool {
	parse := func(v string) [3]int {
		v = strings.TrimPrefix(v, "v")
		parts := strings.SplitN(v, ".", 3)
		var n [3]int
		for i, p := range parts {
			if i >= 3 {
				break
			}
			// Strip any pre-release suffix (e.g. "0-rc1" → 0).
			n[i], _ = strconv.Atoi(strings.SplitN(p, "-", 2)[0])
		}
		return n
	}
	l, c := parse(latest), parse(current)
	for i := range l {
		if l[i] != c[i] {
			return l[i] > c[i]
		}
	}
	return false
}

// isCleanSemver returns true for "vX.Y.Z" with no extra suffixes.
// Only clean release builds should ever prompt for updates.
func isCleanSemver(v string) bool {
	v = strings.TrimPrefix(v, "v")
	parts := strings.SplitN(v, ".", 3)
	if len(parts) != 3 {
		return false
	}
	for _, p := range parts {
		if _, err := strconv.Atoi(p); err != nil {
			return false
		}
	}
	return true
}

func fetchLatest() (*ghRelease, error) {
	url := "https://api.github.com/repos/coveninja/cove/releases/latest"
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")

	resp, err := apiClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, nil // no releases published yet
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub API returned %d", resp.StatusCode)
	}

	var rel ghRelease
	return &rel, json.NewDecoder(resp.Body).Decode(&rel)
}

func check(currentVersion string) (*CheckResult, error) {
	return checkForPlatform(currentVersion, runtime.GOOS, runtime.GOARCH)
}

func checkForPlatform(currentVersion, goos, goarch string) (*CheckResult, error) {
	// Serialize checks and invalidate any previously offered release before
	// doing work. A failed/unavailable check must not leave an older asset
	// eligible for /api/update/apply.
	checkMu.Lock()
	defer checkMu.Unlock()
	mu.Lock()
	pendingURL = ""
	pendingChecksumURL = ""
	mu.Unlock()

	result := &CheckResult{CurrentVersion: currentVersion}

	// Linux has no portable/self-contained distribution channel — only
	// Flatpak and the AUR PKGBUILD package, both of which manage their own
	// updates (flatpak/Flathub, pacman respectively). Self-extracting the
	// release tarball in place would either be a no-op (Flatpak's read-only
	// /app) or silently desync a pacman-owned install from its package
	// database, so self-update never offers itself here.
	// Android and iOS distribute and update through the Play Store and App
	// Store respectively; self-patching outside those channels is not
	// permitted and would not work in a sandboxed environment.
	if goos == "linux" || goos == "android" || goos == "ios" {
		return result, nil
	}

	// Only clean release builds ("vX.Y.Z") can be compared against releases.
	// Dev builds, git-describe strings ("v0.7.2-3-gabc1234"), dirty builds, etc.
	// are all excluded so developers don't get spurious update prompts.
	if !isCleanSemver(currentVersion) {
		return result, nil
	}

	rel, err := fetchLatest()
	if err != nil {
		return nil, err
	}
	if rel == nil {
		return result, nil
	}

	result.LatestVersion = rel.TagName
	result.ReleaseName = rel.Name

	if !newerThan(rel.TagName, currentVersion) {
		return result, nil
	}

	want := assetNameFor(goos, goarch)
	var assetURL, checksumURL string
	for _, a := range rel.Assets {
		switch a.Name {
		case want:
			assetURL = a.BrowserDownloadURL
		case want + ".sha256":
			checksumURL = a.BrowserDownloadURL
		}
	}
	if assetURL == "" {
		return result, nil
	}
	if checksumURL == "" {
		// Fail closed: an update that can't be verified is not offered.
		// Every release newer than the build carrying this code also
		// publishes .sha256 assets, so this only fires on a broken release.
		log.Printf("[updater] release %s has no %s.sha256 asset; refusing unverifiable update", rel.TagName, want)
		return result, nil
	}
	result.Available = true
	mu.Lock()
	pendingURL = assetURL
	pendingChecksumURL = checksumURL
	mu.Unlock()
	return result, nil
}

// fetchChecksum downloads a .sha256 asset and returns the hex digest (the
// first whitespace-delimited field, tolerating "hash  filename" format).
func fetchChecksum(url string) (string, error) {
	resp, err := apiClient.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("checksum download: HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", err
	}
	sum := strings.Fields(string(data))
	if len(sum) == 0 || len(sum[0]) != sha256.Size*2 {
		return "", fmt.Errorf("malformed checksum file")
	}
	if _, err := hex.DecodeString(sum[0]); err != nil {
		return "", fmt.Errorf("malformed checksum file: %w", err)
	}
	return strings.ToLower(sum[0]), nil
}

func applyUpdate() error {
	if !applyMu.TryLock() {
		return fmt.Errorf("update already in progress")
	}
	defer applyMu.Unlock()

	mu.Lock()
	url := pendingURL
	checksumURL := pendingChecksumURL
	mu.Unlock()

	if url == "" || checksumURL == "" {
		return fmt.Errorf("no pending update (call /api/update/check first)")
	}

	execPath, err := executablePath()
	if err != nil {
		return fmt.Errorf("cannot determine executable path: %w", err)
	}
	destDir := filepath.Dir(execPath)

	wantSum, err := fetchChecksum(checksumURL)
	if err != nil {
		return fmt.Errorf("checksum: %w", err)
	}

	log.Printf("[updater] downloading %s", url)
	resp, err := downloadClient.Get(url) //nolint:noctx
	if err != nil {
		return fmt.Errorf("download: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download: HTTP %d", resp.StatusCode)
	}

	// Spool to a temp file, hashing as we go: nothing touches destDir until
	// the archive's SHA-256 matches the published checksum.
	tmp, err := os.CreateTemp("", "cove-update-*")
	if err != nil {
		return fmt.Errorf("temp file: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	hasher := sha256.New()
	if _, err := copyWithLimit(io.MultiWriter(tmp, hasher), resp.Body, maxArchiveBytes); err != nil {
		tmp.Close()
		return fmt.Errorf("download: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("temp file: %w", err)
	}
	gotSum := hex.EncodeToString(hasher.Sum(nil))
	if gotSum != wantSum {
		return fmt.Errorf("checksum mismatch: archive %s, expected %s", gotSum, wantSum)
	}

	log.Printf("[updater] checksum verified — extracting to %s", destDir)
	if runtime.GOOS == "windows" {
		if err := extractZip(tmpName, destDir, filepath.Base(execPath)); err != nil {
			return fmt.Errorf("extract: %w", err)
		}
	} else {
		f, err := os.Open(tmpName)
		if err != nil {
			return fmt.Errorf("temp file: %w", err)
		}
		defer f.Close()
		if err := extractTarGz(f, destDir); err != nil {
			return fmt.Errorf("extract: %w", err)
		}
	}

	// Consume only the release that was applied. Do not erase a newer release
	// discovered by a check that happened while this download was running.
	mu.Lock()
	if pendingURL == url && pendingChecksumURL == checksumURL {
		pendingURL = ""
		pendingChecksumURL = ""
	}
	mu.Unlock()

	log.Println("[updater] done — restarting")
	// Delay exit slightly so the HTTP response is flushed to the client before
	// the process disappears. The Qt shell detects RestartExitCode and re-execs
	// itself; on Windows it also renames cove.exe.new → cove.exe first.
	scheduleRestart()
	return nil
}

func copyWithLimit(dst io.Writer, src io.Reader, limit int64) (int64, error) {
	n, err := io.Copy(dst, io.LimitReader(src, limit+1))
	if err != nil {
		return n, err
	}
	if n > limit {
		return n, fmt.Errorf("content exceeds %d byte limit", limit)
	}
	return n, nil
}

// securePath joins an archive entry name into destDir, erroring on any entry
// that would land outside it. Tampering aborts the whole update — a partially
// applied archive is worse than none.
func securePath(destDir, name string) (string, error) {
	target := filepath.Join(destDir, filepath.FromSlash(name))
	rel, err := filepath.Rel(destDir, target)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return "", fmt.Errorf("archive entry escapes destination: %s", name)
	}
	return target, nil
}

// extractZip extracts a .zip archive into destDir. selfName is the base name
// of the currently-running binary; on Windows a running .exe cannot be renamed
// over itself, so it is written as selfName+".new" and the Qt shell performs
// the final rename after exit code 42 causes it to restart.
func extractZip(zipPath, destDir, selfName string) error {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer r.Close()

	if len(r.File) > maxArchiveEntries {
		return fmt.Errorf("archive contains too many entries")
	}
	stageDir, err := os.MkdirTemp(destDir, ".cove-update-stage-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stageDir)

	files := make([]stagedFile, 0, len(r.File))
	var extractedBytes int64
	for _, f := range r.File {
		target, err := securePath(stageDir, f.Name)
		if err != nil {
			return err
		}

		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if !f.FileInfo().Mode().IsRegular() {
			return fmt.Errorf("archive contains non-regular entry: %s", f.Name)
		}
		size := int64(f.UncompressedSize64)
		if size < 0 || size > maxExtractedBytes-extractedBytes {
			return fmt.Errorf("archive exceeds %d extracted byte limit", maxExtractedBytes)
		}
		extractedBytes += size

		rel, err := filepath.Rel(stageDir, target)
		if err != nil {
			return err
		}
		if filepath.Base(rel) == selfName {
			rel += ".new"
			target += ".new"
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}

		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o755)
		if err != nil {
			rc.Close()
			return err
		}
		n, copyErr := io.Copy(out, rc)
		rc.Close()
		closeErr := out.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if n != size {
			return fmt.Errorf("archive entry %s size mismatch", f.Name)
		}
		files = append(files, stagedFile{rel: rel, path: target})
	}
	return commitStagedFiles(destDir, files)
}

// extractTarGz extracts and validates a .tar.gz archive in a staging directory
// before committing any files into destDir.
func extractTarGz(r io.Reader, destDir string) error {
	gz, err := gzip.NewReader(r)
	if err != nil {
		return err
	}
	defer gz.Close()

	stageDir, err := os.MkdirTemp(destDir, ".cove-update-stage-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stageDir)

	tr := tar.NewReader(gz)
	files := make([]stagedFile, 0)
	var extractedBytes int64
	entryCount := 0
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		entryCount++
		if entryCount > maxArchiveEntries {
			return fmt.Errorf("archive contains too many entries")
		}

		target, err := securePath(stageDir, hdr.Name)
		if err != nil {
			return err
		}

		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if hdr.Size < 0 || hdr.Size > maxExtractedBytes-extractedBytes {
				return fmt.Errorf("archive exceeds %d extracted byte limit", maxExtractedBytes)
			}
			extractedBytes += hdr.Size
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			// Mask modes from the archive: regular files get 0644, anything
			// marked executable gets 0755 — never setuid/sticky bits.
			perm := os.FileMode(0o644)
			if hdr.Mode&0o111 != 0 {
				perm = 0o755
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, perm)
			if err != nil {
				return err
			}
			n, copyErr := io.Copy(f, tr)
			closeErr := f.Close()
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
			if n != hdr.Size {
				return fmt.Errorf("archive entry %s size mismatch", hdr.Name)
			}
			rel, err := filepath.Rel(stageDir, target)
			if err != nil {
				return err
			}
			files = append(files, stagedFile{rel: rel, path: target})
		case tar.TypeSymlink, tar.TypeLink:
			return fmt.Errorf("archive contains link entry: %s", hdr.Name)
		default:
			// PAX/global headers and other metadata entries carry no payload
			// this extractor cares about — ignore them.
		}
	}
	return commitStagedFiles(destDir, files)
}

type stagedFile struct {
	rel  string
	path string
}

type committedFile struct {
	target string
	backup string
	hadOld bool
}

// commitStagedFiles preflights every destination, then installs the staged
// files. Existing files are moved aside and restored if any later rename
// fails, so a malformed archive or destination conflict cannot leave a mixed
// release behind.
func commitStagedFiles(destDir string, files []stagedFile) error {
	for _, file := range files {
		target, err := securePath(destDir, file.rel)
		if err != nil {
			return err
		}
		if err := validateDestinationPath(destDir, target); err != nil {
			return err
		}
	}

	backupDir, err := os.MkdirTemp(destDir, ".cove-update-backup-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(backupDir)

	committed := make([]committedFile, 0, len(files))
	rollback := func() {
		for i := len(committed) - 1; i >= 0; i-- {
			item := committed[i]
			_ = os.Remove(item.target)
			if item.hadOld {
				_ = os.MkdirAll(filepath.Dir(item.target), 0o755)
				_ = os.Rename(item.backup, item.target)
			}
		}
	}

	for _, file := range files {
		target, _ := securePath(destDir, file.rel)
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			rollback()
			return err
		}

		item := committedFile{target: target}
		if _, err := os.Lstat(target); err == nil {
			item.hadOld = true
			item.backup, err = securePath(backupDir, file.rel)
			if err != nil {
				rollback()
				return err
			}
			if err := os.MkdirAll(filepath.Dir(item.backup), 0o755); err != nil {
				rollback()
				return err
			}
			if err := os.Rename(target, item.backup); err != nil {
				rollback()
				return err
			}
		} else if !os.IsNotExist(err) {
			rollback()
			return err
		}
		committed = append(committed, item)

		if err := os.Rename(file.path, target); err != nil {
			rollback()
			return err
		}
	}
	return nil
}

func validateDestinationPath(destDir, target string) error {
	root, err := os.Lstat(destDir)
	if err != nil {
		return err
	}
	if root.Mode()&os.ModeSymlink != 0 || !root.IsDir() {
		return fmt.Errorf("update destination is not a real directory")
	}

	rel, err := filepath.Rel(destDir, target)
	if err != nil {
		return err
	}
	parts := strings.Split(rel, string(filepath.Separator))
	current := destDir
	for i, part := range parts {
		current = filepath.Join(current, part)
		info, err := os.Lstat(current)
		if os.IsNotExist(err) {
			return nil
		}
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("update destination contains symlink: %s", current)
		}
		if i < len(parts)-1 && !info.IsDir() {
			return fmt.Errorf("update destination parent is not a directory: %s", current)
		}
		if i == len(parts)-1 && !info.Mode().IsRegular() {
			return fmt.Errorf("update destination is not a regular file: %s", current)
		}
	}
	return nil
}

// SetupHandlers registers /api/update/check and /api/update/apply on mux.
func SetupHandlers(mux *http.ServeMux, currentVersion string) {
	mux.HandleFunc("/api/update/check", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		result, err := check(currentVersion)
		if err != nil {
			log.Println("[updater] check error:", err)
			http.Error(w, "update check failed", http.StatusInternalServerError)
			return
		}
		utils.WriteJSON(w, result)
	})))

	mux.HandleFunc("/api/update/apply", utils.CorsMiddleware(utils.MethodGuard(http.MethodPost, func(w http.ResponseWriter, r *http.Request) {
		// applyUpdate blocks for the duration of the download + extraction,
		// then spawns a goroutine to exit with RestartExitCode.
		if err := applyUpdate(); err != nil {
			log.Println("[updater] apply error:", err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	})))
}
