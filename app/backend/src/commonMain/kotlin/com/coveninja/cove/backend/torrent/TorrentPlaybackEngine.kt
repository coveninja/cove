package com.coveninja.cove.backend.torrent

import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.Serializable

data class TorrentResource(
    val id: String,
    val name: String,
    val length: Long,
    val contentType: String,
)

@Serializable
data class TorrentProgress(
    val hash: String,
    val fileIndex: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadRate: Int,
    val peers: Int,
    val complete: Boolean,
)

interface TorrentPlaybackEngine : AutoCloseable {
    suspend fun open(hash: String, season: Int?, episode: Int?, fileIndex: Int?): TorrentResource
    suspend fun write(resource: TorrentResource, start: Long, endInclusive: Long, output: ByteWriteChannel)

    /**
     * Opens and writes a range when Ktor's delayed response producer actually starts.
     *
     * [open] is still called beforehand to build the response headers. A cache sweep may run after
     * that call but before the producer, so JVM engines override this to reopen and hold one cache
     * use lease through the complete write. The default keeps lightweight test and non-cache
     * engines source-compatible.
     */
    suspend fun stream(
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
        start: Long,
        endInclusive: Long,
        output: ByteWriteChannel,
    ) {
        write(open(hash, season, episode, fileIndex), start, endInclusive, output)
    }

    fun progress(hash: String): TorrentProgress?

    /**
     * Info hashes the session currently holds open.
     *
     * The cache sweep asks before deleting anything: a torrent being streamed right now is still
     * a file the player has open, and removing it mid-frame is the one failure a retention policy
     * must never cause. Empty is the right answer for an engine with no session up — nothing can
     * be playing through one that was never started — which is also why it is the default.
     *
     * "Active" means being read, not merely loaded. A torrent stays in the session after playback
     * ends so a rewatch is instant, and counting those would mean nothing played since the app
     * started could ever be swept — which is most of what a retention policy is for.
     */
    fun activeHashes(): Set<String> = emptySet()

    /**
     * Drops a torrent from the peer session so its files can be deleted.
     *
     * The sweep must call this before removing a directory. libtorrent keeps the file open and
     * goes on writing pieces into it, so deleting underneath a live handle either fails outright
     * or frees space the session immediately starts refilling.
     *
     * Returns false if the torrent is being read, in which case the caller must leave it alone.
     * JVM callers additionally hold their shared cache lifecycle's exclusive deletion lease from
     * before this call until filesystem deletion finishes. That closes the otherwise unavoidable
     * gap where a new read could start after this method returned.
     */
    fun release(hash: String): Boolean = true

    /**
     * Brings the peer session up before anything is asked of it.
     *
     * Starting it costs a DHT bootstrap, and doing that lazily puts it on the
     * critical path of the first play: the viewer waits through it with nothing on
     * screen. Called when torrent sources are listed, which is the moment one is
     * about to be picked. Idempotent, and a no-op where there is no session to warm.
     */
    suspend fun warmUp() {}

    suspend fun prefetch(hash: String, season: Int?, episode: Int?, fileIndex: Int?) {
        open(hash, season, episode, fileIndex)
    }
}

data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
)

internal fun selectTorrentFile(
    files: List<TorrentFile>,
    season: Int?,
    episode: Int?,
    requestedIndex: Int?,
): TorrentFile {
    val videos = files.filter { it.isVideo() }
    require(videos.isNotEmpty()) { "torrent contains no supported video files" }
    if (requestedIndex != null) {
        return videos.firstOrNull { it.index == requestedIndex }
            ?: throw IllegalArgumentException("invalid torrent file index")
    }
    if (season != null && episode != null) {
        val patterns = listOf(
            Regex("(?i)(?:^|[ ._\\-])s0*$season[ ._\\-]*e0*$episode(?:[ ._\\-]|$)"),
            Regex("(?i)(?:^|[ ._\\-])0*$season[ ._\\-]*x[ ._\\-]*0*$episode(?:[ ._\\-]|$)"),
        )
        videos.firstOrNull { file -> patterns.any { it.containsMatchIn(file.path) } }?.let { return it }
    }
    return videos.maxBy(TorrentFile::size)
}

private fun TorrentFile.isVideo(): Boolean =
    path.substringAfterLast('.', "").lowercase() in
        setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts")
