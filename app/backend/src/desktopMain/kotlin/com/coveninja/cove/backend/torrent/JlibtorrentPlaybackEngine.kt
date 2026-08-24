package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.settings_pack
import com.coveninja.cove.backend.storage.TorrentCacheJournal
import com.coveninja.cove.shared.data.TorrentCachePolicy
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JlibtorrentPlaybackEngine(
    private val downloadDirectory: Path,
    private val lifecycle: TorrentCacheLifecycle,
    private val metadataTimeoutSeconds: Int = 45,
    /**
     * How long a read waits for the pieces under it. **Must stay below the player's own network
     * timeout**, which on Android is set explicitly in `AndroidMpvVideoPlayerHost`.
     *
     * Every one of these waits happens after the 206 has gone out, so mpv is already blocked on
     * a read while it runs. At the old two minutes the player's timeout always expired first:
     * mpv declared EOF, the viewer was told the stream had stopped before the end, and the
     * engine went on downloading for another minute for a reader that had given up — a wait
     * nobody was served by and no log anywhere accounted for. Losing the race deliberately is
     * worth more than winning it silently, because the loss is one the session can see and
     * reconnect from.
     */
    private val pieceTimeoutMillis: Long = 60_000,
    /**
     * Read fresh on every use rather than captured: the viewer can change the download-ahead
     * allowance mid-episode, and a value copied at construction would go on applying the old
     * one until the app was restarted.
     */
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
        // The hash as the engine received it, so a request that never gets going can
        // be told apart from one aimed at the wrong torrent without guessing from a
        // truncated player error.
        log("open $canonical season=$season episode=$episode fileIndex=$fileIndex")
        val torrent = torrents[canonical] ?: torrentMutex.withLock {
            torrents[canonical] ?: loadTorrent(canonical).also { torrents[canonical] = it }
        }
        val selected = selectTorrentFile(torrent.files, season, episode, fileIndex)
        torrent.handle.prioritizeFiles(
            Priority.array(Priority.IGNORE, torrent.info.numFiles()).also {
                it[selected.index] = Priority.NORMAL
            },
        )
        // A player reads a file front to back; libtorrent's default picker fetches
        // whatever is rarest in the swarm. Left alone it spends the opening minutes
        // collecting pieces from the middle of the episode while the first megabyte
        // — the only one anybody is waiting for — arrives whenever it happens to.
        // Sequential order is what turns this from a download into a stream.
        torrent.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val path = torrent.saveDirectory.resolve(selected.path).normalize()
        require(path.startsWith(torrent.saveDirectory)) { "torrent file escaped download directory" }
        val id = "$canonical:${selected.index}"
        // Reused rather than rebuilt, because this runs on every range request and mpv opens a
        // new one on every seek: the resource carries how far the download window has already
        // been advanced, and a fresh object would re-issue every piece priority behind it.
        val managed = resources.computeIfAbsent(id) {
            ManagedResource(torrent, selected, path, scheduler(torrent, selected))
        }
        // File priority alone means the whole file, which is what kept downloading the rest of an
        // episode after the viewer quit five minutes in. Everything past the opening window is
        // parked once, by whichever reader gets here first, and raised again as the reader
        // advances — so the download follows the player instead of outrunning it.
        managed.scheduler.prepareForRead()
        log("$canonical: serving file ${selected.index} (${selected.size} bytes) ${selected.path}")
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
        // Dates the cache entry. Cheap by design — it writes memory and flushes at most once a
        // minute — because it sits on the path every served byte takes.
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
            managed.scheduler.awaitPieces(cursor, cursor, pieceTimeoutMillis)
            awaitTorrentFile(managed.path, pieceTimeoutMillis)
            RandomAccessFile(managed.path.toFile(), "r").use { input ->
                while (cursor <= endInclusive) {
                    // A piece at a time, not a megabyte: the player is handed bytes the
                    // moment the piece under the cursor lands, rather than waiting for
                    // every piece of a megabyte to be complete before any of it moves.
                    // The read-ahead in awaitPieces is what keeps the pipe full, so the
                    // smaller step costs nothing in throughput and removes the stall the
                    // viewer actually sees — the one before the picture first appears.
                    val chunkEnd = min(
                        min(endInclusive, managed.scheduler.pieceEndOffset(cursor)),
                        cursor + buffer.size - 1,
                    )
                    managed.scheduler.awaitPieces(cursor, chunkEnd, pieceTimeoutMillis)
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

    override suspend fun warmUp() {
        withContext(Dispatchers.IO) { runCatching { session() } }
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
        log("$canonical: released from the session so its files can be removed")
        return true
    }

    override fun close() {
        // Before the maps are cleared, so the last read times of everything this session played
        // reach disk and the next sweep evicts by use rather than by file timestamp.
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
        // Metadata is the slowest part of starting a torrent that has never been
        // played here — a DHT lookup that the viewer waits through with nothing on
        // screen. It never changes for a given info hash, so the second play of an
        // episode, and every resume of one, skips straight to asking for pieces.
        val cachedMetadata = metadataDirectory.resolve("$hash.torrent")
        val cached = readCachedMetadata(cachedMetadata, hash)
        // Added exactly once, and never taken back out.
        //
        // The obvious way to do this — fetchMagnet for the metadata, then download()
        // for the content — costs the entire swarm. fetchMagnet adds the torrent,
        // waits for metadata and then *removes* it, so the second add starts from
        // nothing: another DHT lookup, another tracker announce, another round of
        // peer handshakes, all of which had already completed moments earlier. That
        // second cold start is the wait, and it is why this took tens of seconds
        // where clients that add the magnet once and hold onto it are watching video.
        log("$hash: adding torrent — metadata ${if (cached != null) "from cache" else "from magnet"}")
        run {
            if (cached != null) {
                session.download(cached, torrentDirectory.toFile())
            } else {
                session.download(
                    magnetUri(hash),
                    torrentDirectory.toFile(),
                    TorrentFlags.SEQUENTIAL_DOWNLOAD,
                )
            }
        }
        // Read after the add rather than at start-up: listen endpoints and DHT state
        // are published by alerts a few hundred milliseconds in, so asking the moment
        // the session is created only ever reports "nothing yet".
        log(
            "$hash: session state — running=${session.isRunning} " +
                "dht=${runCatching { session.isDhtRunning() }.getOrDefault(false)} " +
                "nodes=${runCatching { session.dhtNodes() }.getOrDefault(-1)} " +
                "endpoints=${runCatching { session.listenEndpoints() }.getOrDefault(emptyList())}",
        )
        val handle = withTimeout(HANDLE_TIMEOUT_MILLIS) {
            var found: TorrentHandle? = null
            while (found == null) {
                found = session.find(Sha1Hash(hash))
                if (found == null) delay(PIECE_POLL_MILLIS)
            }
            found
        }
        log("$hash: handle acquired, ${runCatching { handle.trackers().size }.getOrDefault(-1)} trackers")
        // The metadata now arrives on the handle that is already talking to peers,
        // rather than on a throwaway one.
        val info = cached ?: awaitMetadata(handle, hash, cachedMetadata)
        val storage = info.files()
        val files = (0 until storage.numFiles()).map { index ->
            TorrentFile(index, storage.filePath(index), storage.fileSize(index))
        }
        log("$hash: ready — ${files.size} files, ${info.numPieces()} pieces of ${info.pieceLength()} bytes")
        return ManagedTorrent(info, handle, files, torrentDirectory, hash)
    }

    /**
     * Waits for the torrent's metadata, saying out loud how it is going.
     *
     * Gives up early on a swarm that has produced nobody at all: a source with zero
     * peers after [DEAD_SWARM_MILLIS] is a dead link, not a slow one, and sitting on
     * it for the full timeout only delays the viewer finding that out. A swarm that
     * has found peers keeps the whole window — that one is working, just slowly.
     */
    private suspend fun awaitMetadata(
        handle: TorrentHandle,
        hash: String,
        cachePath: Path,
    ): TorrentInfo {
        awaitMetadata(
            hash = hash,
            timeoutMillis = metadataTimeoutSeconds * 1_000L,
            hasMetadata = { handle.status().hasMetadata() },
            peerCount = { handle.status().numPeers() },
            log = ::log,
            diagnostics = {
                val session = manager
                val dht = runCatching {
                    "dht=${session?.isDhtRunning} nodes=${session?.dhtNodes()}"
                }.getOrDefault("dht=?")
                val trackers = runCatching { handle.trackers().size }.getOrDefault(-1)
                "$dht, $trackers trackers"
            },
            pollMillis = PIECE_POLL_MILLIS,
        )
        val fetched = handle.torrentFile()
        // Cached after it parses, so metadata that does not decode is never written
        // and the next attempt refetches rather than failing fast.
        runCatching { if (fetched.isValid) Files.write(cachePath, fetched.bencode()) }
        return fetched
    }


    private suspend fun session(): SessionManager = startMutex.withLock {
        // Before the first touch of any jlibtorrent class, which is what loads its
        // native library — the interposition only applies to objects loaded after it.
        NativePreloads.install()
        manager ?: run {
            SessionManager(false).also {
                startTorrentSession(it, System.getProperty("os.name"))
                it.applySettings(streamingSettings())
                manager = it
                log("session started, running=${it.isRunning}")
            }
        }
    }

    /**
     * Settings for finding peers quickly rather than politely.
     *
     * A magnet's trackers land in separate tiers, and libtorrent's default is to
     * announce to the first tier and only fall through to the next when it fails —
     * so four of the five trackers sit idle through exactly the wait that matters,
     * the one before anybody has been found. Announcing to all of them at once is
     * what a streaming client wants: the cost is four extra UDP announces, and the
     * saving is the cold-start wait.
     */
    private fun streamingSettings(): SettingsPack = SettingsPack()
        .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)

    private data class ManagedTorrent(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        val files: List<TorrentFile>,
        val saveDirectory: Path,
        val hash: String,
    ) {
        /** How many responses are being written from this torrent right now. */
        val readers = AtomicInteger(0)
    }

    private data class ManagedResource(
        val torrent: ManagedTorrent,
        val file: TorrentFile,
        val path: Path,
        /** Owns every piece priority and deadline this reader issues, and the state behind them. */
        val scheduler: TorrentPieceScheduler,
    )

    private fun scheduler(torrent: ManagedTorrent, file: TorrentFile) = TorrentPieceScheduler(
        handle = torrent.handle,
        info = torrent.info,
        fileIndex = file.index,
        fileSize = file.size,
        downloadAheadBytes = { policy().downloadAheadBytes },
    )
}

internal fun startTorrentSession(
    manager: SessionManager,
    osName: String,
    paramsFactory: () -> SessionParams = ::SessionParams,
) {
    if (needsPosixTorrentDiskIo(osName)) {
        // libtorrent's mmap backend installs process-wide SIGSEGV/SIGBUS handlers.
        // On macOS and Linux they displace HotSpot's handlers, so faults the JVM normally
        // handles can terminate the process.
        manager.start(paramsFactory().apply { setPosixDiskIO() })
    } else {
        manager.start()
    }
}

internal fun needsPosixTorrentDiskIo(osName: String): Boolean =
    osName.startsWith("Mac", ignoreCase = true) ||
        osName.startsWith("Linux", ignoreCase = true)

private const val PIECE_POLL_MILLIS = 50L

// Matches LocalBackendHost: plain stderr, no logging framework, one "Cove" prefix
// so a user's terminal shows where the line came from.
private fun log(message: String) = System.err.println("Cove torrent: $message")

private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "ts", "m2ts" -> "video/mp2t"
    else -> "video/x-matroska"
}
