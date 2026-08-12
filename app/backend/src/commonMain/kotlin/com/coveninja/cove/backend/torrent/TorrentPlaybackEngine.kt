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
    fun progress(hash: String): TorrentProgress?
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
