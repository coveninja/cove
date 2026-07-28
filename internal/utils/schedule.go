package utils

import (
	"context"
	"time"
)

// Schedule describes a background loop with an initial run, a steady cadence,
// and an optional trailing-edge notification debounce.
type Schedule struct {
	StartupDelay time.Duration
	Interval     time.Duration
	Debounce     time.Duration
}

// RunScheduled serializes startup, periodic, and debounced notification
// triggers through one loop. The callback never overlaps itself.
func RunScheduled(
	ctx context.Context,
	schedule Schedule,
	notify <-chan struct{},
	run func(),
) {
	startup := time.NewTimer(schedule.StartupDelay)
	defer startup.Stop()

	ticker := time.NewTicker(schedule.Interval)
	defer ticker.Stop()

	debounce := time.NewTimer(time.Hour)
	defer debounce.Stop()
	if !debounce.Stop() {
		<-debounce.C
	}

	resetDebounce := func() {
		if !debounce.Stop() {
			select {
			case <-debounce.C:
			default:
			}
		}
		debounce.Reset(schedule.Debounce)
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-startup.C:
			run()
		case <-ticker.C:
			run()
		case <-notify:
			resetDebounce()
		case <-debounce.C:
			run()
		}
	}
}
