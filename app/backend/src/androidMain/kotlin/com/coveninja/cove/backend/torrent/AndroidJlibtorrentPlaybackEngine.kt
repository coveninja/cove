package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.coveninja.cove.backend.storage.TorrentCacheJournal
import com.coveninja.cove.shared.data.TorrentCachePolicy
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Android native packaging for the same streaming engine used by desktop. */
internal class AndroidJlibtorrentPlaybackEngine(
    private val downloadDirectory: Path,
    private val lifecycle: TorrentCacheLifecycle,
    private val metadataTimeoutSeconds: Int = 45,
    private val pieceTimeoutMillis: Long = 120_000,
    /** Read fresh each time, so a changed allowance applies without restarting the app. */
    private val policy: () -> TorrentCachePolicy = { TorrentCachePolicy() },
    private val journal: TorrentCacheJournal? = null,
) : TorrentPlaybackEngine {
    private val startMutex = Mutex()
    private val torrentMutex = Mutex()
    private var manager: SessionManager? = null
    private val torrents = ConcurrentHashMap<String, ManagedTorrent>()
    private val resources = ConcurrentHashMap<String, ManagedResource>()

    override suspend fun open(
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ): TorrentResource = lifecycle.withUse(hash.lowercase()) {
        openWithinLease(hash, season, episode, fileIndex)
    }

    private suspend fun openWithinLease(
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ): TorrentResource = withContext(Dispatchers.IO) {
        require(Regex("^[A-Fa-f0-9]{40}$").matches(hash)) { "invalid torrent info hash" }
        require(season == null || season >= 0) { "season must not be negative" }
        require(episode == null || episode > 0) { "episode must be positive" }
        val canonical = hash.lowercase()
        val torrent = torrents[canonical] ?: torrentMutex.withLock {
            torrents[canonical] ?: loadTorrent(canonical).also { torrents[canonical] = it }
        }
        val selected = selectTorrentFile(torrent.files, season, episode, fileIndex)
        torrent.handle.prioritizeFiles(
            Priority.array(Priority.IGNORE, torrent.info.numFiles()).also {
                it[selected.index] = Priority.NORMAL
            },
        )
        val path = torrent.saveDirectory.resolve(selected.path).normalize()
        require(path.startsWith(torrent.saveDirectory)) { "torrent file escaped download directory" }
        val id = "$canonical:${selected.index}"
        // Reused across range requests: the resource remembers how far the download window has
        // been opened, and the player asks for a new range on every seek.
        val managed = resources.computeIfAbsent(id) { ManagedResource(torrent, selected, path) }
        if (managed.parked.compareAndSet(false, true)) {
            prioritizeIndexTail(managed)
            parkPiecesBeyondWindow(managed)
        }
        TorrentResource(id, path.fileName.toString(), selected.size, contentType(path.fileName.toString()))
    }

    override suspend fun write(
        resource: TorrentResource,
        start: Long,
        endInclusive: Long,
        output: ByteWriteChannel,
    ) = lifecycle.withUse(resource.id.substringBefore(':').lowercase()) {
        writeWithinLease(resource, start, endInclusive, output)
    }

    private suspend fun writeWithinLease(
        resource: TorrentResource,
        start: Long,
        endInclusive: Long,
        output: ByteWriteChannel,
    ) = withContext(Dispatchers.IO) {
        val managed = resources[resource.id] ?: error("torrent resource is no longer available")
        require(start in 0 until managed.file.size && endInclusive in start until managed.file.size) {
            "invalid torrent byte range"
        }
        var cursor = start
        val buffer = ByteArray(1024 * 1024)
        journal?.touch(managed.torrent.hash)
        // Kept as a local session guard as well as the cross-component lifecycle lease. The
        // latter spans Ktor's delayed producer and the eventual filesystem deletion.
        managed.torrent.readers.incrementAndGet()
        try {
            // libtorrent allocates sparsely: until it flushes the first piece covering
            // this file, nothing exists on disk — not the file, not even the directory
            // holding it. Opening before that wait throws FileNotFoundException for
            // every freshly added torrent, and because respondBytesWriter has already
            // sent the 206 by then, the player sees a stream that dies at byte zero.
            awaitPieces(managed, cursor, min(endInclusive, cursor + buffer.size - 1))
            awaitTorrentFile(managed.path, pieceTimeoutMillis)
            RandomAccessFile(managed.path.toFile(), "r").use { input ->
                while (cursor <= endInclusive) {
                    val chunkEnd = min(endInclusive, cursor + buffer.size - 1)
                    awaitPieces(managed, cursor, chunkEnd)
                    input.seek(cursor)
                    var remaining = (chunkEnd - cursor + 1).toInt()
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, min(buffer.size, remaining))
                        check(count > 0) { "torrent file ended before advertised size" }
                        output.writeFully(buffer, 0, count)
                        cursor += count
                        remaining -= count
                    }
                }
            }
        } finally {
            managed.torrent.readers.decrementAndGet()
        }
    }

    override suspend fun stream(
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
        start: Long,
        endInclusive: Long,
        output: ByteWriteChannel,
    ) {
        lifecycle.withUse(hash.lowercase()) {
            // Ktor invokes its response producer later. Reopening here makes a deletion that won
            // the gap harmless, while the outer lease keeps this new resource alive through the
            // complete write.
            val resource = openWithinLease(hash, season, episode, fileIndex)
            writeWithinLease(resource, start, endInclusive, output)
        }
    }

    override fun progress(hash: String): TorrentProgress? {
        val torrent = torrents[hash.lowercase()] ?: return null
        val resource = resources.values.firstOrNull { it.torrent === torrent } ?: return null
        val status = torrent.handle.status(true)
        val fileProgress = torrent.handle.fileProgress().getOrNull(resource.file.index) ?: 0L
        return TorrentProgress(
            hash = hash.lowercase(),
            fileIndex = resource.file.index,
            downloadedBytes = fileProgress.coerceAtMost(resource.file.size),
            totalBytes = resource.file.size,
            downloadRate = status.downloadRate(),
            peers = status.numPeers(),
            complete = fileProgress >= resource.file.size,
        )
    }

    override fun activeHashes(): Set<String> = lifecycle.activeHashes()

    override fun release(hash: String): Boolean {
        val canonical = hash.lowercase()
        val torrent = torrents[canonical] ?: return true
        if (canonical in lifecycle.activeHashes()) return false
        if (torrent.readers.get() > 0) return false
        // The cache service holds the lifecycle's exclusive deletion gate through this removal
        // and the filesystem delete. A new open waits at that gate, then re-adds a clean torrent.
        torrents.remove(canonical, torrent)
        if (torrent.readers.get() > 0) {
            torrents[canonical] = torrent
            return false
        }
        resources.entries.removeIf { it.value.torrent === torrent }
        runCatching { manager?.remove(torrent.handle) }
        return true
    }

    override fun close() {
        journal?.flush()
        resources.clear()
        torrents.clear()
        manager?.stop()
        manager = null
    }

    private suspend fun loadTorrent(hash: String): ManagedTorrent {
        val session = session()
        val torrentDirectory = downloadDirectory.resolve(hash).toAbsolutePath().normalize()
        val metadataDirectory = downloadDirectory.resolve("metadata").toAbsolutePath().normalize()
        Files.createDirectories(torrentDirectory)
        Files.createDirectories(metadataDirectory)
        val metadata = session.fetchMagnet(
            "magnet:?xt=urn:btih:$hash",
            metadataTimeoutSeconds,
            metadataDirectory.toFile(),
        ) ?: throw IllegalStateException("timed out fetching torrent metadata")
        val info = TorrentInfo(metadata)
        session.download(info, torrentDirectory.toFile())
        val handle = withTimeout(10_000) {
            var found: TorrentHandle? = null
            while (found == null) {
                found = session.find(info)
                if (found == null) delay(50)
            }
            found
        }
        val storage = info.files()
        val files = (0 until storage.numFiles()).map { index ->
            TorrentFile(index, storage.filePath(index), storage.fileSize(index))
        }
        return ManagedTorrent(info, handle, files, torrentDirectory, hash)
    }

    private suspend fun session(): SessionManager = startMutex.withLock {
        manager ?: SessionManager(false).also {
            it.start()
            manager = it
        }
    }

    private suspend fun awaitPieces(resource: ManagedResource, start: Long, endInclusive: Long) {
        val info = resource.torrent.info
        val fileOffset = info.files().fileOffset(resource.file.index)
        val firstPiece = ((fileOffset + start) / info.pieceLength()).toInt()
        val lastPiece = ((fileOffset + endInclusive) / info.pieceLength()).toInt()
            .coerceAtMost(info.numPieces() - 1)
        for (piece in firstPiece..lastPiece) resource.torrent.handle.piecePriority(piece, Priority.SEVEN)
        extendDownloadWindow(resource, start)
        withTimeout(pieceTimeoutMillis) {
            while ((firstPiece..lastPiece).any { !resource.torrent.handle.havePiece(it) }) delay(100)
        }
    }

    /**
     * Fetches the end of the file up front.
     *
     * An mp4 that was not written for streaming keeps its moov atom there, and a matroska file
     * keeps its cues there; the demuxer reads them before it can decode a frame. Harmless when
     * the whole file is being downloaded anyway, and mandatory once a window stops it from being
     * downloaded — otherwise the bytes the player needs first are the ones parked furthest away.
     */
    private fun prioritizeIndexTail(resource: ManagedResource) {
        val tail = indexTailRange(resource)
        for (piece in tail.first..tail.last) {
            if (resource.torrent.handle.havePiece(piece)) continue
            resource.torrent.handle.piecePriority(piece, Priority.SEVEN)
        }
    }

    private fun indexTailRange(resource: ManagedResource): PieceRange {
        val info = resource.torrent.info
        return pieceRangeOf(
            fileOffset = info.files().fileOffset(resource.file.index),
            pieceLength = info.pieceLength().toLong(),
            start = (resource.file.size - INDEX_TAIL_BYTES).coerceAtLeast(0),
            endInclusive = (resource.file.size - 1).coerceAtLeast(0),
            numPieces = info.numPieces(),
        )
    }

    /** Stops the download running past the window the viewer allowed. See the desktop engine. */
    private fun parkPiecesBeyondWindow(resource: ManagedResource) {
        val ahead = policy().downloadAheadBytes
        if (ahead <= 0) return
        val info = resource.torrent.info
        val fileOffset = info.files().fileOffset(resource.file.index)
        val pieceLength = info.pieceLength().toLong()
        val file = filePieceRange(fileOffset, resource.file.size, pieceLength, info.numPieces())
        val window = downloadWindow(fileOffset, resource.file.size, pieceLength, 0, ahead, info.numPieces())
        val tail = indexTailRange(resource)
        for (piece in (window.last + 1)..file.last) {
            if (piece in tail) continue
            if (resource.torrent.handle.havePiece(piece)) continue
            resource.torrent.handle.piecePriority(piece, Priority.IGNORE)
        }
    }

    /** Releases parked pieces as the reader advances, never re-treading ground already covered. */
    private fun extendDownloadWindow(resource: ManagedResource, cursor: Long) {
        val ahead = policy().downloadAheadBytes
        if (ahead <= 0) return
        val info = resource.torrent.info
        val handle = resource.torrent.handle
        val window = downloadWindow(
            fileOffset = info.files().fileOffset(resource.file.index),
            fileSize = resource.file.size,
            pieceLength = info.pieceLength().toLong(),
            cursor = cursor,
            aheadBytes = ahead,
            numPieces = info.numPieces(),
        )
        for (piece in maxOf(resource.raisedTo + 1, window.first)..window.last) {
            if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.NORMAL)
        }
        if (window.last > resource.raisedTo) resource.raisedTo = window.last
    }

    private data class ManagedTorrent(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        val files: List<TorrentFile>,
        val saveDirectory: Path,
        val hash: String,
    ) {
        val readers = AtomicInteger(0)
    }

    private data class ManagedResource(
        val torrent: ManagedTorrent,
        val file: TorrentFile,
        val path: Path,
    ) {
        val parked = AtomicBoolean(false)

        @Volatile
        var raisedTo: Int = -1
    }
}

/** How much of the end of the file is fetched up front, for the container index. */
private const val INDEX_TAIL_BYTES = 2L * 1024 * 1024

private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "ts", "m2ts" -> "video/mp2t"
    else -> "video/x-matroska"
}
