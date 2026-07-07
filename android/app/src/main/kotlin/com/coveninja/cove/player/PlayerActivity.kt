package com.coveninja.cove.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.coveninja.cove.sync.SyncCoordinator
import com.coveninja.cove.ui.theme.CoveTheme
import dev.jdtech.mpv.MPVLib
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Full-screen, landscape-first player activity backed by MpvPlayerView.
 *
 * Uses dev.jdtech.mpv:libmpv:0.5.1 (static API: MPVLib.create/init/…,
 * constants on MPVLib directly).
 *
 * Intent extras (use companion-object constants as keys):
 *   EXTRA_STREAM_URL  (String, required) — direct HTTP URL or hash-based
 *                     /api/play?hash=… URL (mirrors api.ts playUrl())
 *   EXTRA_HEADERS     (String, optional) — mpv http-header-fields format
 *   EXTRA_TMDB_ID     (Int,    optional, default -1)   — for progress reporting
 *   EXTRA_MEDIA_TYPE  (String, optional, default "movie")
 *   EXTRA_SEASON      (Int,    optional, default -1)   — omitted if ≤ 0
 *   EXTRA_EPISODE     (Int,    optional, default -1)   — omitted if ≤ 0
 *
 * Progress reporting mirrors progressSaver.svelte.ts:
 *   - Throttled save every 10 s while playing (skip if pos < 5 s or dur == 0)
 *   - Completion threshold: pos/dur ≥ 0.9 → completed=true, pos_seconds=dur
 *   - saveNow on EOF and onDestroy
 *   - Once completed=true is saved, no later incomplete save is sent
 */
class PlayerActivity : ComponentActivity(), MPVLib.EventObserver {

    // ── MPV ───────────────────────────────────────────────────────────────────
    private lateinit var playerView: MpvPlayerView

    // ── HTTP / progress ───────────────────────────────────────────────────────
    private val http        = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSaveMs      = 0L
    private var completedSaved  = false
    private var currentTimePos  = 0.0
    private var currentDuration = 0.0

    // ── Compose state (updated from MPV observer on main thread) ──────────────
    private val stateTimePos   = androidx.compose.runtime.mutableStateOf(0.0)
    private val stateDuration  = androidx.compose.runtime.mutableStateOf(0.0)
    private val stateIsPaused  = androidx.compose.runtime.mutableStateOf(false)
    private val stateBuffering = androidx.compose.runtime.mutableStateOf(false)
    private val stateShowCtrl  = androidx.compose.runtime.mutableStateOf(true)

