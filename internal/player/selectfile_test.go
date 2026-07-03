package player

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func intp(v int) *int { return &v }

func TestIsVideoFile(t *testing.T) {
	tests := []struct {
		path string
		want bool
	}{
		{"Show.S01E02.mkv", true},
		{"Show.S01E02.mp4", true},
		{"Show.S01E02.MKV", true}, // extension case-insensitive
		{"Show.S01E02.srt", false},
		{"Show.S01E02.nfo", false},
		{"poster.jpg", false},
		{"Show.S01E02.Sample.mkv", false},
		{"Show.S01E02.SAMPLE.mp4", false},
	}
	for _, tc := range tests {
		t.Run(tc.path, func(t *testing.T) {
			assert.Equal(t, tc.want, isVideoFile(tc.path))
		})
	}
}

func TestSelectFileIndex_MovieNoEpisode(t *testing.T) {
	files := []fileCandidate{
		{path: "Movie.720p.mkv", length: 1_000},
		{path: "Movie.1080p.mkv", length: 5_000},
	}
	idx, _ := selectFileIndex(files, nil, nil)
	assert.Equal(t, 1, idx) // largest
}

func TestSelectFileIndex_SxxExxPattern(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.S01E01.mkv", length: 1_000},
		{path: "Show.S01E02.mkv", length: 1_200},
		{path: "Show.S01E03.mkv", length: 900},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(2))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "matched S01E02")
}

func TestSelectFileIndex_SxxExxNoPadding(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.S1E1.mkv", length: 1_000},
		{path: "Show.S1E2.mkv", length: 1_200},
	}
	idx, _ := selectFileIndex(files, intp(1), intp(2))
	assert.Equal(t, 1, idx)
}

func TestSelectFileIndex_DoesNotConfuseSeason1WithSeason15(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.S15E02.mkv", length: 1_000},
		{path: "Show.S01E02.mkv", length: 1_200},
	}
	idx, _ := selectFileIndex(files, intp(1), intp(2))
	assert.Equal(t, 1, idx, "should pick S01E02, not S15E02")
}

func TestSelectFileIndex_NxNNPattern(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.1x01.mkv", length: 1_000},
		{path: "Show.1x02.mkv", length: 1_200},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(2))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "1x02")
}

func TestSelectFileIndex_EpisodeOnlyWhenUnambiguous(t *testing.T) {
	files := []fileCandidate{
		{path: "Random.Name.mkv", length: 1_000},
		{path: "Another.E05.Thing.mkv", length: 1_200},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "episode-only")
}

func TestSelectFileIndex_EpisodeOnlyAmbiguousFallsBackToLargest(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.E05.mkv", length: 1_000},
		{path: "Show.Ep05.mkv", length: 2_000}, // also matches E05-only pattern
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	// Two files match the episode-only tier — ambiguous, falls back to
	// largest overall rather than guessing.
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "no episode match")
}

func TestSelectFileIndex_MultipleMatchesAtSameTierPicksLargest(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.S01E02.mkv", length: 1_000},
		{path: "Show.S01E02.Extended.mkv", length: 3_000},
	}
	idx, _ := selectFileIndex(files, intp(1), intp(2))
	assert.Equal(t, 1, idx)
}

func TestSelectFileIndex_NoMatchFallsBackToLargest(t *testing.T) {
	files := []fileCandidate{
		{path: "Show.S01E01.mkv", length: 1_000},
		{path: "RandomFile.mkv", length: 5_000},
	}
	idx, reason := selectFileIndex(files, intp(9), intp(9))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "no episode match")
}

func TestSelectFileIndex_EpisodeWord(t *testing.T) {
	files := []fileCandidate{
		{path: "Random.mkv", length: 1_000},
		{path: "Show.Episode 5.mkv", length: 1_200},
	}
	idx, _ := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 1, idx)
}
