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