    // ── Intent extras ─────────────────────────────────────────────────────────
    private lateinit var streamUrl: String
    private var headers:   String? = null
    private var tmdbId:    Int     = -1
    private var mediaType: String  = "movie"
    private var season:    Int?    = null
    private var episode:   Int?    = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // systemUiVisibility — functional on minSdk 29
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive full-screen (deprecated but functional for minSdk 29)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        // Parse intent
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            Log.e(TAG, "No stream URL — finishing")
            finish()
            return
        }
        headers   = intent.getStringExtra(EXTRA_HEADERS)
        tmdbId    = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
        val s = intent.getIntExtra(EXTRA_SEASON, -1)
        val e = intent.getIntExtra(EXTRA_EPISODE, -1)
        season  = if (s > 0) s else null
        episode = if (e > 0) e else null

        Log.i(TAG, "Playing: $streamUrl  tmdbId=$tmdbId mediaType=$mediaType s=$season ep=$episode")

        // Create and initialise mpv
        playerView = MpvPlayerView(this)
        playerView.create()
        MPVLib.addObserver(this)

        setContent {
            CoveTheme {
                PlayerScreen(
                    playerView       = playerView,
                    timePos          = stateTimePos.value,
                    duration         = stateDuration.value,
                    isPaused         = stateIsPaused.value,
                    buffering        = stateBuffering.value,
                    showControls     = stateShowCtrl.value,
                    onToggleControls = { stateShowCtrl.value = !stateShowCtrl.value },
                    onPlayPause      = { playerView.togglePause() },
                    onSeek           = { pos -> playerView.seekTo(pos) },
                    onBack           = { finish() },
                )
            }
        }

        // Queue the file — mpv starts once the surface attaches
        playerView.loadFile(streamUrl, headers)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final progress save on any exit
        if (currentDuration > 0.0 && currentTimePos >= 5.0) {
            val completed = currentTimePos / currentDuration >= 0.9
            doSave(currentTimePos, currentDuration, completed)
            // Debounced sync after the session ends so progress reaches Supabase.
            // Fires 3 s after this call — well after the background save thread
            // completes (typically < 1 s). NOT called on every 10 s heartbeat.
            SyncCoordinator.syncAfterMutation()
        }
        MPVLib.removeObserver(this)
        playerView.destroy()
        http.dispatcher.executorService.shutdown()
    }

    // ── MPVLib.EventObserver ──────────────────────────────────────────────────

    override fun eventProperty(name: String) {}

    override fun eventProperty(name: String, value: Long) {}

    // DOUBLE properties: time-pos, duration — log at verbose level every ~5 s
    override fun eventProperty(name: String, value: Double) {
        mainHandler.post {
            when (name) {
                "time-pos" -> {
                    stateTimePos.value = value
                    currentTimePos     = value
                    Log.v(TAG, "time-pos=${"%.1f".format(value)}s dur=${"%.0f".format(currentDuration)}s")
                    if (!stateIsPaused.value && !stateBuffering.value) {
                        maybeSaveProgress(value, currentDuration)
                    }
                }
                "duration" -> {
                    stateDuration.value = value
                    currentDuration     = value
                }
            }
        }
    }

    // FLAG (boolean) properties: pause, eof-reached, paused-for-cache
    override fun eventProperty(name: String, value: Boolean) {
        mainHandler.post {
            when (name) {
                "pause" -> stateIsPaused.value = value

                "eof-reached" -> if (value) {
                    Log.d(TAG, "EOF — saving completion")
                    val dur = currentDuration
                    val pos = currentTimePos
                    if (dur > 0.0 && pos >= 5.0) doSave(pos, dur, completed = true)
                }

                "paused-for-cache" -> stateBuffering.value = value
            }
        }
    }

    override fun eventProperty(name: String, value: String) {}

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED      -> Log.d(TAG, "MPV_EVENT_FILE_LOADED")
            MPVLib.MPV_EVENT_END_FILE         -> Log.d(TAG, "MPV_EVENT_END_FILE")
            MPVLib.MPV_EVENT_PLAYBACK_RESTART -> Log.d(TAG, "MPV_EVENT_PLAYBACK_RESTART")
        }
    }

    // ── Progress (mirrors progressSaver.svelte.ts) ────────────────────────────

    /** Throttled — at most every 10 s. Skips if pos < 5 s or dur == 0. */
    private fun maybeSaveProgress(pos: Double, dur: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSaveMs < 10_000L) return
        lastSaveMs = now
        doSave(pos, dur, completed = dur > 0.0 && pos / dur >= 0.9)
    }

    /**
     * Post a progress record. Mirrors #save() in progressSaver.svelte.ts:
     *   - skip pos < 5 s or dur == 0
     *   - completed=true → position_seconds = duration_seconds
     *   - never downgrade a completed record
     *   - fire-and-forget on a background Thread
     */
    private fun doSave(pos: Double, dur: Double, completed: Boolean) {
        if (dur <= 0.0 || pos < 5.0) return
        if (tmdbId < 0)               return   // no TMDB id — nothing to save
        if (completedSaved && !completed) return

        if (completed) completedSaved = true

        val posSeconds = if (completed) dur else pos

        val body = JSONObject().apply {
            put("tmdb_id",           tmdbId)
            put("media_type",        mediaType)
            if (season  != null) put("season",  season)
            if (episode != null) put("episode", episode)
            put("position_seconds",  posSeconds)
            put("duration_seconds",  dur)
            put("completed",         completed)
        }.toString()

        Log.d(TAG, "progress save: pos=${posSeconds}s dur=${dur}s completed=$completed")

        Thread {
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:6969/api/library/progress")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    Log.d(TAG, "progress HTTP ${resp.code}")
                }
            } catch (ex: IOException) {
                Log.w(TAG, "progress save failed: ${ex.message}")
            }
        }.start()
    }

    companion object {
        const val EXTRA_STREAM_URL  = "stream_url"
        const val EXTRA_HEADERS     = "headers"
        const val EXTRA_TMDB_ID     = "tmdb_id"
        const val EXTRA_MEDIA_TYPE  = "media_type"
        const val EXTRA_SEASON      = "season"
        const val EXTRA_EPISODE     = "episode"
        private const val TAG       = "PlayerActivity"
    }
}

// ── Composable UI ─────────────────────────────────────────────────────────────

@Composable
private fun PlayerScreen(
    playerView: MpvPlayerView,
    timePos: Double,
    duration: Double,
    isPaused: Boolean,
    buffering: Boolean,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleControls,
            )
    ) {
        // MPV SurfaceView — renders below the Compose layer by default
        AndroidView(
            factory  = { playerView },
            modifier = Modifier.fillMaxSize(),
        )

        // Buffering spinner
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White,
            )
        }

        // Controls overlay
        if (showControls) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top bar: back button
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color.White,
                             style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Bottom: seekbar + play/pause + time
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (duration > 0.0) {
                        Slider(
                            value         = (timePos / duration).toFloat().coerceIn(0f, 1f),
                            onValueChange = { frac -> onSeek(frac * duration) },
                            colors        = SliderDefaults.colors(
                                thumbColor       = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = onPlayPause) {
                            Text(
                                if (isPaused) "▶" else "⏸",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${formatTime(timePos)} / ${formatTime(duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    val s   = seconds.toLong().coerceAtLeast(0L)
    val h   = s / 3600
    val m   = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
    else "%d:%02d".format(m, sec)
}
