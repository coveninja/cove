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

	// Wait for the timer callback to finish rather than assuming the runner
	// scheduled and completed it within a fixed multiple of the deadline.
	var got []byte
	require.Eventually(t, func() bool {
		var err error
		got, err = os.ReadFile(path)
		return err == nil
	}, 2*time.Second, 5*time.Millisecond)
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
	// Give the second call a dramatically different delay. If it reset the
	// existing timer, the write would be deferred for an hour; retaining the
	// original timer writes the latest payload promptly. This checks behavior
	// without relying on a narrow scheduling window around the first deadline.
	dir := t.TempDir()
	path := filepath.Join(dir, "store.json")
	var d DebouncedPersist

	d.MarkDirty(path, []byte(`"v1"`), testDebounce)
	d.MarkDirty(path, []byte(`"v2"`), time.Hour)

	var got []byte
	require.Eventually(t, func() bool {
		var err error
		got, err = os.ReadFile(path)
		return err == nil
	}, 2*time.Second, 5*time.Millisecond, "the original timer should still fire")
	assert.Equal(t, `"v2"`, string(got), "latest payload must be written")
}
