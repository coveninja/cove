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
