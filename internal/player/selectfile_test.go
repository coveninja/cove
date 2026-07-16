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

func TestSelectFileIndex_AnimeBareEpisode(t *testing.T) {
	files := []fileCandidate{
		{path: "[Flugel] Kaguya-sama - 04 [BD 1080p][80AC7B2E].mkv", length: 1_000},
		{path: "[Flugel] Kaguya-sama - 05 [BD 1080p][80AC7B2E].mkv", length: 1_200},
		{path: "[Flugel] Kaguya-sama - 06 [BD 1080p][80AC7B2E].mkv", length: 1_100},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "anime-style E05")
}

func TestSelectFileIndex_AnimeBareEpisodeVersionSuffix(t *testing.T) {
	// " - 05v2 " version suffix must still match episode 5.
	files := []fileCandidate{
		{path: "[Group] Show - 04 [1080p].mkv", length: 1_000},
		{path: "[Group] Show - 05v2 [1080p].mkv", length: 1_200},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "anime-style E05")
}

func TestSelectFileIndex_AnimeBareEpisodeUnpadded(t *testing.T) {
	// Unpadded " - 5 " (no leading zero) must also match.
	files := []fileCandidate{
		{path: "[Group] Show - 4 [1080p].mkv", length: 1_000},
		{path: "[Group] Show - 5 [1080p].mkv", length: 1_200},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "anime-style E05")
}

func TestSelectFileIndex_AnimeAmbiguousFallsBackToLargest(t *testing.T) {
	// Two files both contain " - 05 " — ambiguous, must fall back to largest.
	files := []fileCandidate{
		{path: "[Group] Show - 05 [720p].mkv", length: 1_000},
		{path: "[Group] Show - 05 [1080p].mkv", length: 2_000},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "no episode match")
}

func TestSelectFileIndex_AnimeDoesNotMatchEpisode5InEpisode105(t *testing.T) {
	// " - 105 " must NOT match episode 5 (0*5 would need to match starting
	// after the " - " boundary, but "105" starts with 1 — the regex correctly
	// requires the digits immediately after " - " to be 0*5).
	files := []fileCandidate{
		{path: "[Group] Show - 105 [1080p].mkv", length: 2_000},
		{path: "[Group] Show - 205 [1080p].mkv", length: 1_500},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(5))
	// Neither matches — falls back to largest.
	assert.Equal(t, 0, idx)
	assert.Contains(t, reason, "no episode match")
}

func TestSelectFileIndex_AnimeCRCHashDoesNotMatchEpisode80(t *testing.T) {
	// CRC hash like [80AC7B2E] must not be mistaken for episode 80: the hash
	// is inside brackets with no preceding " - " boundary.
	files := []fileCandidate{
		{path: "[Flugel] Kaguya-sama - 05 [BD 1080p][80AC7B2E].mkv", length: 1_000},
		{path: "[Flugel] Kaguya-sama - 06 [BD 1080p][DEADBEEF].mkv", length: 2_000},
	}
	idx, reason := selectFileIndex(files, intp(1), intp(80))
	// Nothing should match episode 80; falls back to largest.
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "no episode match")
}

func TestSelectFileIndex_AnimeNotUsedForSeason2(t *testing.T) {
	// Bare " - 05 " with no season marker in the path is not trusted for
	// season 2 — unmarked paths are season-1 only. Falls back to largest.
	files := []fileCandidate{
		{path: "[Group] Show - 04 [1080p].mkv", length: 1_000},
		{path: "[Group] Show - 05 [1080p].mkv", length: 1_200},
	}
	idx, reason := selectFileIndex(files, intp(2), intp(5))
	assert.Equal(t, 1, idx)
	assert.Contains(t, reason, "no episode match")
}

func TestSelectFileIndex_AnimeTimeMultiSeasonBatch(t *testing.T) {
	files := []fileCandidate{
		// idx 0 — S1E5 (no season marker)
		{path: "[Anime Time] Kaguya Sama - Love Is War/[Anime Time] Kaguya Sama - Love Is War - 05.mkv", length: 500_000},
		// idx 1 — S2E5 (Season 02 / Season 2 markers); larger so wrong picks are detectable
		{path: "[Anime Time] Kaguya Sama - Love Is War Season 02/[Anime Time] Kaguya Sama - Love Is War Season 2 - 05.mkv", length: 600_000},
		// idx 2 — S3E5
		{path: "[Anime Time] Kaguya Sama - Love Is War Season 03/[Anime Time] Kaguya Sama - Love Is War Season 3 - 05.mkv", length: 400_000},
		// idx 3 — S1E4 (filler, ensures episode matching is specific)
		{path: "[Anime Time] Kaguya Sama - Love Is War/[Anime Time] Kaguya Sama - Love Is War - 04.mkv", length: 500_000},
	}
	idxS1, reasonS1 := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 0, idxS1, "S1E5 must pick the unmarked season-1 file, not the larger Season-2 file")
	assert.Contains(t, reasonS1, "anime-style")

	idxS2, reasonS2 := selectFileIndex(files, intp(2), intp(5))
	assert.Equal(t, 1, idxS2, "S2E5 must pick the Season-2-marked file")
	assert.Contains(t, reasonS2, "anime-style")
}

func TestSelectFileIndex_AnimeRootFolderMarkerTrap(t *testing.T) {
	// A root folder like "Show (Season 1+2)" contains "Season 1" in its name,
	// which would taint every file under it if we used the *first* marker.
	// The *last* (deepest) marker is what identifies the actual season.
	files := []fileCandidate{
		// idx 0 — S1E5: last marker = "Season 1" (the mid-path folder)
		{path: "Show (Season 1+2)/Season 1/Show - 05.mkv", length: 1_000},
		// idx 1 — S2E5: last marker = "Season 2"; larger so wrong picks are detectable
		{path: "Show (Season 1+2)/Season 2/Show - 05.mkv", length: 2_000},
	}
	idxS2, reasonS2 := selectFileIndex(files, intp(2), intp(5))
	assert.Equal(t, 1, idxS2, "S2E5 must pick the file whose last marker is Season 2")
	assert.Contains(t, reasonS2, "anime-style")

	idxS1, reasonS1 := selectFileIndex(files, intp(1), intp(5))
	assert.Equal(t, 0, idxS1, "S1E5 must pick the file whose last marker is Season 1")
	assert.Contains(t, reasonS1, "anime-style")
}
