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
}
