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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JlibtorrentPlaybackEngine(
    private val downloadDirectory: Path,
    private val lifecycle: TorrentCacheLifecycle,
    private val metadataTimeoutSeconds: Int = 45,
    /**
     * The ceiling on how long a read waits for the pieces under it. **Must stay below the
     * player's own network timeout**, which on Android is set explicitly in
     * `AndroidMpvVideoPlayerHost`.
     *
     * Every one of these waits happens after the 206 has gone out, so mpv is already blocked on
     * a read while it runs. At the old two minutes the player's timeout always expired first:
     * mpv declared EOF, the viewer was told the stream had stopped before the end, and the
     * engine went on downloading for another minute for a reader that had given up — a wait
     * nobody was served by and no log anywhere accounted for. Losing the race deliberately is
     * worth more than winning it silently, because the loss is one the session can see and
     * reconnect from.
     *
     * A download that has stopped dead gives up well inside this, on PIECE_STALL_MILLIS; this
     * bounds the other case, where pieces keep arriving but never the one being read.
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
    /**
     * One lock per torrent rather than one for all of them. A single lock held across the
     * metadata wait meant one dead source — twenty seconds of finding nobody — stalled every
     * other torrent behind it, including the one the viewer switched to.
     */
    private val torrentLocks = ConcurrentHashMap<String, Mutex>()
    private var manager: SessionManager? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var routeMonitor: Job? = null

    /** What the session's sockets are bound to, and when that last had to change. */
    @Volatile private var appliedRoute: TorrentRoute? = null
    @Volatile private var routeChangedAtMillis = 0L
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
        val torrent = torrents[canonical] ?: torrentLocks.computeIfAbsent(canonical) { Mutex() }.withLock {
            torrents[canonical] ?: loadTorrent(canonical).also { torrents[canonical] = it }
        }
        val selected = selectTorrentFile(torrent.files, season, episode, fileIndex)
        // Once per selection, not once per range request. Setting file priorities makes
        // libtorrent recompute the priority of every piece from them, which discards
        // everything the scheduler has asked for — so doing this on each request, and mpv
        // opens one on every seek, wiped both the parked download window and the read-ahead
        // and sent the engine back to fetching the file from its beginning. Nothing failed;
        // the window simply stopped bounding anything after the viewer's first seek.
        val prioritiesReset = torrent.prioritizedFile.getAndSet(selected.index) != selected.index
        if (prioritiesReset) {
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
        }
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
        // episode after the viewer quit five minutes in. Everything past the window is parked and
        // released again as the reader moves — so the download follows the player instead of
        // outrunning it.
        managed.scheduler.prepareForRead(prioritiesReset)
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
        // One claim on the swarm's urgency per served range, given back when this response
        // ends — including when it ends because the viewer seeked and mpv dropped it.
        val reader = managed.scheduler.beginRead()
        try {
            // libtorrent allocates sparsely: until it flushes the first piece covering
            // this file, nothing exists on disk — not the file, not even the directory
            // holding it. Opening before that wait throws FileNotFoundException for
            // every freshly added torrent, and because respondBytesWriter has already
            // sent the 206 by then, the player sees a stream that dies at byte zero.
            reader.awaitPieces(cursor, cursor, pieceTimeoutMillis)
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
                    reader.awaitPieces(cursor, chunkEnd, pieceTimeoutMillis)
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
            reader.close()
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
        engineScope.cancel()
        // Cancellation alone does not finish an in-flight JNI call. The monitor must release
        // the native session before stop() destroys it.
        runBlocking { routeMonitor?.join() }
        routeMonitor = null
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
        // Torrents arrive auto-managed, and libtorrent's queue runs three downloads at a time:
        // with a few earlier sources still in the session, the one being watched could be
        // queued behind them with nothing to show for it. Cove decides what runs.
        runCatching {
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.resume()
        }
        // The metadata now arrives on the handle that is already talking to peers,
        // rather than on a throwaway one.
        val info = cached ?: try {
            awaitMetadata(handle, hash, cachedMetadata)
        } catch (failure: Throwable) {
            // A source that never produced metadata is not kept: left in the session it went
            // on searching, and once metadata did turn up it downloaded the whole torrent with
            // nobody watching and nothing set to stop it.
            runCatching { session.remove(handle) }
            log("$hash: removed from the session after failing to start")
            throw failure
        }
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
            swarmVerdictReady = ::swarmVerdictReady,
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
            val route = currentTorrentRoute()
            SessionManager(false).also {
                // Installed before the session starts, so its first sockets, DHT nodes and
                // tracker announces already go out the right way rather than racing a rebind.
                startTorrentSession(it, System.getProperty("os.name"), streamingSettings(route))
                manager = it
                appliedRoute = route
                routeChangedAtMillis = System.currentTimeMillis()
                log("session started, running=${it.isRunning}, listening on ${route.listenInterfaces()}")
                routeMonitor = engineScope.launch { followRoute(it) }
            }
        }
    }

    /**
     * Keeps the session on whatever route the system is using now.
     *
     * Checked every few seconds because nothing announces a route change: switching a Tailscale
     * exit node or a VPN on or off moves the default route without adding or removing a single
     * address. When it moves, the sockets are rebound, connections made over the old route are
     * dropped so the torrents reconnect over the new one instead of waiting minutes for those
     * peers to time out, and every torrent announces again. A DHT that has lost every node —
     * bootstrapped while the network was down — is restarted so it bootstraps again.
     */
    private suspend fun followRoute(session: SessionManager) {
        var emptyDhtSince: Long? = null
        var wasOffline = appliedRoute?.isOffline == true
        while (engineScope.isActive) {
            delay(ROUTE_CHECK_MILLIS)
            val route = currentTorrentRoute()
            if (!route.isOffline && (route != appliedRoute || wasOffline)) {
                log("network route changed — listening on ${route.listenInterfaces()} (was ${appliedRoute?.listenInterfaces()})")
                val rebound = runCatching {
                    session.applySettings(listenSettings(route))
                    // Refresh outgoing sockets too, including a reconnect with the same IP.
                    session.reopenNetworkSockets()
                }.onFailure { log("could not rebind to the new route (${it::class.simpleName})") }.isSuccess
                if (rebound) {
                    appliedRoute = route
                    routeChangedAtMillis = System.currentTimeMillis()
                    wasOffline = false
                    reconnectTorrents()
                }
            }
            if (route.isOffline) wasOffline = true
            val nodes = runCatching { session.dhtNodes() }.getOrDefault(0L)
            val now = System.currentTimeMillis()
            if (nodes > 0 || route.isOffline) {
                emptyDhtSince = null
            } else if (emptyDhtSince == null) {
                emptyDhtSince = now
            } else if (now - emptyDhtSince >= DHT_RESTART_MILLIS) {
                log("DHT has had no nodes for ${(now - emptyDhtSince) / 1_000}s — restarting it")
                runCatching {
                    session.stopDht()
                    session.startDht()
                }
                emptyDhtSince = now
            }
        }
    }

    private fun reconnectTorrents() {
        torrents.values.forEach { torrent ->
            runCatching {
                torrent.handle.pause()
                torrent.handle.resume()
                torrent.handle.forceReannounce()
                torrent.handle.forceDHTAnnounce()
            }
        }
    }

    /**
     * Zero peers is only evidence against a torrent once the network has been settled for a
     * while and the DHT has somewhere to ask.
     */
    private fun swarmVerdictReady(): Boolean {
        val settled = System.currentTimeMillis() - routeChangedAtMillis >= DEAD_SWARM_MILLIS
        val dhtReady = runCatching { (manager?.dhtNodes() ?: 0L) > 0L }.getOrDefault(true)
        return settled && dhtReady
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
    private fun streamingSettings(route: TorrentRoute): SettingsPack = listenSettings(route)
        .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)

    private fun listenSettings(route: TorrentRoute): SettingsPack = SettingsPack()
        .setString(settings_pack.string_types.listen_interfaces.swigValue(), route.listenInterfaces())

    private data class ManagedTorrent(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        val files: List<TorrentFile>,
        val saveDirectory: Path,
        val hash: String,
    ) {
        /** How many responses are being written from this torrent right now. */
        val readers = AtomicInteger(0)

        /**
         * The file whose priorities are installed on the handle; -1 before the first open.
         *
         * One per torrent rather than per file, because file priorities are a property of the
         * torrent: switching to another episode inside the same pack re-installs them, which
         * resets the pieces of the one being served too. Only a torrent serving two files at
         * once notices, and that reader recovers as its next chunk re-asks for its pieces.
         */
        val prioritizedFile = AtomicInteger(-1)
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
    settings: SettingsPack? = null,
    paramsFactory: () -> SessionParams = ::SessionParams,
) {
    if (settings == null && !needsPosixTorrentDiskIo(osName)) {
        manager.start()
        return
    }
    val params = paramsFactory()
    settings?.let(params::setSettings)
    // libtorrent's mmap backend installs process-wide SIGSEGV/SIGBUS handlers.
    // On macOS and Linux they displace HotSpot's handlers, so faults the JVM normally
    // handles can terminate the process.
    if (needsPosixTorrentDiskIo(osName)) params.setPosixDiskIO()
    manager.start(params)
}

internal fun needsPosixTorrentDiskIo(osName: String): Boolean =
    osName.startsWith("Mac", ignoreCase = true) ||
        osName.startsWith("Linux", ignoreCase = true)

private const val PIECE_POLL_MILLIS = 50L

/** How often the route is checked; a switch is picked up within this. */
private const val ROUTE_CHECK_MILLIS = 5_000L

/** How long the DHT may sit with no nodes at all before it is restarted. */
private const val DHT_RESTART_MILLIS = 30_000L

// Matches LocalBackendHost: plain stderr, no logging framework, one "Cove" prefix
// so a user's terminal shows where the line came from.
private fun log(message: String) = System.err.println("Cove torrent: $message")

private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "ts", "m2ts" -> "video/mp2t"
    else -> "video/x-matroska"
}
