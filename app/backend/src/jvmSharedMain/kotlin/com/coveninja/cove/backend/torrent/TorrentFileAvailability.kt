package com.coveninja.cove.backend.torrent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Waits for a torrent's file to exist on disk.
 *
 * libtorrent allocates sparsely: until it flushes the first piece covering a
 * file, nothing exists on disk — not the file, not even the directories above
 * it. `havePiece` also flips as soon as a piece verifies, which is a moment
 * before the disk thread has finished creating the file, so the read side needs
 * a wait of its own even once the pieces are in hand.
 *
 * [exists] is a parameter so the wait can be tested without a live session.
 */
internal suspend fun awaitTorrentFile(
    path: Path,
    timeoutMillis: Long,
    pollMillis: Long = 25,
    exists: (Path) -> Boolean = Files::isRegularFile,
) {
    if (exists(path)) return
    withTimeout(timeoutMillis) {
        while (!exists(path)) delay(pollMillis)
    }
}
