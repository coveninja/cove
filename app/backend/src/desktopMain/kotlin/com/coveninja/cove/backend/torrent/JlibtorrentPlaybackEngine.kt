package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JlibtorrentPlaybackEngine(
    private val downloadDirectory: Path,
    private val metadataTimeoutSeconds: Int = 45,
    private val pieceTimeoutMillis: Long = 120_000,
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
        val managed = ManagedResource(torrent, selected, path)
        resources[id] = managed
        TorrentResource(id, path.fileName.toString(), selected.size, contentType(path.fileName.toString()))
    }

    override suspend fun write(
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
    }

    override fun progress(hash: String): TorrentProgress? {
        val canonical = hash.lowercase()
        val torrent = torrents[canonical] ?: return null
        val resource = resources.values.firstOrNull { it.torrent === torrent } ?: return null
        val status = torrent.handle.status(true)
        val fileProgress = torrent.handle.fileProgress().getOrNull(resource.file.index) ?: 0L
        return TorrentProgress(
            hash = canonical,
            fileIndex = resource.file.index,
            downloadedBytes = fileProgress.coerceAtMost(resource.file.size),
            totalBytes = resource.file.size,
            downloadRate = status.downloadRate(),
            peers = status.numPeers(),
            complete = fileProgress >= resource.file.size,
        )
    }

    override fun close() {
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
        val magnet = "magnet:?xt=urn:btih:$hash"
        val metadata = session.fetchMagnet(magnet, metadataTimeoutSeconds, metadataDirectory.toFile())
            ?: throw IllegalStateException("timed out fetching torrent metadata")
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
        return ManagedTorrent(info, handle, files, torrentDirectory)
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
        withTimeout(pieceTimeoutMillis) {
            while ((firstPiece..lastPiece).any { !resource.torrent.handle.havePiece(it) }) delay(100)
        }
    }

    private data class ManagedTorrent(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        val files: List<TorrentFile>,
        val saveDirectory: Path,
    )

    private data class ManagedResource(
        val torrent: ManagedTorrent,
        val file: TorrentFile,
        val path: Path,
    )
}

private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "ts", "m2ts" -> "video/mp2t"
    else -> "video/x-matroska"
}
