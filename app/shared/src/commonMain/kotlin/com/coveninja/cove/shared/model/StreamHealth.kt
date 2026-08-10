package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One URL to check, matching the backend's probe request shape. */
@Serializable
data class StreamProbeRequest(val url: String)

@Serializable
data class StreamProbeBody(
    val streams: List<StreamProbeRequest> = emptyList(),
    val timeoutMs: Int = 0,
)

@Serializable
data class StreamProbeResult(
    val url: String,
    val alive: Boolean,
    @SerialName("contentLength") val contentLength: Long = 0,
)

@Serializable
data class StreamProbeResponse(val results: List<StreamProbeResult> = emptyList())

/** Live state of a torrent being streamed, from /api/progress. */
@Serializable
data class TorrentProgress(
    val hash: String = "",
    val fileIndex: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val downloadRate: Int = 0,
    val peers: Int = 0,
    val complete: Boolean = false,
)
