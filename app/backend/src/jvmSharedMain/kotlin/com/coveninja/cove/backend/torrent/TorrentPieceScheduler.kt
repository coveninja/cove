package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

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
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val pieceLength = info.pieceLength().toLong()
    private val fileOffset = info.files().fileOffset(fileIndex)
    private val numPieces = info.numPieces()

    /**
     * The window currently installed, or null before the first read.
     *
     * Guarded by [this] because two readers can overlap: every seek opens a new range request
     * while the previous response is still being written, and both move the same window.
     */
    private var appliedWindow: PieceRange? = null

    /** How many ranges are being served from this file right now. */
    private val liveReaders = AtomicInteger(0)

    /**
     * Puts the file in a state a reader can start from.
     *
     * The tail is re-asked on every open because it is cheap — pieces already held are skipped —
     * and a torrent that lost those pieces has to want them again.
     *
     * [prioritiesReset] says that the caller has just installed file priorities, which in
     * libtorrent recomputes every piece priority from them and so discards everything this
     * class has asked for. The window is then rebuilt from scratch; otherwise it is left where
     * the readers have moved it, since it is their advance that releases parked pieces.
     */
    fun prepareForRead(prioritiesReset: Boolean) {
        prioritizeIndexTail()
        synchronized(this) {
            if (!prioritiesReset && appliedWindow != null) return
            // Everything in the file is wanted at this instant, which is exactly what the file
            // priority says; parking back down to the opening window is the same transition a
            // reader makes when it advances, so it goes through the same code.
            appliedWindow = filePieceRange(fileOffset, fileSize, pieceLength, numPieces)
        }
        moveDownloadWindow(0)
    }

    /** The last byte, in file coordinates, of the piece holding [offset]. */
    fun pieceEndOffset(offset: Long): Long = pieceEndOffset(fileOffset, pieceLength, offset)

    /** One served byte range. Returned by [beginRead] and closed when that response ends. */
    fun beginRead(): Reader = Reader()

    /**
     * A single reader's claim on the swarm's urgency.
     *
     * It exists because deadlines outlive the request that set them. mpv ends one response and
     * opens another on every seek, and the abandoned one is typically still blocked waiting for
     * pieces at the old position — with those pieces marked time-critical, which is libtorrent's
     * strongest instruction and reorders every request around them. Several seeks in a row left
     * a pile of them pulling the swarm at positions nobody was reading, which is precisely when
     * the position somebody *was* reading failed to arrive in time.
     */
    inner class Reader : AutoCloseable {
        /** The chunk this reader currently holds deadlines on. */
        private var deadlined: PieceRange? = null

        init {
            liveReaders.incrementAndGet()
        }

        /**
         * Asks for the pieces covering [start]..[endInclusive] and waits for them to land.
         *
         * Deadlines rather than priority alone: a deadline makes libtorrent ask its fastest peers
         * for the piece and order every other request around it, which is the difference between
         * the next chunk arriving now and it arriving once the swarm gets round to it. They are
         * staggered so the piece being read is always the most urgent one outstanding, and the
         * previous chunk's are given back as this one is claimed.
         */
        suspend fun awaitPieces(start: Long, endInclusive: Long, timeoutMillis: Long) {
            val chunk = PieceRange(pieceIndexOf(start), pieceIndexOf(endInclusive))
            releaseDeadlinesOutside(chunk)
            for (piece in chunk.first..chunk.last) {
                handle.piecePriority(piece, Priority.SEVEN)
                handle.setPieceDeadline(piece, DEADLINE_STEP_MILLIS * (piece - chunk.first))
            }
            deadlined = chunk
            // Read-ahead. Without it every chunk starts from cold: the bytes after the ones being
            // served are never asked for until the reader reaches them, so playback stalls once per
            // megabyte no matter how fast the swarm is.
            for (piece in readAheadPieces(chunk.last, pieceLength, READ_AHEAD_BYTES, numPieces)) {
                if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.SIX)
            }
            // Past the urgent read-ahead, out to whatever the viewer allows the download to run
            // to. This is the only thing that releases the pieces parked when the file was opened,
            // so a reader that stops advancing leaves the download stopped where it stood.
            moveDownloadWindow(start)

            awaitChunk(chunk, timeoutMillis)
        }

        /** Gives back whatever urgency this reader still holds. */
        override fun close() {
            releaseDeadlinesOutside(null)
            liveReaders.decrementAndGet()
        }

        private fun releaseDeadlinesOutside(keep: PieceRange?) {
            val held = deadlined ?: return
            for (piece in held.first..held.last) {
                if (keep != null && piece in keep) continue
                runCatching { handle.resetPieceDeadline(piece) }
            }
            deadlined = keep
        }
    }

    /**
     * Waits for [chunk], saying out loud how it is going and giving up the way the swarm says to.
     *
     * Progress is read off the torrent rather than off the chunk: while one large piece is being
     * assembled nothing about the chunk changes for a minute at a time, and a wait that treated
     * that as a stall would abandon a download that was working.
     */
    private suspend fun awaitChunk(chunk: PieceRange, ceilingMillis: Long) {
        val started = nowMillis()
        var reportedAt = started
        var progressAt = started
        var sampledAt = started
        var missing = missingCount(chunk)
        var done = downloadedBytes()

        while (missing > 0) {
            val now = nowMillis()
            when (pieceWaitVerdict(now - started, now - progressAt, ceilingMillis)) {
                PieceWaitVerdict.Wait -> Unit
                PieceWaitVerdict.GiveUpStalled -> throw TorrentStalledException(
                    "no data for ${(now - progressAt) / 1_000}s while waiting for pieces " +
                        "${chunk.first}..${chunk.last}",
                )
                PieceWaitVerdict.GiveUpTimedOut -> throw TorrentStalledException(
                    "pieces ${chunk.first}..${chunk.last} did not arrive in " +
                        "${(now - started) / 1_000}s",
                )
            }
            delay(PIECE_POLL_MILLIS)

            // The chunk itself is re-checked on every poll, not on the sampling interval: a
            // chunk is a piece or two, so asking is cheap, and noticing late would add that
            // whole interval to every megabyte served — which is throughput, not latency.
            val remaining = missingCount(chunk)
            if (remaining < missing) progressAt = nowMillis()
            missing = remaining
            if (missing == 0) break

            // The torrent's own counter is the slower question, and the only one that can tell
            // a swarm delivering other pieces from a swarm delivering nothing at all.
            val sampled = nowMillis()
            if (sampled - sampledAt < PROGRESS_SAMPLE_MILLIS) continue
            sampledAt = sampled
            val downloaded = downloadedBytes()
            if (downloaded > done) progressAt = sampled
            done = downloaded
            // Silent while it is keeping up, which is the normal case: only a wait long
            // enough for the viewer to notice is worth a line, and then the peer count and
            // rate are what say whether it is stalled or merely slow.
            if (sampled - reportedAt >= PROGRESS_REPORT_MILLIS) {
                reportedAt = sampled
                val status = runCatching { handle.status(true) }.getOrNull()
                logTorrent(
                    "pieces ${chunk.first}..${chunk.last} still missing after " +
                        "${(sampled - started) / 1_000}s — " +
                        "${status?.numPeers() ?: 0} peers, ${(status?.downloadRate() ?: 0) / 1024} KiB/s",
                )
            }
        }
    }

    private fun missingCount(chunk: PieceRange): Int =
        (chunk.first..chunk.last).count { !handle.havePiece(it) }

    private fun downloadedBytes(): Long =
        runCatching { handle.status(false).totalDone() }.getOrDefault(0L)

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
     * Moves the download window to sit ahead of [cursor], raising what it now covers and parking
     * what it has left behind.
     *
     * A window of zero means the viewer asked for the whole file, and nothing is parked at all.
     */
    private fun moveDownloadWindow(cursor: Long) {
        val ahead = downloadAheadBytes()
        if (ahead <= 0) return
        val next = downloadWindow(
            fileOffset = fileOffset,
            fileSize = fileSize,
            pieceLength = pieceLength,
            cursor = cursor,
            aheadBytes = ahead,
            numPieces = numPieces,
        )
        val transition = synchronized(this) {
            val previous = appliedWindow
            if (previous == next) return
            appliedWindow = next
            windowTransition(previous = previous, next = next, exempt = indexTailRange())
        }
        for (piece in transition.raise) {
            if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.NORMAL)
        }
        // Parking is skipped while two ranges are being served from this file at once, because
        // then "behind the cursor" has no single meaning: the second reader is either the one
        // the viewer just seeked to — in which case the abandoned one is about to end anyway —
        // or genuinely another viewer over the LAN, and parking the pieces under them would
        // stall somebody who is watching. Raising still happens, so the window grows and stops
        // bounding the download for as long as that lasts, which is where it stood before.
        if (liveReaders.get() > 1) return
        for (piece in transition.park) {
            if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.IGNORE)
        }
    }

    private fun pieceIndexOf(offset: Long): Int =
        ((fileOffset + offset) / pieceLength).toInt().coerceIn(0, numPieces - 1)
}

/**
 * A read that gave up on the swarm rather than on the file.
 *
 * Distinct from a cancellation and from an I/O failure so the line the boundary logs says which
 * of the three happened; the response is already on the wire either way.
 */
internal class TorrentStalledException(message: String) : RuntimeException(message)

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

/** How often the torrent is asked whether anything arrived. Cheaper than the poll it rides on. */
private const val PROGRESS_SAMPLE_MILLIS = 500L


// Matches both engines: plain stderr, no logging framework, one "Cove" prefix so the line is
// findable in logcat and in a terminal alike.
private fun logTorrent(message: String) = System.err.println("Cove torrent: $message")
