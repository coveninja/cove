package com.coveninja.cove.backend.torrent

/** An inclusive range of piece indices. */
data class PieceRange(val first: Int, val last: Int) {
    operator fun contains(piece: Int): Boolean = piece in first..last
}

/**
 * The pieces holding bytes [start]..[endInclusive] of a file that begins at [fileOffset].
 *
 * Torrent pieces are indexed across the whole torrent, not per file, so every conversion has to
 * add the file's offset before dividing — a multi-file torrent otherwise reads the right byte
 * range out of the wrong episode.
 */
fun pieceRangeOf(
    fileOffset: Long,
    pieceLength: Long,
    start: Long,
    endInclusive: Long,
    numPieces: Int,
): PieceRange {
    require(pieceLength > 0) { "piece length must be positive" }
    val ceiling = (numPieces - 1).coerceAtLeast(0)
    val first = ((fileOffset + start.coerceAtLeast(0)) / pieceLength).toInt().coerceIn(0, ceiling)
    val last = ((fileOffset + endInclusive.coerceAtLeast(0)) / pieceLength).toInt().coerceIn(first, ceiling)
    return PieceRange(first, last)
}

/** Every piece the selected file occupies. */
fun filePieceRange(
    fileOffset: Long,
    fileSize: Long,
    pieceLength: Long,
    numPieces: Int,
): PieceRange = pieceRangeOf(
    fileOffset = fileOffset,
    pieceLength = pieceLength,
    start = 0,
    endInclusive = (fileSize - 1).coerceAtLeast(0),
    numPieces = numPieces,
)

/**
 * The last byte, in file coordinates, of the piece holding [offset].
 *
 * A reader serves up to here and no further, so the bytes move as each piece lands rather than
 * once a whole fixed-size buffer is complete. With a large piece length those are very different
 * waits: the buffer's worth may be several pieces, and holding all of them back means the player
 * sees nothing until the slowest arrives.
 */
fun pieceEndOffset(fileOffset: Long, pieceLength: Long, offset: Long): Long {
    require(pieceLength > 0) { "piece length must be positive" }
    val pieceIndex = (fileOffset + offset.coerceAtLeast(0)) / pieceLength
    return (pieceIndex + 1) * pieceLength - 1 - fileOffset
}

/**
 * The pieces to ask for beyond the chunk being served, so the next read is not cold.
 *
 * Empty when the chunk already reaches the last piece — there is nothing ahead to want. Always
 * at least one piece otherwise, however small [aheadBytes] is against the piece length: a
 * read-ahead rounded down to nothing is the stall it exists to prevent.
 */
fun readAheadPieces(lastPiece: Int, pieceLength: Long, aheadBytes: Long, numPieces: Int): IntRange {
    require(pieceLength > 0) { "piece length must be positive" }
    val ceiling = numPieces - 1
    if (lastPiece >= ceiling) return IntRange.EMPTY
    val count = (aheadBytes / pieceLength).toInt().coerceAtLeast(1)
    return (lastPiece + 1)..(lastPiece + count).coerceAtMost(ceiling)
}

/**
 * How far ahead of the player the torrent is allowed to run.
 *
 * Without a bound, libtorrent downloads the entire file: the selected file is set to a normal
 * priority and sequential order does the rest, so quitting five minutes into an episode still
 * pulls the remaining forty in the background. Bounding the window means the download advances
 * only as the reader does, and stops on its own the moment nobody is reading.
 *
 * [aheadBytes] of zero restores the unbounded behaviour, and the window never falls short of the
 * piece under the cursor — an allowance smaller than one piece still has to fetch the piece being
 * read, or playback would wait on a piece nothing ever asked for.
 */
fun downloadWindow(
    fileOffset: Long,
    fileSize: Long,
    pieceLength: Long,
    cursor: Long,
    aheadBytes: Long,
    numPieces: Int,
): PieceRange {
    val file = filePieceRange(fileOffset, fileSize, pieceLength, numPieces)
    if (aheadBytes <= 0) return file
    // Clamped before it is used as the floor, not after. A cursor past the end of the file is
    // rejected long before it reaches here, but a floor above the ceiling would throw out of
    // coerceIn rather than return a small window — a crash where a clamp was intended.
    val first = pieceRangeOf(fileOffset, pieceLength, cursor, cursor, numPieces).first
        .coerceAtMost(file.last)
    val ahead = ((fileOffset + cursor + aheadBytes) / pieceLength).toInt()
    return PieceRange(first = first, last = ahead.coerceIn(first, file.last))
}

/** Pieces to raise and pieces to park when the download window moves. */
data class WindowTransition(val raise: List<Int>, val park: List<Int>)

/**
 * The difference between two download windows, as the two sets of pieces to act on.
 *
 * Both halves matter and only one of them used to happen. The old code tracked the furthest
 * piece the window had ever reached and raised anything past it, which is right while a reader
 * advances and wrong the moment it jumps: a seek forward left the pieces in between parked for
 * ever, and a seek *back* raised nothing at all, because the new window was behind the high
 * water mark — so after one backward seek the only pieces ever asked for were the chunk under
 * the cursor and its read-ahead, however much the viewer had allowed the download to run to.
 *
 * Parking what has fallen out is the other half of the same accounting: the window exists to
 * keep the download next to the player, and a window that only ever grew was no window at all
 * by the end of an episode.
 *
 * [exempt] is never parked — the container index at the end of the file, which the demuxer
 * needs whatever the allowance.
 */
fun windowTransition(
    previous: PieceRange?,
    next: PieceRange,
    exempt: PieceRange? = null,
): WindowTransition {
    val raise = (next.first..next.last).filter { previous == null || it !in previous }
    val park = if (previous == null) {
        emptyList()
    } else {
        (previous.first..previous.last).filter { it !in next && (exempt == null || it !in exempt) }
    }
    return WindowTransition(raise = raise, park = park)
}

/** What a reader waiting on pieces should do next. */
enum class PieceWaitVerdict { Wait, GiveUpStalled, GiveUpTimedOut }

/**
 * Whether to keep waiting for the pieces under a read, and if not, which way it failed.
 *
 * A flat deadline answered the wrong question. Every one of these waits happens after the 206
 * has gone out, so giving up truncates the response and the viewer is told the stream stopped
 * before the end — and a seek into a cold region of a working swarm hit that deadline routinely,
 * because the piece it needs is the one piece the swarm has not got round to. Meanwhile a source
 * with no peers at all sat there for the whole minute before saying so.
 *
 * So the clock that matters is the one since anything last arrived: a download that is moving
 * keeps its ceiling, and one that is not fails fast enough for the session to fail over to
 * another source while the viewer is still waiting. The ceiling stays because mpv has its own
 * network timeout behind this one, and losing that race deliberately is what keeps the
 * explanation ours.
 */
fun pieceWaitVerdict(
    elapsedMillis: Long,
    millisSinceProgress: Long,
    ceilingMillis: Long,
    stalledMillis: Long = PIECE_STALL_MILLIS,
): PieceWaitVerdict = when {
    millisSinceProgress >= stalledMillis -> PieceWaitVerdict.GiveUpStalled
    elapsedMillis >= ceilingMillis -> PieceWaitVerdict.GiveUpTimedOut
    else -> PieceWaitVerdict.Wait
}

/**
 * How long nothing at all may arrive before a read gives up.
 *
 * Short enough that the failover to another source happens while the viewer is still watching
 * the spinner, long enough to cover a tracker announce and a round of handshakes on a swarm
 * that is merely slow to start.
 */
const val PIECE_STALL_MILLIS = 15_000L
