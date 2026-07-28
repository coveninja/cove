package utils

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRunScheduledRunsAfterStartupDelay(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ran := make(chan struct{}, 1)

	go RunScheduled(ctx, Schedule{
		StartupDelay: 5 * time.Millisecond,
		Interval:     time.Hour,
		Debounce:     time.Hour,
	}, nil, func() {
		ran <- struct{}{}
	})

	select {
	case <-ran:
	case <-time.After(time.Second):
		t.Fatal("scheduled startup run did not fire")
	}
}

func TestRunScheduledCoalescesNotifications(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	notify := make(chan struct{}, 3)
	var runs atomic.Int32

	go RunScheduled(ctx, Schedule{
		StartupDelay: time.Hour,
		Interval:     time.Hour,
		Debounce:     20 * time.Millisecond,
	}, notify, func() {
		runs.Add(1)
	})

	notify <- struct{}{}
	notify <- struct{}{}
	notify <- struct{}{}
	require.Eventually(t, func() bool { return runs.Load() == 1 }, time.Second, time.Millisecond)
	time.Sleep(50 * time.Millisecond)
	assert.Equal(t, int32(1), runs.Load())
}

func TestRunScheduledStopsWithContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		RunScheduled(ctx, Schedule{
			StartupDelay: time.Hour,
			Interval:     time.Hour,
			Debounce:     time.Hour,
		}, nil, func() {})
		close(done)
	}()

	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("scheduled loop did not stop after cancellation")
	}
}
