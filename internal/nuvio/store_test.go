package nuvio

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLoadStoreUsesLegacyFileTimestampAndReportsCorruption(t *testing.T) {
	dir := t.TempDir()
	legacyPath := filepath.Join(dir, "legacy.json")
	require.NoError(t, os.WriteFile(legacyPath, []byte(`{"repos":[{"id":"owner/repo"}]}`), 0o644))
	modified := time.Date(2024, time.January, 2, 3, 4, 5, 0, time.UTC)
	require.NoError(t, os.Chtimes(legacyPath, modified, modified))

	store, err := loadStore(legacyPath)
	require.NoError(t, err)
	require.Len(t, store.Repos, 1)
	assert.WithinDuration(t, modified, store.UpdatedAt, time.Second)

	corruptPath := filepath.Join(dir, "corrupt.json")
	require.NoError(t, os.WriteFile(corruptPath, []byte("{invalid"), 0o644))
	_, err = loadStore(corruptPath)
	require.Error(t, err)

	_, err = loadStore(dir)
	require.Error(t, err)
}
