package player

import (
	"context"
	"log/slog"
)

// hashNoiseFilter drops the torrent library's per-piece WARN "finished
// hashing piece" records when the hash actually passed (correct=true).
// Upstream's classic file IO reports a spurious io.ErrShortWrite on every
// successful verification (limitWriter vs os.File.WriteTo copying to EOF);
// the piece is still marked complete, so these warnings are pure noise.
// Anything else — including correct=false — passes through.
type hashNoiseFilter struct{ slog.Handler }

func (h hashNoiseFilter) Handle(ctx context.Context, r slog.Record) error {
	if r.Level == slog.LevelWarn && r.Message == "finished hashing piece" {
		correct := false
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "correct" && a.Value.Kind() == slog.KindBool {
				correct = a.Value.Bool()
				return false
			}
			return true
		})
		if correct {
			return nil
		}
	}
	return h.Handler.Handle(ctx, r)
}

func (h hashNoiseFilter) WithAttrs(attrs []slog.Attr) slog.Handler {
	return hashNoiseFilter{h.Handler.WithAttrs(attrs)}
}

func (h hashNoiseFilter) WithGroup(name string) slog.Handler {
	return hashNoiseFilter{h.Handler.WithGroup(name)}
}
