package utils

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testDebounce = 40 * time.Millisecond

func TestDebouncedPersist_CoalescesManyWrites(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "store.json")
	var d DebouncedPersist

	// Three rapid MarkDirty calls — only the last payload should be written
	// because the timer is armed on the first call and not reset by subsequent
	// ones, while pendingData is updated to the latest value each time.
	d.MarkDirty(path, []byte(`"first"`), testDebounce)
	d.MarkDirty(path, []byte(`"second"`), testDebounce)
	d.MarkDirty(path, []byte(`"third"`), testDebounce)

	// File must not exist yet — the timer has not fired.
	_, err := os.Stat(path)
	assert.True(t, os.IsNotExist(err), "no write should happen before the deadline")

	// Wait for the timer to fire.
	time.Sleep(2 * testDebounce)

	got, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.Equal(t, `"third"`, string(got))
}

func TestDebouncedPersist_NoWriteBeforeDeadline(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "store.json")
	var d DebouncedPersist

	d.MarkDirty(path, []byte(`"data"`), testDebounce)

	// Immediately after MarkDirty the file must not exist.
	_, err := os.Stat(path)
	assert.True(t, os.IsNotExist(err), "write must not happen before the after duration elapses")

	// Cancel the timer so it doesn't fire after the temp dir is cleaned up.
	d.Flush()
}

func TestDebouncedPersist_FlushForcesImmediateWrite(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "store.json")
	var d DebouncedPersist

	d.MarkDirty(path, []byte(`"flushed"`), time.Hour) // very long deadline

	// File must not exist yet.
	_, err := os.Stat(path)
	require.True(t, os.IsNotExist(err))

	d.Flush()

	// Flush must have written immediately, without waiting for the deadline.
	got, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.Equal(t, `"flushed"`, string(got))
}

func TestDebouncedPersist_FlushNoPendingIsNoOp(t *testing.T) {
	var d DebouncedPersist
	// Should not panic or error when there is nothing pending.
	assert.NotPanics(t, func() { d.Flush() })
}

func TestDebouncedPersist_FlushCancelsTimer(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "store.json")
	var d DebouncedPersist

	d.MarkDirty(path, []byte(`"v1"`), testDebounce)
	d.Flush()

	// Overwrite path so we can tell if the timer fires a second write.
	require.NoError(t, os.WriteFile(path, []byte(`"after-flush"`), 0o644))

	// Wait well past the original debounce window.
	time.Sleep(2 * testDebounce)

	// The timer was stopped by Flush; the file should still contain what we
	// wrote manually, not a stale write from the cancelled timer.
	got, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.Equal(t, `"after-flush"`, string(got))
}

func TestDebouncedPersist_SecondMarkDirtyDoesNotResetTimer(t *testing.T) {
	// If the timer were reset on each MarkDirty call, a stream of calls spaced
	// just under the deadline would defer the write indefinitely. Verify that
	// the write happens at roughly the first MarkDirty's deadline regardless of
	// a second call that arrives just before firing.
	dir := t.TempDir()
	path := filepath.Join(dir, "store.json")
	var d DebouncedPersist

	start := time.Now()
	d.MarkDirty(path, []byte(`"v1"`), testDebounce)
	time.Sleep(testDebounce / 2) // second call arrives midway
	d.MarkDirty(path, []byte(`"v2"`), testDebounce)

	// Wait just a bit past the first deadline; if the timer was reset by the
	// second MarkDirty the file wouldn't be there yet.
	time.Sleep(testDebounce)

	elapsed := time.Since(start)
	got, err := os.ReadFile(path)
	require.NoError(t, err, "file must exist within ~1× the deadline (elapsed: %v)", elapsed)
	assert.Equal(t, `"v2"`, string(got), "latest payload must be written")
}
