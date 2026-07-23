package player

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// capturingHandler records every slog.Record it receives.
type capturingHandler struct {
	records []slog.Record
}

func (h *capturingHandler) Enabled(_ context.Context, _ slog.Level) bool { return true }
func (h *capturingHandler) Handle(_ context.Context, r slog.Record) error {
	h.records = append(h.records, r)
	return nil
}
func (h *capturingHandler) WithAttrs([]slog.Attr) slog.Handler { return h }
func (h *capturingHandler) WithGroup(string) slog.Handler      { return h }

func TestHashNoiseFilter(t *testing.T) {
	cap := &capturingHandler{}
	filter := hashNoiseFilter{cap}

	ctx := context.Background()

	// A "finished hashing piece" WARN with correct=true must be silently dropped.
	dropped := slog.NewRecord(time.Time{}, slog.LevelWarn, "finished hashing piece", 0)
	dropped.AddAttrs(slog.Bool("correct", true), slog.String("err", "short write"))
	require.NoError(t, filter.Handle(ctx, dropped))
	assert.Len(t, cap.records, 0, "correct=true record should be dropped")

	// The same message with correct=false (a genuine hash failure) must pass through.
	failure := slog.NewRecord(time.Time{}, slog.LevelWarn, "finished hashing piece", 0)
	failure.AddAttrs(slog.Bool("correct", false))
	require.NoError(t, filter.Handle(ctx, failure))
	assert.Len(t, cap.records, 1, "correct=false record should pass through")

	// Unrelated WARN records must also pass through.
	other := slog.NewRecord(time.Time{}, slog.LevelWarn, "some other warning", 0)
	require.NoError(t, filter.Handle(ctx, other))
	assert.Len(t, cap.records, 2, "unrelated WARN should pass through")
}

// TestHashNoiseFilterWithAttrsAndWithGroup verifies that WithAttrs and
// WithGroup delegate to the inner Handler and wrap the result in a new
// hashNoiseFilter — the returned handler must still suppress hash noise.
func TestHashNoiseFilterWithAttrsAndWithGroup(t *testing.T) {
	cap := &capturingHandler{}
	filter := hashNoiseFilter{cap}

	// WithAttrs must return a hashNoiseFilter wrapping an enriched inner handler.
	attrFilter := filter.WithAttrs([]slog.Attr{slog.String("component", "torrent")})
	_, ok := attrFilter.(hashNoiseFilter)
	assert.True(t, ok, "WithAttrs must return a hashNoiseFilter")

	// WithGroup must return a hashNoiseFilter wrapping a grouped inner handler.
	groupFilter := filter.WithGroup("torrent")
	_, ok = groupFilter.(hashNoiseFilter)
	assert.True(t, ok, "WithGroup must return a hashNoiseFilter")

	// The wrapped filter must still drop hash noise — regression guard.
	ctx := context.Background()
	noisy := slog.NewRecord(time.Time{}, slog.LevelWarn, "finished hashing piece", 0)
	noisy.AddAttrs(slog.Bool("correct", true))
	require.NoError(t, attrFilter.Handle(ctx, noisy))
	assert.Empty(t, cap.records, "WithAttrs result must still suppress hash noise")
}
