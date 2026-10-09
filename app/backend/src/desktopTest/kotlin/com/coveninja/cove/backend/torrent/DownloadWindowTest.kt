package com.coveninja.cove.backend.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic that stops a torrent downloading past the playhead.
 *
 * Getting it wrong is invisible from the outside — too small a window stalls playback, too large
 * a one silently downloads the whole episode again — so every case here was confirmed to fail
 * against a broken implementation before its comment was written.
 */
class DownloadWindowTest {
    // A second file in the torrent, so every conversion has to account for the offset rather
    // than treating file bytes as torrent bytes.
    private val pieceLength = 1_000L
    private val fileOffset = 10_000L
    private val fileSize = 20_000L
    private val numPieces = 40

    @Test
    fun `piece ranges are offset by the file's position in the torrent`() {
        // Byte 0 of this file is byte 10,000 of the torrent, so piece 10 — not piece 0. Fails if
        // the offset is dropped, which serves the right byte range out of the wrong file.
        assertEquals(PieceRange(10, 10), pieceRangeOf(fileOffset, pieceLength, 0, 999, numPieces))
        assertEquals(PieceRange(10, 11), pieceRangeOf(fileOffset, pieceLength, 0, 1_000, numPieces))
    }

    @Test
    fun `the file range covers exactly the pieces the file occupies`() {
        // 20,000 bytes from offset 10,000: pieces 10 through 29 inclusive. Fails on a division
        // that rounds the last piece up, which would park a piece belonging to the next file.
        assertEquals(PieceRange(10, 29), filePieceRange(fileOffset, fileSize, pieceLength, numPieces))
    }

    @Test
    fun `an unlimited allowance reproduces the whole file`() {
        // The escape hatch, and the behaviour every build before this one had. Fails if zero is
        // treated as a literal byte count, which would cap the download at the first piece.
        assertEquals(
            filePieceRange(fileOffset, fileSize, pieceLength, numPieces),
            downloadWindow(fileOffset, fileSize, pieceLength, cursor = 5_000, aheadBytes = 0, numPieces = numPieces),
        )
    }

    @Test
    fun `the window follows the cursor rather than the start of the file`() {
        val early = downloadWindow(fileOffset, fileSize, pieceLength, 0, 3_000, numPieces)
        val later = downloadWindow(fileOffset, fileSize, pieceLength, 9_000, 3_000, numPieces)
        // Fails if the window is measured from byte zero: it would never advance, and playback
        // would stall the moment the reader passed the opening allowance.
        assertEquals(PieceRange(10, 13), early)
        assertEquals(PieceRange(19, 22), later)
        assertTrue(later.first > early.last)
    }

    @Test
    fun `the window never runs past the end of the file`() {
        // Fails without the clamp: pieces beyond the file would be raised, downloading part of
        // whatever sits after it in a multi-file torrent.
        assertEquals(
            PieceRange(29, 29),
            downloadWindow(fileOffset, fileSize, pieceLength, 19_500, 50_000, numPieces),
        )
    }

    @Test
    fun `an allowance smaller than a piece yields exactly the piece being read`() {
        val window = downloadWindow(fileOffset, fileSize, pieceLength, 5_500, 1, numPieces)
        // Both halves matter. Nothing at all would hang the stream on a piece no one requested;
        // falling back to the whole file — the tempting shortcut for an allowance too small to
        // divide — would quietly undo the cap. Fails on either.
        assertEquals(PieceRange(15, 15), window)
        assertTrue(15 in window)
    }

    @Test
    fun `a cursor on the last byte of the file gives a window of one piece`() {
        // Fails if the end of the file is computed exclusively: the window would reach into the
        // piece after it, which belongs to the next file in the torrent.
        assertEquals(
            PieceRange(29, 29),
            downloadWindow(fileOffset, fileSize, pieceLength, fileSize - 1, 4_000, numPieces),
        )
    }

