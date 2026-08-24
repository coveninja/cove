package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Which pieces of one file libtorrent is asked for, in what order, and the wait for them.
 *
 * One instance per file being served. It owns every priority and deadline the reader issues,
 * which is the whole reason it exists as a shared class rather than a method on each engine:
 * the desktop engine grew deadlines, read-ahead and per-piece serving while the Android one
 * kept a first draft that asked for a megabyte at a time and waited, with no deadline and no
 * read-ahead, for whatever order the swarm felt like. On a phone a seek into a cold region
 * then took long enough that mpv's own network timeout expired first, and the viewer was told
 * the stream had stopped before the end — of a file that was still downloading perfectly well.
 *
 * [downloadAheadBytes] is read fresh on every use rather than captured, so a changed allowance
 * applies without restarting the app.
 */
internal class TorrentPieceScheduler(
    private val handle: TorrentHandle,
    info: TorrentInfo,
    private val fileIndex: Int,
    private val fileSize: Long,
    private val downloadAheadBytes: () -> Long,
) {
    private val pieceLength = info.pieceLength().toLong()
    private val fileOffset = info.files().fileOffset(fileIndex)
    private val numPieces = info.numPieces()

    /** Set once the pieces past the opening window have been parked. */
    private val parked = AtomicBoolean(false)

    /**
     * The furthest piece the window has been opened to, so an advancing reader only ever issues
     * priorities for ground it has not already covered. -1 before the first read.
     */
    @Volatile
    private var raisedTo: Int = -1

    /**
     * Puts the file in a state a reader can start from.
     *
     * The tail is re-asked on every open because it is cheap — pieces already held are skipped —
     * and a torrent that lost those pieces has to want them again. The parking is once only: it
     * is the reader's advance that releases pieces from it, and repeating it would re-park
     * ground [extendDownloadWindow] has already opened.
     */
    fun prepareForRead() {
        prioritizeIndexTail()
        if (parked.compareAndSet(false, true)) parkPiecesBeyondWindow()
    }

    /** The last byte, in file coordinates, of the piece holding [offset]. */
    fun pieceEndOffset(offset: Long): Long = pieceEndOffset(fileOffset, pieceLength, offset)

    /**
     * Asks for the pieces covering [start]..[endInclusive] and waits for them to land.
     *
     * Deadlines rather than priority alone: a deadline makes libtorrent ask its fastest peers
     * for the piece and order every other request around it, which is the difference between the
     * next chunk arriving now and it arriving once the swarm gets round to it. They are
     * staggered so the piece being read is always the most urgent one outstanding.
     */
    suspend fun awaitPieces(start: Long, endInclusive: Long, timeoutMillis: Long) {
        val firstPiece = pieceIndexOf(start)
        val lastPiece = pieceIndexOf(endInclusive)
        for (piece in firstPiece..lastPiece) {
            handle.piecePriority(piece, Priority.SEVEN)
            handle.setPieceDeadline(piece, DEADLINE_STEP_MILLIS * (piece - firstPiece))
        }
        // Read-ahead. Without it every chunk starts from cold: the bytes after the ones being
        // served are never asked for until the reader reaches them, so playback stalls once per
        // megabyte no matter how fast the swarm is.
        for (piece in readAheadPieces(lastPiece, pieceLength, READ_AHEAD_BYTES, numPieces)) {
            if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.SIX)
        }
        // Past the urgent read-ahead, out to whatever the viewer allows the download to run to.
        // This is the only thing that releases the pieces parked when the file was opened, so a
        // reader that stops advancing leaves the download stopped where it stood.
        extendDownloadWindow(start)

        val started = System.currentTimeMillis()
        var reportedAt = started
        withTimeout(timeoutMillis) {
            while ((firstPiece..lastPiece).any { !handle.havePiece(it) }) {
                // Silent while it is keeping up, which is the normal case: only a wait long
                // enough for the viewer to notice is worth a line, and then the peer count and
                // rate are what say whether it is stalled or merely slow.
                if (System.currentTimeMillis() - reportedAt >= PROGRESS_REPORT_MILLIS) {
                    reportedAt = System.currentTimeMillis()
                    val status = runCatching { handle.status(true) }.getOrNull()
                    logTorrent(
                        "pieces $firstPiece..$lastPiece still missing after " +
                            "${(System.currentTimeMillis() - started) / 1_000}s — " +
                            "${status?.numPeers() ?: 0} peers, ${(status?.downloadRate() ?: 0) / 1024} KiB/s",
                    )
                }
                delay(PIECE_POLL_MILLIS)
            }
        }
    }

    /**
     * Asks for the end of the file up front, alongside the beginning.
     *
     * An mp4 that was not written for streaming keeps its moov atom at the end, and a matroska
     * file keeps its cues there; either way the player seeks to the tail before it can decode a
     * single frame. Under sequential download those bytes are otherwise the *last* thing to
     * arrive, so the viewer waits out a download of the whole episode to see the first second of
     * it. Harmless when the whole file is being downloaded anyway, and mandatory once a window
     * stops it from being downloaded — otherwise the bytes the player needs first are the ones
     * parked furthest away.
     */
    private fun prioritizeIndexTail() {
        val tail = indexTailRange()
        for (piece in tail.first..tail.last) {
            if (handle.havePiece(piece)) continue
            handle.piecePriority(piece, Priority.SEVEN)
            handle.setPieceDeadline(piece, INDEX_TAIL_DEADLINE_MILLIS)
        }
    }

    /** The container index at the end of the file — wanted up front however small the window. */
    private fun indexTailRange(): PieceRange = pieceRangeOf(
        fileOffset = fileOffset,
        pieceLength = pieceLength,
        start = (fileSize - INDEX_TAIL_BYTES).coerceAtLeast(0),
        endInclusive = (fileSize - 1).coerceAtLeast(0),
        numPieces = numPieces,
    )

    /**
     * Tells libtorrent not to fetch anything past the opening window.
     *
     * The index tail is exempt: the demuxer cannot open the file without it, so it is wanted up
     * front no matter how tight the allowance. A window of zero means the viewer asked for the
     * whole file, and nothing is parked at all.
     */
    private fun parkPiecesBeyondWindow() {
        val ahead = downloadAheadBytes()
        if (ahead <= 0) return
        val file = filePieceRange(fileOffset, fileSize, pieceLength, numPieces)
        val window = downloadWindow(fileOffset, fileSize, pieceLength, 0, ahead, numPieces)
        val tail = indexTailRange()
        for (piece in (window.last + 1)..file.last) {
            if (piece in tail) continue
            if (handle.havePiece(piece)) continue
            handle.piecePriority(piece, Priority.IGNORE)
        }
    }

    /** Releases parked pieces as the reader advances, never re-treading ground already covered. */
    private fun extendDownloadWindow(cursor: Long) {
        val ahead = downloadAheadBytes()
        if (ahead <= 0) return
        val window = downloadWindow(
            fileOffset = fileOffset,
            fileSize = fileSize,
            pieceLength = pieceLength,
            cursor = cursor,
            aheadBytes = ahead,
            numPieces = numPieces,
        )
        for (piece in maxOf(raisedTo + 1, window.first)..window.last) {
            if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.NORMAL)
        }
        if (window.last > raisedTo) raisedTo = window.last
    }

    private fun pieceIndexOf(offset: Long): Int =
        ((fileOffset + offset) / pieceLength).toInt().coerceIn(0, numPieces - 1)
}

/** How much further ahead of the served chunk pieces are asked for. */
private const val READ_AHEAD_BYTES = 32L * 1024 * 1024

/** How much of the end of the file is fetched up front, for the container index. */
private const val INDEX_TAIL_BYTES = 2L * 1024 * 1024

/**
 * Deadline for the tail: behind the opening pieces, ahead of everything else. The player needs
 * both before it can start, and the opening is the larger read.
 */
private const val INDEX_TAIL_DEADLINE_MILLIS = 3_000

/** Spacing between the deadlines of consecutive pieces in the chunk being read. */
private const val DEADLINE_STEP_MILLIS = 50

private const val PIECE_POLL_MILLIS = 50L

// Matches both engines: plain stderr, no logging framework, one "Cove" prefix so the line is
// findable in logcat and in a terminal alike.
private fun logTorrent(message: String) = System.err.println("Cove torrent: $message")
