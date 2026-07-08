package com.coveninja.cove.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.coveninja.cove.api.*
import com.coveninja.cove.sync.SyncCoordinator
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Port of web/src/lib/utils.ts getVideoOpt(vids, "Trailer", { randomize: true }).
 * Filters to YouTube-only (the library cannot play Vimeo).
 * Falls back: YouTube trailers → any YouTube video → null.
 */
private fun getTrailerKey(videos: MediaVideos?): String? {
    val results = videos?.results ?: return null
    if (results.isEmpty()) return null

    // Primary: YouTube trailers (randomize)
    var list = results.filter { it.type == "Trailer" && it.site.lowercase() == "youtube" }
    // Fallback: any YouTube video
    if (list.isEmpty()) list = results.filter { it.site.lowercase() == "youtube" }
    if (list.isEmpty()) return null

    return list.random().key
}

private fun formatRuntime(minutes: Int): String {
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(
    media: Media,
    onDismiss: () -> Unit,
    onStreamsRequested: (Media, Int?, Int?) -> Unit,
    onOpenMedia: ((Media) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<Details?>(null) }
    var libraryDetail by remember { mutableStateOf<LibraryDetail?>(null) }
    var loadingDetails by remember { mutableStateOf(true) }
    var selectedSeason by rememberSaveable { mutableStateOf(1) }
    var episodes by remember { mutableStateOf<List<TvEpisode>>(emptyList()) }
    var loadingEpisodes by remember { mutableStateOf(false) }
    var libraryLoading by remember { mutableStateOf(false) }
    var trailerKey by remember { mutableStateOf<String?>(null) }
    var similar by remember { mutableStateOf<List<Media>>(emptyList()) }

    // Parallel fetch: details + library status + videos + similar.
    LaunchedEffect(media.id) {
        loadingDetails = true
        var fetchedDetails: Details? = null
        var fetchedLib: LibraryDetail? = null
        var fetchedVideos: MediaVideos? = null
        var fetchedSimilar: List<Media> = emptyList()
        withContext(Dispatchers.IO) {
            coroutineScope {
                val d = async {
                    try { CoveApiClient.get<Details>("/details?id=${media.id}&type=${media.mediaType}") }
                    catch (e: Exception) { null }
                }
                val lib = async {
                    try { CoveApiClient.getOrNull<LibraryDetail>("/library/${media.id}/${media.mediaType}") }
                    catch (e: Exception) { null }
                }
                val vids = async {
                    try { CoveApiClient.get<MediaVideos>("/videos?id=${media.id}&type=${media.mediaType}") }
                    catch (e: Exception) { null }
                }
                val sim = async {
                    try { CoveApiClient.get<List<Media>>("/similar?id=${media.id}&type=${media.mediaType}") }
                    catch (e: Exception) { emptyList() }
                }
                fetchedDetails = d.await()
                fetchedLib = lib.await()
                fetchedVideos = vids.await()
                fetchedSimilar = sim.await() ?: emptyList()
            }
        }
        details = fetchedDetails
        libraryDetail = fetchedLib
        trailerKey = getTrailerKey(fetchedVideos)
        similar = fetchedSimilar

        if (!media.isMovie && (fetchedDetails?.seasons?.isNotEmpty() == true)) {
            val firstSeason = fetchedDetails!!.seasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber ?: 1
            selectedSeason = firstSeason
        }
        loadingDetails = false
    }

    LaunchedEffect(media.id, selectedSeason) {
        if (!media.isMovie && selectedSeason > 0) {
            loadingEpisodes = true
            episodes = try {
                CoveApiClient.get("/tv/episodes?id=${media.id}&season=$selectedSeason")
            } catch (e: Exception) { emptyList() }
            loadingEpisodes = false
        }
    }

    // Map episode number → WatchProgress for the currently selected season.
    // Recomputes whenever libraryDetail or selectedSeason change.
    val progressMap by remember {
        derivedStateOf {
            libraryDetail?.progress
                ?.filter { it.season == selectedSeason }
                ?.associateBy { it.episode ?: -1 }
                ?: emptyMap()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        // Fill the whole view: the sheet's surface spans top-to-bottom and draws
        // under the navigation bar so no strip of the scrimmed page shows through
        // beneath it. Content is padded for the nav bar below.
        contentWindowInsets = { WindowInsets(0) },
        // Span the full width too — otherwise the sheet is capped at ~640dp and
        // centered on wide/landscape screens, leaving bands on either side.
        sheetMaxWidth = Dp.Unspecified,
        modifier = Modifier.fillMaxHeight(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ── Backdrop / YouTube trailer header ──────────────────────────────
            item {
                TrailerHeader(
                    media = media,
                    trailerKey = trailerKey,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                )
            }

            // Title + meta row
            item {
                Column(Modifier.padding(16.dp, 0.dp)) {
                    Text(
                        media.displayTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (media.year.isNotBlank()) {
                            Text(media.year, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val typeLabel = if (media.isMovie) "Movie" else "TV Series"
                        Text(typeLabel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (media.voteAverage > 0) {
                            Text(
                                "★ ${"%.1f".format(media.voteAverage)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (!loadingDetails && details != null) {
                            val runtimeMinutes = if (media.isMovie) details!!.runtime
                                                 else details!!.episodeRunTime.firstOrNull() ?: 0
                            val runtimeStr = formatRuntime(runtimeMinutes)
                            if (runtimeStr.isNotBlank()) {
                                Text(
                                    runtimeStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!loadingDetails && details != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            details!!.genres.take(3).forEach { g ->
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(g.name, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            // Library status controls
            item {
                val currentStatus = libraryDetail?.entry?.status
                val inLibrary = currentStatus != null
                val libEntry = libraryDetail?.entry

                // Resume-aware play labels
                val movieProgressEntry = libraryDetail?.progress
                    ?.firstOrNull { it.season == null && it.episode == null }
                val moviePlayLabel = when {
                    movieProgressEntry?.completed == true -> "Rewatch"
                    (movieProgressEntry?.positionSeconds ?: 0.0) > 10.0 -> "Continue watching"
                    else -> "Play"
                }
                val tvPlayLabel = if (libEntry?.lastWatchedSeason != null && libEntry.lastWatchedEpisode != null) {
                    "Continue S${libEntry.lastWatchedSeason}E${libEntry.lastWatchedEpisode}"
                } else "Play"
                val tvPlaySeason = libEntry?.lastWatchedSeason
                val tvPlayEpisode = libEntry?.lastWatchedEpisode

                val playLabel = if (media.isMovie) moviePlayLabel else tvPlayLabel
                val playSeasonArg: Int? = if (media.isMovie) null else tvPlaySeason
                val playEpisodeArg: Int? = if (media.isMovie) null else tvPlayEpisode

                if (!inLibrary) {
                    var showStatusMenu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.padding(16.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            OutlinedButton(
                                onClick = { if (!libraryLoading) showStatusMenu = true },
                            ) {
                                Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = showStatusMenu,
                                onDismissRequest = { showStatusMenu = false },
                            ) {
                                listOf(
                                    "watch_later" to "Watch Later",
                                    "watching" to "Watching",
                                    "finished" to "Finished",
                                    "dropped" to "Dropped",
                                ).forEach { (status, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            showStatusMenu = false
                                            scope.launch {
                                                libraryLoading = true
                                                val bodyJson = buildJsonObject {
                                                    put("tmdb_id", media.id)
                                                    put("media_type", media.mediaType)
                                                    put("title", media.displayTitle)
                                                    put("poster_path", media.posterPath)
                                                    put("vote_average", media.voteAverage)
                                                    put("status", status)
                                                }.toString()
                                                val newEntry = CoveApiClient.post<LibraryEntry>("/library", bodyJson)
                                                libraryDetail = (libraryDetail ?: LibraryDetail()).copy(entry = newEntry)
                                                SyncCoordinator.syncAfterMutation()
                                                libraryLoading = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Button(onClick = { onStreamsRequested(media, playSeasonArg, playEpisodeArg) }) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(playLabel)
                        }
                    }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            if (libraryLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            listOf(
                                "watch_later" to "Watch Later",
                                "watching" to "Watching",
                                "finished" to "Finished",
                                "dropped" to "Dropped",
                            ).forEach { (status, label) ->
                                FilterChip(
                                    selected = currentStatus == status,
                                    enabled = !libraryLoading,
                                    onClick = {
                                        if (libraryLoading || currentStatus == status) return@FilterChip
                                        scope.launch {
                                            libraryLoading = true
                                            val bodyJson = buildJsonObject {
                                                put("tmdb_id", media.id)
                                                put("media_type", media.mediaType)
                                                put("title", media.displayTitle)
                                                put("poster_path", media.posterPath)
                                                put("vote_average", media.voteAverage)
                                                put("status", status)
                                            }.toString()
                                            val updatedEntry = CoveApiClient.post<LibraryEntry>("/library", bodyJson)
                                            libraryDetail = libraryDetail?.copy(entry = updatedEntry)
                                            SyncCoordinator.syncAfterMutation()
                                            libraryLoading = false
                                        }
                                    },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    if (libraryLoading) return@TextButton
                                    scope.launch {
                                        libraryLoading = true
                                        CoveApiClient.delete("/library/${media.id}/${media.mediaType}")
                                        libraryDetail = libraryDetail?.copy(entry = null)
                                        SyncCoordinator.syncAfterMutation()
                                        libraryLoading = false
                                    }
                                },
                                enabled = !libraryLoading,
                            ) {
                                Icon(
                                    Icons.Default.BookmarkRemove,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Remove from My List",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Button(onClick = { onStreamsRequested(media, playSeasonArg, playEpisodeArg) }) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(playLabel)
                            }
                        }
                    }
                }
            }

            // Overview
            val overview = details?.overview?.ifBlank { media.overview } ?: media.overview
            if (overview.isNotBlank()) {
                item {
                    Text(
                        overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp, 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Cast
            val castList = details?.credits?.cast?.take(8).orEmpty()
            if (castList.isNotEmpty()) {
                item {
                    Text(
                        "Cast",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(castList) { member ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(72.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        member.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    member.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            // More like this
            if (similar.isNotEmpty()) {
                item {
                    Text(
                        "More like this",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    )
                    MediaRow(similar, onTap = { onOpenMedia?.invoke(it) })
                }
            }

            // TV: season selector + episode list
            if (!media.isMovie) {
                val seasons = details?.seasons?.filter { it.seasonNumber > 0 } ?: emptyList()
                if (seasons.isNotEmpty()) {
                    item {
                        Text(
                            "Seasons",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons) { s ->
                                FilterChip(
                                    selected = s.seasonNumber == selectedSeason,
                                    onClick = { selectedSeason = s.seasonNumber },
                                    label = { Text("S${s.seasonNumber}") },
                                )
                            }
                        }
                    }
                    if (loadingEpisodes) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                    } else {
                        items(episodes) { ep ->
                            EpisodeRow(
                                ep = ep,
                                onPlay = { onStreamsRequested(media, selectedSeason, ep.episodeNumber) },
                                progress = progressMap[ep.episodeNumber],
                            )
                        }
                    }
                }
                if (seasons.isEmpty() && !loadingDetails) {
                    item {
                        Text(
                            "No seasons available.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Bottom spacer + nav-bar inset so the last content clears the nav bar
            // now that the sheet draws edge-to-edge under it.
            item { Spacer(Modifier.height(32.dp).navigationBarsPadding()) }
        }
    }
}

/**
 * Header area: backdrop image as fallback, YouTube trailer on top when available.
 * The trailer autoplays muted with looping. A mute/unmute toggle sits bottom-right.
 * The player is released (WebView destroyed) in DisposableEffect when the sheet closes.
 */
@Composable
private fun TrailerHeader(
    media: Media,
    trailerKey: String?,
    surfaceColor: Color,
) {
    val context = LocalContext.current

    // YouTube player state — hoisted so DisposableEffect and AndroidView share the same ref.
    var ytPlayerView by remember { mutableStateOf<YouTubePlayerView?>(null) }
    var ytPlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var playerFailed by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }

    // Release the WebView when the composable leaves the tree (sheet dismissed).
    DisposableEffect(Unit) {
        onDispose {
            ytPlayerView?.release()
            ytPlayerView = null
            ytPlayer = null
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        // Backdrop poster — always rendered; fades behind the player once ready.
        AsyncImage(
            model = CoveApiClient.resolveImgUrl(media.posterPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // YouTube trailer — only mounted when a key is available and playable.
        // On any player error (embed-restricted video, region block, network)
        // we unmount and fall back to the backdrop instead of showing
        // YouTube's error box.
        if (trailerKey != null && !playerFailed) {
            AndroidView(
                factory = { ctx ->
                    // 13.x: Builder takes the context so the library can derive
                    // the embed origin/Referer from the app package (YouTube's
                    // late-2025 requirement for embedded players).
                    val options = IFramePlayerOptions.Builder(ctx)
                        .controls(0)      // hide YouTube chrome
                        .rel(0)           // no related videos
                        .build()
                    YouTubePlayerView(ctx).also { pv ->
                        // Remove default layout constraints imposed by the library
                        // so fillMaxSize() applies correctly inside Compose.
                        pv.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        pv.enableBackgroundPlayback(false)
                        // Manual initialize() (needed to pass IFramePlayerOptions)
                        // requires opting out of the library's automatic init,
                        // which otherwise runs too and throws IllegalStateException.
                        pv.enableAutomaticInitialization = false
                        pv.initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(player: YouTubePlayer) {
                                // mute() BEFORE loadVideo() to avoid any unmuted flash.
                                player.mute()
                                player.loadVideo(trailerKey, 0f)
                                ytPlayer = player
                            }
                            override fun onStateChange(
                                player: YouTubePlayer,
                                state: PlayerConstants.PlayerState,
                            ) {
                                when (state) {
                                    PlayerConstants.PlayerState.PLAYING -> playerReady = true
                                    PlayerConstants.PlayerState.ENDED -> {
                                        // Loop: seek to 0 and replay.
                                        player.seekTo(0f)
                                        player.play()
                                    }
                                    else -> {}
                                }
                            }
                            override fun onError(
                                player: YouTubePlayer,
                                error: PlayerConstants.PlayerError,
                            ) {
                                // Defer the release past this callback, then
                                // let recomposition unmount the AndroidView.
                                pv.post {
                                    pv.release()
                                    ytPlayerView = null
                                    ytPlayer = null
                                }
                                playerReady = false
                                playerFailed = true
                            }
                        }, options)
                        ytPlayerView = pv
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    // Fade in once the player has started; backdrop shows until then.
                    .then(
                        if (playerReady) Modifier
                        else Modifier.background(Color.Transparent)
                    ),
                // If alpha is needed use graphicsLayer, but visibility is handled
                // by the backdrop image naturally being obscured once the video renders.
            )
        }

        // Bottom gradient — fades backdrop/player into surface so title text is legible.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, surfaceColor))
            )
        )

        // Mute/unmute toggle — only shown when the trailer is actively playing.
        if (trailerKey != null && playerReady) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable {
                        isMuted = !isMuted
                        if (isMuted) ytPlayer?.mute() else ytPlayer?.unMute()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (isMuted) "Unmute trailer" else "Mute trailer",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun EpisodeRow(ep: TvEpisode, onPlay: () -> Unit, progress: WatchProgress? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlay() }.padding(16.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Episode still with progress overlay
        Box(modifier = Modifier.size(100.dp, 60.dp)) {
            AsyncImage(
                model = CoveApiClient.resolveImgUrl(ep.stillPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            )
            when {
                progress?.completed == true -> {
                    // Dimmed overlay with a check-circle for completed episodes
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                (progress?.positionSeconds ?: 0.0) > 5.0 -> {
                    // Thin progress bar anchored to the bottom of the still
                    val fraction = if ((progress?.durationSeconds ?: 0.0) > 0) {
                        (progress!!.positionSeconds / progress.durationSeconds)
                            .toFloat().coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)),
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${ep.episodeNumber}. ${ep.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Air date + per-episode runtime meta line
            val metaParts = buildList<String> {
                if (ep.airDate.isNotBlank()) add(ep.airDate)
                if (ep.runtime > 0) add("${ep.runtime}m")
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ep.overview.isNotBlank()) {
                Text(
                    ep.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Default.PlayArrow, "Play episode", tint = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
}
