package player

import (
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type metadataTestFile struct {
	path string
	data string
}

// addMetadataTestTorrent adds a fully-described torrent without trackers or
// peers. That gives file selection and progress tests real torrent.File values
// while keeping the suite deterministic and entirely local.
func addMetadataTestTorrent(t *testing.T, p *Player, name string, files []metadataTestFile) (*torrent.Torrent, string) {
	t.Helper()

	dataByPath := make(map[string]string, len(files))
	info := metainfo.Info{Name: name, PieceLength: 16}
	for _, file := range files {
		path := strings.Split(file.path, "/")
		info.Files = append(info.Files, metainfo.FileInfo{
			Path:   path,
			Length: int64(len(file.data)),
		})
		dataByPath[file.path] = file.data
	}
	require.NoError(t, info.GeneratePieces(func(file metainfo.FileInfo) (io.ReadCloser, error) {
		return io.NopCloser(strings.NewReader(dataByPath[strings.Join(file.BestPath(), "/")])), nil
	}))
	infoBytes, err := bencode.Marshal(info)
	require.NoError(t, err)

	tor, added, err := p.client.AddTorrentSpec(torrent.TorrentSpecFromMetaInfo(&metainfo.MetaInfo{
		InfoBytes: infoBytes,
	}))
	require.NoError(t, err)
	assert.True(t, added)
	require.NotNil(t, tor.Info())

	hash := tor.InfoHash().HexString()
	now := time.Now()
	p.activeTorrentsMu.Lock()
	p.activeTorrents[hash] = &torrentState{
		torrent:   tor,
		lastCheck: now.Add(-time.Second),
		lastUsed:  now.Add(-time.Minute),
	}
	p.activeTorrentsMu.Unlock()
	return tor, hash
}

func TestTorrentMetadataFileSelection(t *testing.T) {
	p := newTestPlayer(t)
	tor, _ := addMetadataTestTorrent(t, p, "show-pack", []metadataTestFile{
		{path: "notes.nfo", data: "notes"},
		{path: "Show.Sample.mkv", data: strings.Repeat("s", 80)},
		{path: "Show.S01E01.mkv", data: strings.Repeat("1", 20)},
		{path: "Show.S01E02.mkv", data: strings.Repeat("2", 30)},
		{path: "Show.S01E03.mp4", data: strings.Repeat("3", 50)},
	})

	files := videoFiles(tor)
	require.Len(t, files, 3)
	assert.Equal(t, "Show.S01E01.mkv", files[0].DisplayPath())

	selected, err := selectFile(tor, intp(1), intp(2))
	require.NoError(t, err)
	assert.Equal(t, "Show.S01E02.mkv", selected.DisplayPath())

	largest, err := selectFile(tor, nil, nil)
	require.NoError(t, err)
	assert.Equal(t, "Show.S01E03.mp4", largest.DisplayPath())

	assert.Nil(t, selectFileByIdx(tor, -1))
	assert.Nil(t, selectFileByIdx(tor, len(tor.Files())))
	assert.Nil(t, selectFileByIdx(tor, 0), "non-video raw index should be rejected")
	assert.Equal(t, "Show.S01E02.mkv", selectFileByIdx(tor, 3).DisplayPath())

	noVideo, _ := addMetadataTestTorrent(t, p, "documents", []metadataTestFile{
		{path: "notes.nfo", data: "notes only"},
	})
	_, err = selectFile(noVideo, nil, nil)
	assert.ErrorContains(t, err, "no video files")
}

func TestGetTorrentFileReusesMetadataAndHonorsFileIdx(t *testing.T) {
	p := newTestPlayer(t)
	_, hash := addMetadataTestTorrent(t, p, "show-pack", []metadataTestFile{
		{path: "Show.S01E01.mkv", data: strings.Repeat("1", 20)},
		{path: "Show.S01E02.mkv", data: strings.Repeat("2", 30)},
	})

	selected, err := p.getTorrentFile(hash, intp(1), intp(1), intp(1))
	require.NoError(t, err)
	assert.Equal(t, "Show.S01E02.mkv", selected.DisplayPath(), "fileIdx takes priority over episode matching")

	selected, err = p.getTorrentFile(hash, intp(1), intp(2), intp(99))
	require.NoError(t, err)
	assert.Equal(t, "Show.S01E02.mkv", selected.DisplayPath(), "invalid fileIdx falls back to episode matching")
	assert.Error(t, func() error {
		_, err := p.getTorrentFile("not-a-hash", nil, nil, nil)
		return err
	}())
}

func TestPrefetchTorrentSwitchesSingleDownloadSlot(t *testing.T) {
	p := newTestPlayer(t)
	firstTorrent, firstHash := addMetadataTestTorrent(t, p, "first", []metadataTestFile{
		{path: "First.mkv", data: strings.Repeat("1", 32)},
	})
	_, secondHash := addMetadataTestTorrent(t, p, "second", []metadataTestFile{
		{path: "Second.mkv", data: strings.Repeat("2", 48)},
	})

	require.NoError(t, p.PrefetchTorrent(firstHash, nil, nil, nil))
	assert.Equal(t, torrent.PiecePriorityNormal, firstTorrent.Files()[0].Priority())
	assert.True(t, p.activeTorrents[firstHash].prefetched)

	require.NoError(t, p.PrefetchTorrent(secondHash, nil, nil, nil))
	assert.Equal(t, torrent.PiecePriorityNone, firstTorrent.Files()[0].Priority())
	assert.False(t, p.activeTorrents[firstHash].prefetched)
	assert.True(t, p.activeTorrents[secondHash].prefetched)
	assert.Equal(t, secondHash, p.prefetchHash)
	assert.Error(t, p.PrefetchTorrent("bad", nil, nil, nil))
}

func TestGetProgressUsesSelectedFileAndRefreshesOnlyActiveReaders(t *testing.T) {
	p := newTestPlayer(t)
	_, hash := addMetadataTestTorrent(t, p, "show-pack", []metadataTestFile{
		{path: "Show.S01E01.mkv", data: strings.Repeat("1", 20)},
		{path: "Show.S01E02.mkv", data: strings.Repeat("2", 30)},
	})

	p.activeTorrentsMu.Lock()
	p.activeTorrents[hash].readers = 1
	before := p.activeTorrents[hash].lastUsed
	p.activeTorrentsMu.Unlock()

	progress := p.GetProgress(hash, intp(1), intp(2), nil)
	assert.Equal(t, true, progress["found"])
	assert.Equal(t, int64(30), progress["totalBytes"])
	assert.Equal(t, int64(0), progress["downloadedBytes"])
	p.activeTorrentsMu.Lock()
	assert.True(t, p.activeTorrents[hash].lastUsed.After(before))
	p.activeTorrents[hash].readers = 0
	idleSince := time.Now().Add(-time.Hour)
	p.activeTorrents[hash].lastUsed = idleSince
	p.activeTorrentsMu.Unlock()

	progress = p.GetProgress(hash, intp(1), intp(1), intp(1))
	assert.Equal(t, int64(30), progress["totalBytes"], "fileIdx should override the requested episode")
	p.activeTorrentsMu.Lock()
	assert.Equal(t, idleSince, p.activeTorrents[hash].lastUsed)
	p.activeTorrentsMu.Unlock()

	progress = p.GetProgress(hash, intp(1), intp(1), intp(99))
	assert.Equal(t, int64(20), progress["totalBytes"], "invalid fileIdx should fall back to episode matching")
	assert.Equal(t, false, p.GetProgress("missing", nil, nil, nil)["found"])
}

func TestGetProgressWithoutMetadataOrVideoFiles(t *testing.T) {
	p := newTestPlayer(t)
	const pendingHash = "1111111111111111111111111111111111111111"
	addFakeHash(t, p, pendingHash, &torrentState{lastCheck: time.Now().Add(-time.Second)})
	pending := p.GetProgress(pendingHash, nil, nil, nil)
	assert.Equal(t, true, pending["found"])
	assert.Equal(t, int64(0), pending["totalBytes"])
	assert.Equal(t, "0 B/s", pending["speed"])

	_, documentsHash := addMetadataTestTorrent(t, p, "documents", []metadataTestFile{
		{path: "notes.nfo", data: "notes only"},
	})
	documents := p.GetProgress(documentsHash, nil, nil, nil)
	assert.Equal(t, int64(len("notes only")), documents["totalBytes"])
}

func TestGetProgressConcurrentCalls(t *testing.T) {
	p := newTestPlayer(t)
	_, hash := addMetadataTestTorrent(t, p, "movie", []metadataTestFile{
		{path: "Movie.mkv", data: strings.Repeat("m", 32)},
	})

	var wg sync.WaitGroup
	for range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			assert.Equal(t, true, p.GetProgress(hash, nil, nil, nil)["found"])
		}()
	}
	wg.Wait()
}
