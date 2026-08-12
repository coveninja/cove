package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.torrent.TorrentProgress
import com.coveninja.cove.shared.network.UpdateCheckDto
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Platform media implementation exposed to the compatibility HTTP routes. */
interface RouteMediaBoundary {
    suspend fun registerStreams(candidates: List<AddonStream>): List<AddonStream>
    suspend fun playDirect(call: ApplicationCall, url: String)
    suspend fun playTorrent(
        call: ApplicationCall,
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    )
    fun torrentProgress(hash: String): TorrentProgress?
    suspend fun probe(request: ProbeStreamsRequest): ProbeStreamsResponse
    suspend fun subtitle(call: ApplicationCall, url: String)
    suspend fun image(call: ApplicationCall, size: String, file: String)
    fun prefetchTorrent(hash: String, season: Int?, episode: Int?, fileIndex: Int?): Boolean
    suspend fun speedTest(call: ApplicationCall)
}

interface MpvConfigStore {
    suspend fun readMpvConfig(): String
    suspend fun writeMpvConfig(content: String)
}

interface RouteUpdater {
    fun check(): UpdateCheckDto
    fun apply(): Nothing
}

@Serializable
data class ProbeStreamsRequest(
    val streams: List<ProbeStreamRequest> = emptyList(),
    val timeoutMs: Int = 0,
)

@Serializable
data class ProbeStreamRequest(val url: String)

@Serializable
data class ProbeStreamsResponse(val results: List<ProbeStreamResult>)

@Serializable
data class ProbeStreamResult(
    val url: String,
    val alive: Boolean,
    @SerialName("contentLength") val contentLength: Long = 0,
)
