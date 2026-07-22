package player

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestValidateInfoHash(t *testing.T) {
	tests := []struct {
		name     string
		infoHash string
		valid    bool
	}{
		{name: "lowercase", infoHash: "0123456789abcdef0123456789abcdef01234567", valid: true},
		{name: "uppercase", infoHash: "0123456789ABCDEF0123456789ABCDEF01234567", valid: true},
		{name: "empty", infoHash: "", valid: false},
		{name: "too short", infoHash: "0123456789abcdef", valid: false},
		{name: "non hexadecimal", infoHash: "g123456789abcdef0123456789abcdef01234567", valid: false},
		{name: "traversal", infoHash: "../../outside", valid: false},
		{name: "absolute path", infoHash: "/tmp/outside", valid: false},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := validateInfoHash(tc.infoHash)
			if tc.valid {
				require.NoError(t, err)
			} else {
				require.Error(t, err)
			}
		})
	}
}

func TestGetTorrentFileRejectsInvalidHashBeforeUsingClient(t *testing.T) {
	p := &Player{}
	_, err := p.getTorrentFile("../../outside", nil, nil, nil)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "invalid infohash")
}

func TestRemoveTorrentDataEntryRemovesContainedTarget(t *testing.T) {
	sandbox := t.TempDir()
	dataDir := filepath.Join(sandbox, "torrents")
	target := filepath.Join(dataDir, "show", "season-1")
	sibling := filepath.Join(dataDir, "keep")
	require.NoError(t, os.MkdirAll(target, 0o755))
	require.NoError(t, os.MkdirAll(sibling, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(target, "episode.mkv"), []byte("data"), 0o600))

	require.NoError(t, removeTorrentDataEntry(dataDir, "show/season-1"))
	_, err := os.Stat(target)
	require.ErrorIs(t, err, os.ErrNotExist)
	_, err = os.Stat(sibling)
	require.NoError(t, err)
	_, err = os.Stat(dataDir)
	require.NoError(t, err)
}

func TestRemoveTorrentDataEntryRejectsEscapeAndRoot(t *testing.T) {
	sandbox := t.TempDir()
	dataDir := filepath.Join(sandbox, "torrents")
	outside := filepath.Join(sandbox, "outside")
	require.NoError(t, os.MkdirAll(dataDir, 0o755))
	require.NoError(t, os.MkdirAll(outside, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(dataDir, "keep"), []byte("inside"), 0o600))
	require.NoError(t, os.WriteFile(filepath.Join(outside, "keep"), []byte("outside"), 0o600))

	unsafeNames := []string{
		"",
		".",
		"child/..",
		"..",
		"../outside",
		filepath.Join("nested", "..", "..", "outside"),
		outside,
	}
	for _, name := range unsafeNames {
		t.Run(name, func(t *testing.T) {
			require.Error(t, removeTorrentDataEntry(dataDir, name))
		})
	}

	_, err := os.Stat(filepath.Join(dataDir, "keep"))
	require.NoError(t, err)
	_, err = os.Stat(filepath.Join(outside, "keep"))
	require.NoError(t, err)
}

func TestRemoveTorrentDataEntryRejectsEscapingSymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("creating symlinks may require elevated Windows privileges")
	}

	sandbox := t.TempDir()
	dataDir := filepath.Join(sandbox, "torrents")
	outside := filepath.Join(sandbox, "outside")
	require.NoError(t, os.MkdirAll(dataDir, 0o755))
	require.NoError(t, os.MkdirAll(outside, 0o755))
	sentinel := filepath.Join(outside, "keep")
	require.NoError(t, os.WriteFile(sentinel, []byte("outside"), 0o600))
	require.NoError(t, os.Symlink(outside, filepath.Join(dataDir, "escape")))

	require.Error(t, removeTorrentDataEntry(dataDir, "escape/keep"))
	_, err := os.Stat(sentinel)
	require.NoError(t, err)
}

func TestRemoveTorrentDataEntryMissingRootMatchesRemoveAll(t *testing.T) {
	dataDir := filepath.Join(t.TempDir(), "missing")
	require.NoError(t, removeTorrentDataEntry(dataDir, "unused"))
}
