package utils

// DebouncedPersist coalesces rapid MarkDirty calls into a single
// AtomicWriteFile at the end of a quiet window, matching the pattern used
// independently by internal/library (markDirty / flushPending / Flush) and
// internal/activity (markDirty / flushPending / Flush). Both implementations
// were verified to be semantically identical before this helper was extracted:
//
//   - The timer is NOT reset when a second MarkDirty arrives while one is
//     already running — it fires on its original deadline. Only pendingData
//     (and pendingPath) are updated, so the write that eventually happens
//     carries the most-recently-staged bytes.
//
//   - Flush synchronously stops the pending timer and writes whatever data was
//     last staged, returning before the timer would have fired. Call it on
//     shutdown to avoid losing the last mutation in the quiet window.
//
// DebouncedPersist is zero-value usable — no constructor is needed. Embed it
// in a larger struct or declare a zero value.
//
// Callers that need a synchronous, unconditional write (the equivalent of
// library.writeNow / activity.writeNow) should call AtomicWriteFile directly;
// this type deliberately does not absorb those bypass paths.

import (
	"log"
	"sync"
	"time"
)

// DebouncedPersist coalesces many writes into one. Zero value is ready to use.
type DebouncedPersist struct {
	mu          sync.Mutex
	pendingData []byte
	pendingPath string
	timer       *time.Timer
}

// MarkDirty stages data for an atomic write to path and arms the debounce
// timer if it is not already running. Calling MarkDirty again before the
// timer fires updates pendingData to data but does not reset the deadline —
// the write happens at the original firing time carrying the latest bytes.
func (d *DebouncedPersist) MarkDirty(path string, data []byte, after time.Duration) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.pendingData = data
	d.pendingPath = path
	if d.timer == nil {
		d.timer = time.AfterFunc(after, d.flush)
	}
}

// Flush writes any pending data immediately, stopping the debounce timer if
// one is running. Safe to call when nothing is pending (no-op). Intended for
// graceful shutdown so the last staged write is not lost.
func (d *DebouncedPersist) Flush() {
	d.mu.Lock()
	if d.timer != nil {
		d.timer.Stop()
	}
	data, path := d.pendingData, d.pendingPath
	d.pendingData, d.pendingPath, d.timer = nil, "", nil
	d.mu.Unlock()

	if data == nil {
		return
	}
	if err := AtomicWriteFile(path, data, 0o644); err != nil {
		log.Printf("utils: flush of %s failed: %v", path, err)
	}
}

// flush is the AfterFunc callback. It reads and clears the staged data under
// the lock, then writes without holding it so AtomicWriteFile never blocks
// a concurrent MarkDirty.
func (d *DebouncedPersist) flush() {
	d.mu.Lock()
	data, path := d.pendingData, d.pendingPath
	d.pendingData, d.pendingPath, d.timer = nil, "", nil
	d.mu.Unlock()

	if data == nil {
		return
	}
	if err := AtomicWriteFile(path, data, 0o644); err != nil {
		log.Printf("utils: debounced write to %s failed: %v", path, err)
	}
}
