package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val metadataTimeoutSeconds: Int = 45,
    private val pieceTimeoutMillis: Long = 120_000,
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
        prioritizeIndexTail(torrent, selected)
        val path = torrent.saveDirectory.resolve(selected.path).normalize()
        require(path.startsWith(torrent.saveDirectory)) { "torrent file escaped download directory" }
        val id = "$canonical:${selected.index}"
        // Reused rather than rebuilt, because this runs on every range request and mpv opens a
        // new one on every seek: the resource carries how far the download window has already
        // been advanced, and a fresh object would re-issue every piece priority behind it.
        val managed = resources.computeIfAbsent(id) { ManagedResource(torrent, selected, path) }
        // File priority alone means the whole file, which is what kept downloading the rest of an
        // episode after the viewer quit five minutes in. Everything past the opening window is
        // parked once, by whichever reader gets here first, and raised again as the reader
        // advances — so the download follows the player instead of outrunning it.
        if (managed.parked.compareAndSet(false, true)) parkPiecesBeyondWindow(managed)
        log("$canonical: serving file ${selected.index} (${selected.size} bytes) ${selected.path}")
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
        // Dates the cache entry. Cheap by design — it writes memory and flushes at most once a
        // minute — because it sits on the path every served byte takes.
        journal?.touch(managed.torrent.hash)
        // Marks the torrent as being read for as long as this response is being written, which is
        // what stops the sweep releasing it underneath the player. Incremented before the first
        // wait and released in a finally, so an aborted range request — the player seeking, or
        // closing — does not leave the torrent pinned for the rest of the session.
        managed.torrent.readers.incrementAndGet()
        try {
            // libtorrent allocates sparsely: until it flushes the first piece covering
            // this file, nothing exists on disk — not the file, not even the directory
            // holding it. Opening before that wait throws FileNotFoundException for
            // every freshly added torrent, and because respondBytesWriter has already
            // sent the 206 by then, the player sees a stream that dies at byte zero.
            awaitPieces(managed, cursor, cursor)
            awaitTorrentFile(managed.path, pieceTimeoutMillis)
            RandomAccessFile(managed.path.toFile(), "r").use { input ->
                while (cursor <= endInclusive) {
                    // A piece at a time, not a megabyte: the player is handed bytes the
                    // moment the piece under the cursor lands, rather than waiting for
                    // every piece of a megabyte to be complete before any of it moves.
                    // The read-ahead in awaitPieces is what keeps the pipe full, so the
                    // smaller step costs nothing in throughput and removes the stall the
                    // viewer actually sees — the one before the picture first appears.
                    val chunkEnd = min(min(endInclusive, pieceEndOffset(managed, cursor)), cursor + buffer.size - 1)
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

    override fun activeHashes(): Set<String> =
        torrents.values.filter { it.readers.get() > 0 }.mapTo(mutableSetOf()) { it.hash }

    override fun release(hash: String): Boolean {
        val canonical = hash.lowercase()
        val torrent = torrents[canonical] ?: return true
        if (torrent.readers.get() > 0) return false
        // Removed from the map first: a read that begins between here and the session removal
        // would otherwise pin a torrent that is already on its way out, and the resource it looks
        // up would point at files about to be deleted. A new open() after this re-adds it.
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
        val started = System.currentTimeMillis()
        var reportedAt = started
        while (!handle.status().hasMetadata()) {
            val elapsed = System.currentTimeMillis() - started
            val peers = runCatching { handle.status().numPeers() }.getOrDefault(0)
            if (elapsed >= metadataTimeoutSeconds * 1_000L) {
                log("$hash: no metadata after ${elapsed / 1_000}s, $peers peers — giving up")
                throw IllegalStateException(
                    "timed out fetching torrent metadata after ${elapsed / 1_000}s with $peers peers",
                )
            }
            if (elapsed >= DEAD_SWARM_MILLIS && peers == 0) {
                log("$hash: no peers at all after ${elapsed / 1_000}s — treating the source as dead")
                throw IllegalStateException(
                    "no peers found for this torrent after ${elapsed / 1_000}s — the source looks dead",
                )
            }
            if (System.currentTimeMillis() - reportedAt >= PROGRESS_REPORT_MILLIS) {
                reportedAt = System.currentTimeMillis()
                // The session's own state alongside the peer count: zero peers with a
                // dead DHT and no trackers is a session that never got started
                // properly, which looks identical from the outside to a torrent
                // nobody is seeding.
                val session = manager
                val dht = runCatching {
                    "dht=${session?.isDhtRunning} nodes=${session?.dhtNodes()}"
                }.getOrDefault("dht=?")
                val trackers = runCatching { handle.trackers().size }.getOrDefault(-1)
                log("$hash: waiting for metadata, ${elapsed / 1_000}s elapsed, $peers peers, $dht, $trackers trackers")
            }
            delay(PIECE_POLL_MILLIS)
        }
        val fetched = handle.torrentFile()
        log("$hash: metadata in ${System.currentTimeMillis() - started}ms")
        // Cached after it parses, so metadata that does not decode is never written
        // and the next attempt refetches rather than failing fast.
        runCatching { if (fetched.isValid) Files.write(cachePath, fetched.bencode()) }
        return fetched
    }

    /**
     * Reads metadata cached by an earlier play, or null if there is none to read.
     *
     * A corrupt or truncated file reads as "no cache" rather than as an error: the
     * fetch that follows is slow, not broken, and losing a torrent to a bad cache
     * entry would be a far worse trade than paying for the metadata again.
     */
    private fun readCachedMetadata(path: Path, hash: String): TorrentInfo? = runCatching {
        TorrentInfo(path.toFile()).takeIf { info ->
            // The info hash is checked rather than trusted from the file name: a
            // cache entry that decodes to a different torrent would otherwise send
            // the player off downloading something else entirely, and the find()
            // below would search for a handle that was never added.
            info.isValid && info.infoHashV1()?.toHex().equals(hash, ignoreCase = true)
        }
    }.getOrNull()

    private suspend fun session(): SessionManager = startMutex.withLock {
        // Before the first touch of any jlibtorrent class, which is what loads its
        // native library — the interposition only applies to objects loaded after it.
        NativePreloads.install()
        manager ?: run {
            SessionManager(false).also {
                it.start()
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

    /**
     * Asks for the end of the file up front, alongside the beginning.
     *
     * An mp4 that was not written for streaming keeps its moov atom at the end, and
     * a matroska file keeps its cues there; either way the player seeks to the tail
     * before it can decode a single frame. Under sequential download those bytes are
     * otherwise the *last* thing to arrive, so the viewer waits out a download of the
     * whole episode to see the first second of it. A couple of megabytes at each end
     * is what the demuxer actually needs to open the file.
     */
    private fun prioritizeIndexTail(torrent: ManagedTorrent, file: TorrentFile) {
        val tail = indexTailRange(torrent, file)
        for (piece in tail.first..tail.last) {
            if (torrent.handle.havePiece(piece)) continue
            torrent.handle.piecePriority(piece, Priority.SEVEN)
            torrent.handle.setPieceDeadline(piece, INDEX_TAIL_DEADLINE_MILLIS)
        }
    }

    /** The container index at the end of the file — wanted up front however small the window. */
    private fun indexTailRange(torrent: ManagedTorrent, file: TorrentFile): PieceRange {
        val info = torrent.info
        return pieceRangeOf(
            fileOffset = info.files().fileOffset(file.index),
            pieceLength = info.pieceLength().toLong(),
            start = (file.size - INDEX_TAIL_BYTES).coerceAtLeast(0),
            endInclusive = (file.size - 1).coerceAtLeast(0),
            numPieces = info.numPieces(),
        )
    }

    /**
     * Tells libtorrent not to fetch anything past the opening window.
     *
     * The index tail is exempt: the demuxer cannot open the file without it, so it is wanted up
     * front no matter how tight the allowance. A window of zero means the viewer asked for the
     * whole file, and nothing is parked at all.
     */
    private fun parkPiecesBeyondWindow(resource: ManagedResource) {
        val ahead = policy().downloadAheadBytes
        if (ahead <= 0) return
        val torrent = resource.torrent
        val info = torrent.info
        val fileOffset = info.files().fileOffset(resource.file.index)
        val pieceLength = info.pieceLength().toLong()
        val file = filePieceRange(fileOffset, resource.file.size, pieceLength, info.numPieces())
        val window = downloadWindow(fileOffset, resource.file.size, pieceLength, 0, ahead, info.numPieces())
        val tail = indexTailRange(torrent, resource.file)
        for (piece in (window.last + 1)..file.last) {
            if (piece in tail) continue
            if (torrent.handle.havePiece(piece)) continue
            torrent.handle.piecePriority(piece, Priority.IGNORE)
        }
    }

    /**
     * Opens the window as the reader advances, releasing pieces parked by [parkPiecesBeyondWindow].
     *
     * Only ever forward, from where the last call left off: a backward seek re-reads pieces that
     * are already on disk or already requested, and re-issuing priorities behind the furthest
     * point reached would cost a JNI call per piece for every chunk served.
     */
    private fun extendDownloadWindow(resource: ManagedResource, cursor: Long) {
        val ahead = policy().downloadAheadBytes
        if (ahead <= 0) return
        val handle = resource.torrent.handle
        val info = resource.torrent.info
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

    /** The last byte, in file coordinates, of the piece holding [offset]. */
    private fun pieceEndOffset(resource: ManagedResource, offset: Long): Long {
        val info = resource.torrent.info
        val pieceLength = info.pieceLength().toLong()
        val fileOffset = info.files().fileOffset(resource.file.index)
        val pieceIndex = (fileOffset + offset) / pieceLength
        return (pieceIndex + 1) * pieceLength - 1 - fileOffset
    }

    private suspend fun awaitPieces(resource: ManagedResource, start: Long, endInclusive: Long) {
        val info = resource.torrent.info
        val handle = resource.torrent.handle
        val pieceLength = info.pieceLength()
        val fileOffset = info.files().fileOffset(resource.file.index)
        val firstPiece = ((fileOffset + start) / pieceLength).toInt()
        val lastPiece = ((fileOffset + endInclusive) / pieceLength).toInt()
            .coerceAtMost(info.numPieces() - 1)
        // Deadlines rather than priority alone: a deadline makes libtorrent ask its
        // fastest peers for the piece and order every other request around it, which
        // is the difference between the next chunk arriving now and it arriving once
        // the swarm gets round to it. They are staggered so the piece being read is
        // always the most urgent one outstanding.
        for (piece in firstPiece..lastPiece) {
            handle.piecePriority(piece, Priority.SEVEN)
            handle.setPieceDeadline(piece, DEADLINE_STEP_MILLIS * (piece - firstPiece))
        }
        // Read-ahead. Without it every chunk starts from cold: the bytes after the
        // ones being served are never asked for until the reader reaches them, so
        // playback stalls once per megabyte no matter how fast the swarm is.
        val readAhead = ((READ_AHEAD_BYTES / pieceLength).toInt()).coerceAtLeast(1)
        val readAheadLast = (lastPiece + readAhead).coerceAtMost(info.numPieces() - 1)
        for (piece in (lastPiece + 1)..readAheadLast) {
            if (!handle.havePiece(piece)) handle.piecePriority(piece, Priority.SIX)
        }
        // Past the urgent read-ahead, out to whatever the viewer allows the download to run to.
        // This is the only thing that releases the pieces parked when the file was opened, so a
        // reader that stops advancing leaves the download stopped where it stood.
        extendDownloadWindow(resource, start)
        val started = System.currentTimeMillis()
        var reportedAt = started
        withTimeout(pieceTimeoutMillis) {
            while ((firstPiece..lastPiece).any { !handle.havePiece(it) }) {
                // Silent while it is keeping up, which is the normal case: only a
                // wait long enough for the viewer to notice is worth a line, and
                // then the peer count and rate are what say whether it is stalled
                // or merely slow.
                if (System.currentTimeMillis() - reportedAt >= PROGRESS_REPORT_MILLIS) {
                    reportedAt = System.currentTimeMillis()
                    val status = runCatching { handle.status(true) }.getOrNull()
                    log(
                        "pieces $firstPiece..$lastPiece still missing after " +
                            "${(System.currentTimeMillis() - started) / 1_000}s — " +
                            "${status?.numPeers() ?: 0} peers, ${(status?.downloadRate() ?: 0) / 1024} KiB/s",
                    )
                }
                delay(PIECE_POLL_MILLIS)
            }
        }
    }

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
    ) {
        /** Set once the pieces past the opening window have been parked. */
        val parked = AtomicBoolean(false)

        /**
         * The furthest piece the window has been opened to, so an advancing reader only ever
         * issues priorities for ground it has not already covered. -1 before the first read.
         */
        @Volatile
        var raisedTo: Int = -1
    }
}

/** How much further ahead of the served chunk pieces are asked for. */
private const val READ_AHEAD_BYTES = 32L * 1024 * 1024

/** How much of the end of the file is fetched up front, for the container index. */
private const val INDEX_TAIL_BYTES = 2L * 1024 * 1024

/**
 * Deadline for the tail: behind the opening pieces, ahead of everything else. The
 * player needs both before it can start, and the opening is the larger read.
 */
private const val INDEX_TAIL_DEADLINE_MILLIS = 3_000

/** Spacing between the deadlines of consecutive pieces in the chunk being read. */
private const val DEADLINE_STEP_MILLIS = 50

private const val PIECE_POLL_MILLIS = 50L

/** How long the session is given to register a torrent that was just added. */
private const val HANDLE_TIMEOUT_MILLIS = 15_000L

/** How long a torrent may find nobody at all before it is called dead. */
private const val DEAD_SWARM_MILLIS = 20_000L

/** How often a wait long enough to be noticed reports what it is waiting on. */
private const val PROGRESS_REPORT_MILLIS = 5_000L

// Matches LocalBackendHost: plain stderr, no logging framework, one "Cove" prefix
// so a user's terminal shows where the line came from.
private fun log(message: String) = System.err.println("Cove torrent: $message")

/**
 * Trackers appended to every magnet.
 *
 * A bare `magnet:?xt=urn:btih:` leaves DHT and peer exchange as the only way to
 * find anybody, which on a cold start means waiting out a DHT lookup before the
 * first byte moves — and for a poorly-seeded torrent it can mean never finding
 * the peers a tracker would have handed over immediately. These are the open
 * trackers the public swarms already announce to, so this asks the same servers
 * the torrent's own announce list would have.
 */
private val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
)

private fun magnetUri(hash: String): String = buildString {
    append("magnet:?xt=urn:btih:").append(hash)
    for (tracker in DEFAULT_TRACKERS) {
        append("&tr=").append(URLEncoder.encode(tracker, StandardCharsets.UTF_8))
    }
}

private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "ts", "m2ts" -> "video/mp2t"
    else -> "video/x-matroska"
}