    @Test
    fun `a chunk ends at the piece boundary, in file coordinates`() {
        // Byte 0 of the file is byte 10,000 of the torrent, which is the start of piece 10, so
        // the first chunk runs to file byte 999. Fails if the file offset is not subtracted back
        // off: the chunk end would be a torrent coordinate, and the reader would seek past the
        // bytes it just waited for.
        assertEquals(999, pieceEndOffset(fileOffset, pieceLength, 0))
        assertEquals(999, pieceEndOffset(fileOffset, pieceLength, 999))
        assertEquals(1_999, pieceEndOffset(fileOffset, pieceLength, 1_000))
    }

    @Test
    fun `a file that does not start on a piece boundary still ends chunks on one`() {
        // The ordinary case in a multi-file torrent. From offset 10,500 the file's byte 0 sits
        // mid-piece, so the first chunk is the 500 bytes to the end of piece 10. Fails on
        // arithmetic that assumes the file begins where a piece does, which asks for a piece the
        // reader has already been given and stalls a chunk behind for the whole episode.
        assertEquals(499, pieceEndOffset(fileOffset = 10_500, pieceLength = pieceLength, offset = 0))
        assertEquals(1_499, pieceEndOffset(fileOffset = 10_500, pieceLength = pieceLength, offset = 500))
    }

    @Test
    fun `read-ahead asks for the pieces after the chunk being served`() {
        // Four pieces of allowance beyond piece 12. Fails if the range starts at lastPiece, which
        // re-requests the piece the reader is already waiting on and wastes the deadline on it.
        assertEquals(13..16, readAheadPieces(12, pieceLength, aheadBytes = 4_000, numPieces = numPieces))
    }

    @Test
    fun `read-ahead never rounds down to nothing and never runs off the end`() {
        // An allowance smaller than one piece still has to name a piece, or the next read starts
        // from cold — the stall this exists to prevent. Fails on a plain division.
        assertEquals(13..13, readAheadPieces(12, pieceLength, aheadBytes = 1, numPieces = numPieces))
        // At the last piece there is nothing ahead to want. Fails if the range is built before
        // the ceiling is checked, which asks libtorrent for a piece index it does not have.
        assertTrue(readAheadPieces(numPieces - 1, pieceLength, 4_000, numPieces).isEmpty())
        assertEquals(
            (numPieces - 1)..(numPieces - 1),
            readAheadPieces(numPieces - 2, pieceLength, 100_000, numPieces),
        )
    }

    @Test
    fun `a cursor past the end of the file clamps instead of throwing`() {
        // The engine rejects such a cursor before it gets here, so this guards the guard: with
        // the floor clamped only after it is used as one, coerceIn is handed a minimum above its
        // maximum and throws. A crash inside the piece picker would surface as a dead stream
        // with nothing in the log pointing back at a range that was never valid.
        assertEquals(
            PieceRange(29, 29),
            downloadWindow(fileOffset, fileSize, pieceLength, fileSize + 5_000, 1_000, numPieces),
        )
    }

    // ── windowTransition ─────────────────────────────────────────────────────

    @Test
    fun `a first window raises its own pieces and parks nothing`() {
        val window = downloadWindow(fileOffset, fileSize, pieceLength, 0, 3_000, numPieces)

        val transition = windowTransition(previous = null, next = window)

        // Fails if the no-previous case is treated as an empty range, which would raise
        // nothing at all and leave the opening pieces at whatever libtorrent last set.
        assertEquals(listOf(10, 11, 12, 13), transition.raise)
        assertEquals(emptyList(), transition.park)
    }

    @Test
    fun `advancing raises only the new ground and parks what is behind`() {
        val before = downloadWindow(fileOffset, fileSize, pieceLength, 0, 3_000, numPieces)
        val after = downloadWindow(fileOffset, fileSize, pieceLength, 5_000, 3_000, numPieces)

        val transition = windowTransition(previous = before, next = after)

        // 10..13 becomes 15..18. Fails if the overlap is re-issued (harmless but pointless) or
        // if the pieces left behind are not parked, which is the whole of what bounds the
        // download: a window that only ever grew was no window at all by the end of an episode.
        assertEquals(listOf(15, 16, 17, 18), transition.raise)
        assertEquals(listOf(10, 11, 12, 13), transition.park)
    }

