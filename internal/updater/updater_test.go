package updater

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewerThan(t *testing.T) {
	tests := []struct {
		latest  string
		current string
		want    bool
	}{
		{"v0.10.0", "v0.9.0", true},    // minor bump, double-digit vs single
		{"v1.0.0", "v0.9.9", true},     // major bump
		{"v0.9.1", "v0.9.0", true},     // patch bump
		{"v0.9.0", "v0.9.0", false},    // equal
		{"v0.9.0", "v0.10.0", false},   // older
		{"v1.0.0", "v1.0.0", false},    // exact equal with v prefix
		{"v0.9.0-rc1", "v0.8.0", true}, // pre-release suffix stripped from latest
		{"v1.2.3", "v1.2.4", false},    // patch regression
	}
	for _, tc := range tests {
		t.Run(fmt.Sprintf("%s_vs_%s", tc.latest, tc.current), func(t *testing.T) {
			assert.Equal(t, tc.want, newerThan(tc.latest, tc.current))
		})
	}
}

func TestIsCleanSemver(t *testing.T) {
	tests := []struct {
		v    string
		want bool
	}{
		{"v1.2.3", true},
		{"v0.0.0", true},
		{"v10.20.30", true},
		{"dev", false},
		{"", false},
		{"v0.7.2-3-gabc1234", false}, // git describe output
		{"v1.2", false},              // only two parts
		{"v1.2.3.4", false},          // four parts
	}
	for _, tc := range tests {
		t.Run(tc.v, func(t *testing.T) {
			assert.Equal(t, tc.want, isCleanSemver(tc.v))
		})
	}
}

func TestCheckSkipsOnLinux(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux-only behavior")
	}
	// No network call should happen: the linux skip returns before fetchLatest.
	result, err := check("v1.0.0")
	assert.NoError(t, err)
	assert.False(t, result.Available)
	assert.Empty(t, result.LatestVersion)
}

func TestAssetName(t *testing.T) {
	name := assetName()
	assert.NotEmpty(t, name)
	assert.True(t, strings.HasPrefix(name, "cove-"), "should start with cove-")
	assert.Contains(t, name, runtime.GOOS)
	assert.Contains(t, name, runtime.GOARCH)
	if runtime.GOOS == "windows" {
		assert.True(t, strings.HasSuffix(name, ".zip"))
	} else {
		assert.True(t, strings.HasSuffix(name, ".tar.gz"))
	}
}

func TestSecurePath(t *testing.T) {
	dest := "/tmp/covedest"
	ok := []string{"cove", "web/index.html", "./web/app.js"}
	for _, name := range ok {
		if _, err := securePath(dest, name); err != nil {
			t.Errorf("securePath(%q) unexpectedly rejected: %v", name, err)
		}
	}
	// A leading "/" is cleaned by filepath.Join into a path relative to dest,
	// so "/abs/path" lands safely inside — only real ".." escapes are rejected.
	bad := []string{"../evil", "../../etc/passwd", "web/../../escape"}
	for _, name := range bad {
		if _, err := securePath(dest, name); err == nil {
			t.Errorf("securePath(%q) should have been rejected as escaping", name)
		}
	}
}

type archiveEntry struct {
	name string
	body string
	mode os.FileMode
}

func writeZip(t *testing.T, entries []archiveEntry) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "update.zip")
	f, err := os.Create(path)
	require.NoError(t, err)
	zw := zip.NewWriter(f)
	for _, entry := range entries {
		header := &zip.FileHeader{Name: entry.name, Method: zip.Store}
		header.SetMode(entry.mode)
		writer, err := zw.CreateHeader(header)
		require.NoError(t, err)
		_, err = io.WriteString(writer, entry.body)
		require.NoError(t, err)
	}
	require.NoError(t, zw.Close())
	require.NoError(t, f.Close())
	return path
}

