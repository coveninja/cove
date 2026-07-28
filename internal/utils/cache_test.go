package utils

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestTTLCache_HitAndMiss(t *testing.T) {
	c := NewTTLCache[string, int](0)

	_, ok := c.Get("missing")
	assert.False(t, ok, "empty cache must miss")

	c.Set("k", 42, time.Minute)
	v, ok := c.Get("k")
	require.True(t, ok)
	assert.Equal(t, 42, v)
}

func TestTTLCache_Expiry(t *testing.T) {
	c := NewTTLCache[string, string](0)
	c.Set("k", "hello", 10*time.Millisecond)

	// Fresh — should hit.
	v, ok := c.Get("k")
	require.True(t, ok)
	assert.Equal(t, "hello", v)

	// Wait for expiry.
	time.Sleep(20 * time.Millisecond)
	_, ok = c.Get("k")
	assert.False(t, ok, "expired entry must not be returned")
	assert.Zero(t, c.Len(), "an expired entry read must be removed")
}

func TestTTLCache_SweepOnSet(t *testing.T) {
	c := NewTTLCache[string, int](0)
	c.Set("old", 1, 10*time.Millisecond)
	assert.Equal(t, 1, c.Len())

	time.Sleep(20 * time.Millisecond)
	// Storing a new entry triggers the sweep of "old".
	c.Set("new", 2, time.Minute)
	assert.Equal(t, 1, c.Len(), "expired entry must be swept on next Set")
}

func TestTTLCache_OverwriteEntry(t *testing.T) {
	c := NewTTLCache[string, int](0)
	c.Set("k", 1, time.Minute)
	c.Set("k", 2, time.Minute)

	v, ok := c.Get("k")
	require.True(t, ok)
	assert.Equal(t, 2, v, "second Set must overwrite the first")
}

func TestTTLCache_ZeroValueOnMiss(t *testing.T) {
	c := NewTTLCache[string, int](0)
	v, ok := c.Get("absent")
	assert.False(t, ok)
	assert.Equal(t, 0, v, "zero value must be returned on a miss")
}

func TestTTLCache_UnboundedDoesNotDropOnOverflow(t *testing.T) {
	// With maxEntries=0 the cache is unbounded: no drop-whole-map ever happens.
	c := NewTTLCache[int, int](0)
	for i := range 100 {
		c.Set(i, i, time.Minute)
	}
	assert.Equal(t, 100, c.Len())
}

func TestTTLCache_BoundedDropsWholeMaps(t *testing.T) {
	c := NewTTLCache[int, int](5)
	for i := range 5 {
		c.Set(i, i, time.Minute)
	}
	assert.Equal(t, 5, c.Len())

	// A distinct insertion at capacity drops the reconstructable cache before
	// storing, so the configured bound is never exceeded.
	c.Set(99, 99, time.Minute)
	assert.Equal(t, 1, c.Len(), "overflow must drop whole map; only new entry survives")

	v, ok := c.Get(99)
	require.True(t, ok)
	assert.Equal(t, 99, v)

	// Replacing an existing key at capacity is not an overflow.
	for i := 100; i < 104; i++ {
		c.Set(i, i, time.Minute)
	}
	assert.Equal(t, 5, c.Len())
	c.Set(99, 1000, time.Minute)
	assert.Equal(t, 5, c.Len())
	value, ok := c.Get(99)
	require.True(t, ok)
	assert.Equal(t, 1000, value)
}

func TestTTLCache_BoundedSweepBeforeDropping(t *testing.T) {
	// Entries expiring before the cap is checked must be swept first so a
	// legitimately short-lived burst does not trigger a full wipe.
	c := NewTTLCache[int, int](5)
	for i := range 5 {
		c.Set(i, i, 10*time.Millisecond) // all expire quickly
	}
	time.Sleep(20 * time.Millisecond)

	// All 5 are expired; the sweep removes them before the cap check, so no
	// drop-whole-map occurs and the new entry is the only one.
	c.Set(99, 99, time.Minute)
	assert.Equal(t, 1, c.Len(), "sweep should clear expired before evaluating cap")
}

func TestTTLCache_PerCallTTL(t *testing.T) {
	// Two entries, different TTLs — only the short-lived one expires.
	c := NewTTLCache[string, int](0)
	c.Set("short", 1, 15*time.Millisecond)
	c.Set("long", 2, time.Minute)

	time.Sleep(30 * time.Millisecond)

	_, ok := c.Get("short")
	assert.False(t, ok)
	v, ok := c.Get("long")
	require.True(t, ok)
	assert.Equal(t, 2, v)
}

func TestTTLCache_ConcurrentAccess(t *testing.T) {
	// Run with -race to catch data races.
	c := NewTTLCache[int, int](50)
	const goroutines = 20
	const opsEach = 100

	var wg sync.WaitGroup
	wg.Add(goroutines)
	for g := range goroutines {
		go func(id int) {
			defer wg.Done()
			for i := range opsEach {
				key := (id*opsEach + i) % 30
				c.Set(key, key, 50*time.Millisecond)
				c.Get(key)
				c.Len()
			}
		}(g)
	}
	wg.Wait()
}
