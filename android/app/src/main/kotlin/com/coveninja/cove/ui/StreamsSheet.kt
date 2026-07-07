package com.coveninja.cove.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.api.*
import com.coveninja.cove.player.PlayerActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsSheet(
    media: Media,
    season: Int?,
    episode: Int?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var streams by remember { mutableStateOf<List<Stream>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(media.id, season, episode) {
        loading = true
        error = null
        try {
            val path = buildString {
                append("/streams?id=${media.id}&type=${media.mediaType}")
                if (season != null) append("&season=$season")
                if (episode != null) append("&episode=$episode")
            }
            val raw: List<Stream> = CoveApiClient.get(path)
            streams = rankStreams(raw)
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val sheetTitle = if (season != null && episode != null)
                "${media.displayTitle} — S${season}E${episode}"
            else media.displayTitle
            Text(
                "Streams: $sheetTitle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                streams.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "No streams available",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "No provider addons are installed.\nGo to Settings → Addons to add a stream source.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> LazyColumn {
                    items(streams) { stream ->
                        StreamItem(stream) {
                            val playUrl = CoveApiClient.playUrl(
                                if (stream.infoHash.isNotEmpty()) stream.infoHash else stream.url,
                                season,
                                episode,
                            )
                            context.startActivity(
                                Intent(context, PlayerActivity::class.java).apply {
                                    putExtra(PlayerActivity.EXTRA_STREAM_URL, playUrl)
                                    putExtra(PlayerActivity.EXTRA_TMDB_ID, media.id)
                                    putExtra(PlayerActivity.EXTRA_MEDIA_TYPE, media.mediaType)
                                    if (season != null) putExtra(PlayerActivity.EXTRA_SEASON, season)
                                    if (episode != null) putExtra(PlayerActivity.EXTRA_EPISODE, episode)
                                    if (stream.headers.isNotEmpty()) {
                                        val headersStr = stream.headers.entries
                                            .joinToString("\n") { "${it.key}: ${it.value}" }
                                        putExtra(PlayerActivity.EXTRA_HEADERS, headersStr)
                                    }
                                }
                            )
                            onDismiss()
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun StreamItem(stream: Stream, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stream.name.lines().firstOrNull() ?: stream.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (stream.title.isNotBlank()) {
                Text(
                    stream.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val quality = inferQuality(stream)
                if (quality != null) {
                    Text(
                        quality.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "· ${stream.addonName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