func tarGz(t *testing.T, entries []archiveEntry) []byte {
	t.Helper()
	var compressed bytes.Buffer
	gz := gzip.NewWriter(&compressed)
	tw := tar.NewWriter(gz)
	for _, entry := range entries {
		typeflag := byte(tar.TypeReg)
		if entry.mode.IsDir() {
			typeflag = tar.TypeDir
		} else if entry.mode&os.ModeSymlink != 0 {
			typeflag = tar.TypeSymlink
		}
		header := &tar.Header{
			Name: entry.name, Mode: int64(entry.mode.Perm()), Typeflag: typeflag,
			Size: int64(len(entry.body)),
		}
		if typeflag != tar.TypeReg {
			header.Size = 0
		}
		require.NoError(t, tw.WriteHeader(header))
		if typeflag == tar.TypeReg {
			_, err := io.WriteString(tw, entry.body)
			require.NoError(t, err)
		}
	}
	require.NoError(t, tw.Close())
	require.NoError(t, gz.Close())
	return compressed.Bytes()
}

func TestExtractZipWritesFilesAndStagesRunningBinary(t *testing.T) {
	dest := t.TempDir()
	archive := writeZip(t, []archiveEntry{
		{name: "web/", mode: os.ModeDir | 0o755},
		{name: "web/index.html", body: "new ui", mode: 0o644},
		{name: "cove.exe", body: "new binary", mode: 0o755},
	})

	require.NoError(t, extractZip(archive, dest, "cove.exe"))
	web, err := os.ReadFile(filepath.Join(dest, "web", "index.html"))
	require.NoError(t, err)
	assert.Equal(t, "new ui", string(web))
	binary, err := os.ReadFile(filepath.Join(dest, "cove.exe.new"))
	require.NoError(t, err)
	assert.Equal(t, "new binary", string(binary))
	_, err = os.Stat(filepath.Join(dest, "cove.exe"))
	assert.ErrorIs(t, err, os.ErrNotExist)
}

func TestExtractZipRejectsTraversalAndLinks(t *testing.T) {
	for _, entry := range []archiveEntry{
		{name: "../escape", body: "bad", mode: 0o644},
		{name: "link", body: "target", mode: os.ModeSymlink | 0o777},
	} {
		t.Run(entry.name, func(t *testing.T) {
			err := extractZip(writeZip(t, []archiveEntry{entry}), t.TempDir(), "cove.exe")
			assert.Error(t, err)
		})
	}
}

func TestExtractTarGzWritesNestedFilesAndExecutableMode(t *testing.T) {
	dest := t.TempDir()
	archive := tarGz(t, []archiveEntry{
		{name: "web/", mode: os.ModeDir | 0o755},
		{name: "web/index.html", body: "new ui", mode: 0o644},
		{name: "cove", body: "new binary", mode: 0o755},
	})

	require.NoError(t, extractTarGz(bytes.NewReader(archive), dest))
	web, err := os.ReadFile(filepath.Join(dest, "web", "index.html"))
	require.NoError(t, err)
	assert.Equal(t, "new ui", string(web))
	info, err := os.Stat(filepath.Join(dest, "cove"))
	require.NoError(t, err)
	assert.NotZero(t, info.Mode().Perm()&0o111)
	_, err = os.Stat(filepath.Join(dest, "cove.new"))
	assert.ErrorIs(t, err, os.ErrNotExist)
}

func TestExtractTarGzRejectsTraversalLinksAndInvalidInput(t *testing.T) {
	for _, entry := range []archiveEntry{
		{name: "../../escape", body: "bad", mode: 0o644},
		{name: "link", mode: os.ModeSymlink | 0o777},
	} {
		t.Run(entry.name, func(t *testing.T) {
			err := extractTarGz(bytes.NewReader(tarGz(t, []archiveEntry{entry})), t.TempDir())
			assert.Error(t, err)
		})
	}
	assert.Error(t, extractTarGz(strings.NewReader("not gzip"), t.TempDir()))
}
