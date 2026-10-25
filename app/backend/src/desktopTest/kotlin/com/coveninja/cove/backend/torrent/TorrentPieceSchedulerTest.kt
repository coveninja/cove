package com.coveninja.cove.backend.torrent

import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.backend.storage.CacheDirectories
import com.coveninja.cove.backend.storage.CacheStorageService
import com.coveninja.cove.backend.storage.LocalStorageRepository
import com.coveninja.cove.shared.data.TorrentCachePolicy
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.settings_pack
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TorrentPieceSchedulerTest {
    @Test
    fun `storage switch to whole file restores parked pieces without another player read`() = runBlocking {
        NativeTorrentFixture(listOf(64L * MiB)).use { fixture ->
            val state = MutableStateFlow(TorrentCachePolicy(downloadAheadBytes = 8L * MiB))
            val scheduler = fixture.scheduler { state.value.downloadAheadBytes }
            scheduler.prepareForRead(true)
            assertTrue(fixture.ignored(0 until fixture.info.numPieces()) > 0)
            val storage = LocalStorageRepository(
                CacheStorageService(CacheDirectories(torrents = fixture.download), TorrentCacheLifecycle()),
                DeviceSettingsService(fixture.root),
                state,
                onDownloadPolicyChanged = scheduler::refreshDownloadPolicy,
            )

            storage.setPolicy(state.value.copy(downloadAheadBytes = 0))

            assertEquals(0, fixture.ignored(0 until fixture.info.numPieces()))
            assertEquals(0L, DeviceSettingsService(fixture.root).read().downloadAheadBytes)
        }
    }

    @Test
    fun `whole file preserves urgent pieces and does not select other files in a pack`() {
        NativeTorrentFixture(listOf(32L * MiB, 32L * MiB)).use { fixture ->
            var ahead = 8L * MiB
            val scheduler = fixture.scheduler { ahead }
            scheduler.prepareForRead(true)
            fixture.handle.piecePriority(10, Priority.SIX)
            ahead = 0

            scheduler.refreshDownloadPolicy()

            assertEquals(0, fixture.ignored(0 until 16))
            assertEquals(16, fixture.ignored(16 until 32))
            assertEquals(Priority.SIX, fixture.handle.piecePriority(10))
        }
    }

    @Test
    fun `smaller allowance can be applied again after whole file`() {
        NativeTorrentFixture(listOf(64L * MiB)).use { fixture ->
            var ahead = 8L * MiB
            val scheduler = fixture.scheduler { ahead }
            scheduler.prepareForRead(true)
            val initiallyParked = fixture.ignored(0 until fixture.info.numPieces())
            ahead = 0
            scheduler.refreshDownloadPolicy()
            ahead = 8L * MiB

            scheduler.refreshDownloadPolicy()

            assertEquals(initiallyParked, fixture.ignored(0 until fixture.info.numPieces()))
        }
    }

    @Test
    fun `a slowly arriving large piece is not declared stalled before it completes`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val size = 2 * MiB
        server.executor = executor
        server.createContext("/payload.mkv") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                    ?.removePrefix("bytes=")?.split('-')
                val first = range?.firstOrNull()?.toIntOrNull() ?: 0
                val last = range?.getOrNull(1)?.toIntOrNull() ?: (size - 1)
                val length = last - first + 1
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$size")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, length.toLong())
                exchange.responseBody.use { body ->
                    val block = ByteArray(16 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val count = minOf(remaining, block.size)
                        body.write(block, 0, count)
                        body.flush()
                        remaining -= count
                        Thread.sleep(160) // ~100 KiB/s; one piece takes longer than the 15s stall threshold.
                    }
                }
            } catch (_: java.io.IOException) {
                // Closing the fixture also closes an unfinished native request.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            NativeTorrentFixture(listOf(size.toLong()), "http://127.0.0.1:${server.address.port}/payload.mkv").use { fixture ->
                val scheduler = fixture.scheduler { 0L }
                scheduler.prepareForRead(true)
                val started = System.nanoTime()
                try {
                    scheduler.beginRead().use { reader ->
                        withTimeout(45_000) { reader.awaitPieces(0, 1, 40_000) }
                    }
                } finally {
                    val status = fixture.handle.status(true)
                    println("Throttled fixture: received=${status.totalPayloadDownload()} completed=${status.totalDone()}")
                }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue(elapsed >= PIECE_STALL_MILLIS, "Fixture was not slow enough: $elapsed ms")
                assertTrue(fixture.handle.havePiece(0))
                assertTrue(fixture.handle.status(true).totalPayloadDownload() >= size)
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

private const val MiB = 1024 * 1024

private class NativeTorrentFixture(sizes: List<Long>, webSeed: String? = null) : AutoCloseable {
    val root = Files.createTempDirectory("cove-scheduler-")
    val download = Files.createDirectory(root.resolve("download"))
    val info: TorrentInfo
    val handle: TorrentHandle
    private val session = SessionManager(false)
    private val selectedSize = sizes.first()

    init {
        val piece = 2 * MiB
        val digest = MessageDigest.getInstance("SHA-1").digest(ByteArray(piece))
        val pieces = ByteArrayOutputStream().apply {
            repeat((sizes.sum() / piece).toInt()) { write(digest) }
        }.toByteArray()
        val description = mutableMapOf<String, Any>("piece length" to piece, "pieces" to pieces)
        if (sizes.size == 1) {
            description["name"] = "payload.mkv"
            description["length"] = sizes.first()
        } else {
            description["name"] = "bundle"
            description["files"] = sizes.mapIndexed { index, size ->
                mapOf("length" to size, "path" to listOf("part-$index.mkv"))
            }
        }
        info = TorrentInfo(bencode(mapOf("info" to description)))
        if (webSeed != null) info.addUrlSeed(webSeed)
        val settings = SettingsPack().listenInterfaces("127.0.0.1:0").apply {
            setEnableDht(false)
            setEnableLsd(false)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        }
        session.start(SessionParams().apply {
            setSettings(settings)
            if (needsPosixTorrentDiskIo(System.getProperty("os.name"))) setPosixDiskIO()
        })
        session.download(info, download.toFile())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var found: TorrentHandle? = null
        while (found == null && System.nanoTime() < deadline) {
            found = session.find(info.infoHashV1())
            if (found == null) Thread.sleep(10)
        }
        handle = checkNotNull(found) { "Native fixture torrent was not added" }
        handle.prioritizeFiles(Priority.array(Priority.IGNORE, sizes.size).apply { this[0] = Priority.NORMAL })
    }

    fun scheduler(ahead: () -> Long) = TorrentPieceScheduler(handle, info, 0, selectedSize, ahead)

    fun ignored(pieces: IntRange): Int = pieces.count { handle.piecePriority(it) == Priority.IGNORE }

    override fun close() {
        session.stop()
        root.toFile().deleteRecursively()
    }
}

private fun bencode(value: Any): ByteArray = ByteArrayOutputStream().apply {
    fun put(item: Any) {
        when (item) {
            is Number -> write("i${item}e".toByteArray())
            is String -> put(item.toByteArray())
            is ByteArray -> { write("${item.size}:".toByteArray()); write(item) }
            is List<*> -> { write('l'.code); item.forEach { put(requireNotNull(it)) }; write('e'.code) }
            is Map<*, *> -> {
                write('d'.code)
                item.entries.sortedBy { it.key as String }.forEach { put(it.key as String); put(requireNotNull(it.value)) }
                write('e'.code)
            }
            else -> error("Unsupported fixture value")
        }
    }
    put(value)
}.toByteArray()
