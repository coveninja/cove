package utils

// TTLCache is a mutex-guarded, generic in-memory cache with per-entry expiry.
// It replaces five hand-rolled caches in internal/tmdb and internal/player
// that shared the same shape: a map from a comparable key to a value and
// expiry, plus inline stale-entry cleanup without a background goroutine.
//
// Eviction on overflow: three original caches are unbounded (they rely
// entirely on the expiry sweep). Two — tmdb's detailsCache (cap 2 000)
// and episodesCache (cap 500) — also drop the whole map when the post-sweep
// count still exceeds the cap, accepting a cold-start penalty in exchange for
// a firm memory bound. TTLCache supports both modes:
//   - maxEntries <= 0: unbounded; only expired entries are removed on Set.
//   - maxEntries > 0: after the expiry sweep, if len > maxEntries the entire
//     map is replaced with a fresh one before the new entry is stored.
//
// Dropping the whole map rather than evicting the least-recently-used entry
// is deliberately simple: LRU requires per-entry bookkeeping that the original
// caches never had, and a full drop is safe because all values are
// reconstructable from upstream APIs.
//
// Per-call TTL (not baked into the struct) because some callers use a shorter
// TTL for empty/negative results than for hits — tmdb's qualityCache and the
// addon stream caches both differentiate hit vs empty TTLs.

import (
	"sync"
	"time"
)

type cacheEntry[V any] struct {
	value   V
	expires time.Time
}

// TTLCache is safe for concurrent use by multiple goroutines.
type TTLCache[K comparable, V any] struct {
	mu         sync.Mutex
	entries    map[K]cacheEntry[V]
	maxEntries int // 0 means unbounded
}

// NewTTLCache returns a ready-to-use cache. Pass maxEntries <= 0 for an
// unbounded cache; a positive value enables the drop-whole-map overflow guard.
func NewTTLCache[K comparable, V any](maxEntries int) *TTLCache[K, V] {
	return &TTLCache[K, V]{
		entries:    make(map[K]cacheEntry[V]),
		maxEntries: maxEntries,
	}
}

// Get returns the cached value for key if it exists and has not expired.
// Returns the zero value of V and false otherwise.
func (c *TTLCache[K, V]) Get(key K) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[key]
	if !ok {
		var zero V
		return zero, false
	}
	if time.Now().After(e.expires) {
		delete(c.entries, key)
		var zero V
		return zero, false
	}
	return e.value, true
}

// Set stores value under key with the given TTL, sweeping expired entries first.
// If maxEntries > 0 and the post-sweep map is still over capacity, the entire
// map is dropped before the new entry is written so the bound is always honoured.
func (c *TTLCache[K, V]) Set(key K, value V, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for k, v := range c.entries {
		if now.After(v.expires) {
			delete(c.entries, k)
		}
	}
	if c.maxEntries > 0 {
		if _, replacing := c.entries[key]; !replacing && len(c.entries) >= c.maxEntries {
			c.entries = make(map[K]cacheEntry[V])
		}
	}
	c.entries[key] = cacheEntry[V]{value: value, expires: now.Add(ttl)}
}

// Len returns the total number of entries in the map, including any that have
// expired but not yet been swept. Intended for tests.
func (c *TTLCache[K, V]) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.entries)
}