    @Test
    fun `an overlapping move touches only the two ends`() {
        val before = downloadWindow(fileOffset, fileSize, pieceLength, 0, 3_000, numPieces)
        val after = downloadWindow(fileOffset, fileSize, pieceLength, 1_500, 3_000, numPieces)

        val transition = windowTransition(previous = before, next = after)

        // 10..13 becomes 11..14. Both halves are differences rather than whole ranges: fails if
        // raise is the new window outright, which re-issues a priority per piece per chunk read
        // for no change, and fails if park is the old window outright, which would park pieces
        // the reader is about to need — the one thing the window must never do.
        assertEquals(listOf(14), transition.raise)
        assertEquals(listOf(10), transition.park)
    }

    @Test
    fun `seeking backwards raises the ground in front of the new cursor`() {
        val ahead = downloadWindow(fileOffset, fileSize, pieceLength, 15_000, 3_000, numPieces)
        val back = downloadWindow(fileOffset, fileSize, pieceLength, 2_000, 3_000, numPieces)

        val transition = windowTransition(previous = ahead, next = back)

        // The case the old high-water mark could not express: everything here is behind the
        // furthest the window had reached, so it raised nothing and the only pieces ever asked
        // for after one backward seek were the chunk under the cursor and its read-ahead.
        assertEquals(listOf(12, 13, 14, 15), transition.raise)
        assertEquals(listOf(25, 26, 27, 28), transition.park)
    }

    @Test
    fun `the container index is never parked`() {
        val before = downloadWindow(fileOffset, fileSize, pieceLength, 0, 3_000, numPieces)
        val after = downloadWindow(fileOffset, fileSize, pieceLength, 9_000, 3_000, numPieces)

        val transition = windowTransition(previous = before, next = after, exempt = PieceRange(11, 12))

        // Fails without the exemption: the demuxer cannot open the file without the index at
        // its end, so parking those pieces because the reader moved past them means the next
        // load waits on bytes nothing is fetching.
        assertEquals(listOf(10, 13), transition.park)
    }

    // ── pieceWaitVerdict ─────────────────────────────────────────────────────

    @Test
    fun `a wait with data still arriving keeps its ceiling`() {
        // Fails if the stall clock is measured from the start of the wait rather than from the
        // last thing that arrived: a seek into a cold region of a working swarm would then be
        // abandoned after fifteen seconds, mid-response, and read as a dead stream.
        assertEquals(
            PieceWaitVerdict.Wait,
            pieceWaitVerdict(elapsedMillis = 40_000, millisSinceProgress = 400, ceilingMillis = 60_000),
        )
    }

    @Test
    fun `a swarm that has gone quiet gives up well inside the ceiling`() {
        // Fails if only the ceiling is checked: a source with no peers at all held the response
        // open for the full minute before anyone could fail over to another one.
        assertEquals(
            PieceWaitVerdict.GiveUpStalled,
            pieceWaitVerdict(elapsedMillis = 16_000, millisSinceProgress = PIECE_STALL_MILLIS, ceilingMillis = 60_000),
        )
    }

    @Test
    fun `pieces that never arrive still lose to the ceiling`() {
        // mpv has its own network timeout behind this one, and losing that race deliberately is
        // what keeps the explanation ours. Fails if the ceiling is dropped in favour of the
        // stall clock alone, which a trickle of unrelated pieces would keep resetting for ever.
        assertEquals(
            PieceWaitVerdict.GiveUpTimedOut,
            pieceWaitVerdict(elapsedMillis = 60_000, millisSinceProgress = 100, ceilingMillis = 60_000),
        )
    }
}
