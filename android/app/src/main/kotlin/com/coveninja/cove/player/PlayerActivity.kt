package com.coveninja.cove.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.Settings
import com.coveninja.cove.api.Stream
import com.coveninja.cove.api.SubtitleDto
import com.coveninja.cove.api.TimestampData
import com.coveninja.cove.api.TimestampSegment
import com.coveninja.cove.api.WatchProgress
import com.coveninja.cove.sync.SyncCoordinator
import com.coveninja.cove.ui.rankStreams
import com.coveninja.cove.ui.theme.CoveTheme
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

// ── In-file data classes ──────────────────────────────────────────────────────

/** A single mpv track entry parsed from the "track-list" property. */
data class MpvTrack(
    val id: Int,
    val type: String,      // "audio" | "sub" | "video"
    val title: String,
    val lang: String,
    val selected: Boolean,
)

/** Currently-active IntroDB segment (derived from timestamps + time-pos). */
data class ActiveSegment(
    val type: String,      // "intro" | "recap" | "credits" | "preview"
    val label: String,     // "Intro" | "Recap" | "Credits" | "Preview"
    val endMs: Long?,      // null = end of file
)

/**
 * Full-screen, landscape-first player activity backed by MpvPlayerView.
 *
 * Uses dev.jdtech.mpv:libmpv:0.5.1 (static API: MPVLib.create/init/…,
 * constants on MPVLib directly).
 *
 * Intent extras (use companion-object constants as keys):
 *   EXTRA_STREAM_URL     (String, required) — direct HTTP URL or hash
 *   EXTRA_HEADERS        (String, optional) — mpv http-header-fields format
 *   EXTRA_TMDB_ID        (Int,    optional, default -1)
 *   EXTRA_MEDIA_TYPE     (String, optional, default "movie")
 *   EXTRA_SEASON         (Int,    optional, default -1)
 *   EXTRA_EPISODE        (Int,    optional, default -1)
 *   EXTRA_TITLE          (String, optional) — lock-screen title
 *   EXTRA_ORIGINAL_LANG  (String, optional) — ISO 639-1 original language for audio auto-select
 *
 * NOTE FOR CALLERS: MediaDetailSheet / StreamsSheet should pass EXTRA_ORIGINAL_LANG
 * using the media's originalLanguage field (Media.originalLanguage from Dtos.kt).
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSaveMs      = 0L
    private var completedSaved  = false
    private var currentTimePos  = 0.0
    private var currentDuration = 0.0

    // ── Basic Compose state ───────────────────────────────────────────────────
    private val stateTimePos    = mutableStateOf(0.0)
    private val stateDuration   = mutableStateOf(0.0)
    private val stateIsPaused   = mutableStateOf(false)
    private val stateBuffering  = mutableStateOf(false)
    private val stateShowCtrl   = mutableStateOf(true)
    private val stateCtrlRev    = mutableStateOf(0)   // incremented to restart auto-hide timer

    // ── Extended Compose state ────────────────────────────────────────────────
    private val stateAudioTracks    = mutableStateOf<List<MpvTrack>>(emptyList())
    private val stateSubTracks      = mutableStateOf<List<MpvTrack>>(emptyList())
    private val stateActiveSegment  = mutableStateOf<ActiveSegment?>(null)
    private val stateNextEp         = mutableStateOf<NextEp?>(null)
    private val stateUpNextDismissed = mutableStateOf(false)
    private val stateSpeed          = mutableStateOf(1f)
    private val stateShowError      = mutableStateOf(false)
    private val stateIsEof          = mutableStateOf(false)
    private val stateTimestamps     = mutableStateOf<TimestampData?>(null)

    // ── Audio focus ───────────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var wasPausedByFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        mainHandler.post {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    wasPausedByFocusLoss = !stateIsPaused.value
                    if (::playerView.isInitialized) playerView.pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (wasPausedByFocusLoss) {
                        if (::playerView.isInitialized) playerView.play()
                        wasPausedByFocusLoss = false
                    }
                }
            }
        }
    }

    // ── Media session ─────────────────────────────────────────────────────────
    private lateinit var mediaSession: MediaSessionCompat
    private var mediaTitle: String = "Cove"

    // ── Intent extras ─────────────────────────────────────────────────────────
    private lateinit var streamUrl: String
    private var headers:      String? = null
    private var tmdbId:       Int     = -1
    private var mediaType:    String  = "movie"
    private var season:       Int?    = null
    private var episode:      Int?    = null
    private var originalLang: String? = null

    // ── Settings ──────────────────────────────────────────────────────────────
    private var cachedSettings: Settings? = null
    private val settingsDeferred = CompletableDeferred<Settings?>()
    // Reactive copy of autoPlay so the UpNextCard countdown is driven by Compose state.
    private val stateAutoPlay = mutableStateOf(false)

    // ── Per-file guards (reset on every MPV_EVENT_FILE_LOADED) ───────────────
    private var appliedAudioDefault  = false
    private var appliedSubDefault    = false
    private var resumeDone           = false
    private var subtitlesAdded       = false
    private var nextEpResolved       = false
    private var advanced             = false
    private val autoSkippedSegments  = mutableSetOf<String>()

    // ── Buffering watchdog ────────────────────────────────────────────────────
    private var bufferingStartPos    = 0.0
    private var bufferingWatchdog:   Runnable? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // WindowCompat handles all API levels; window.decorView also forces decor
        // creation — window.insetsController NPEs before setContent otherwise.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Parse intent extras
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            Log.e(TAG, "No stream URL — finishing")
            finish()
            return
        }
        headers      = intent.getStringExtra(EXTRA_HEADERS)
        tmdbId       = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        mediaType    = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
        mediaTitle   = intent.getStringExtra(EXTRA_TITLE) ?: "Cove"
        originalLang = intent.getStringExtra(EXTRA_ORIGINAL_LANG)
        val s = intent.getIntExtra(EXTRA_SEASON, -1)
        val e = intent.getIntExtra(EXTRA_EPISODE, -1)
        season  = if (s > 0) s else null
        episode = if (e > 0) e else null

        Log.i(TAG, "Playing: $streamUrl  tmdbId=$tmdbId type=$mediaType s=$season ep=$episode origLang=$originalLang")

        // Fetch settings once; settingsDeferred allows FILE_LOADED handlers to await it.
        lifecycleScope.launch(Dispatchers.IO) {
            val s = try { CoveApiClient.getOrNull<Settings>("/settings") } catch (_: Exception) { null }
            cachedSettings = s
            settingsDeferred.complete(s)
            // Push reactive values to Compose state and retry auto-select in case tracks
            // arrived (via track-list property) before settings finished loading.
            mainHandler.post {
                stateAutoPlay.value = s?.autoPlay ?: false
                tryAutoSelectTracks()
            }
        }

        // Audio focus
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        audioManager.requestAudioFocus(audioFocusRequest)

        // MPV
        playerView = MpvPlayerView(this)
        playerView.create()
        MPVLib.addObserver(this)

        // Media session
        mediaSession = MediaSessionCompat(this, "CovePlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()            { playerView.play() }
                override fun onPause()           { playerView.pause() }
                override fun onSeekTo(pos: Long) { playerView.seekTo(pos / 1000.0) }
            })
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle)
                    .build()
            )
            isActive = true
        }

        setContent {
            CoveTheme {
                PlayerScreen(
                    playerView         = playerView,
                    timePos            = stateTimePos.value,
                    duration           = stateDuration.value,
                    isPaused           = stateIsPaused.value,
                    buffering          = stateBuffering.value,
                    showControls       = stateShowCtrl.value,
                    controlsRevision   = stateCtrlRev.value,
                    showError          = stateShowError.value,
                    audioTracks        = stateAudioTracks.value,
                    subTracks          = stateSubTracks.value,
                    speed              = stateSpeed.value,
                    activeSegment      = stateActiveSegment.value,
                    nextEp             = stateNextEp.value,
                    upNextDismissed    = stateUpNextDismissed.value,
                    autoPlay           = stateAutoPlay.value,
                    isEof              = stateIsEof.value,
                    onToggleControls   = {
                        if (stateShowCtrl.value) {
                            stateShowCtrl.value = false
                        } else {
                            stateShowCtrl.value = true
                            stateCtrlRev.value++
                        }
                    },
                    onShowControls     = { stateShowCtrl.value = true; stateCtrlRev.value++ },
                    onHideControls     = { stateShowCtrl.value = false },
                    onPlayPause        = { playerView.togglePause(); stateShowCtrl.value = true; stateCtrlRev.value++ },
                    onSeek             = { pos -> playerView.seekTo(pos); stateShowCtrl.value = true; stateCtrlRev.value++ },
                    onSkipSegment      = {
                        val seg = stateActiveSegment.value
                        if (seg != null) {
                            val seekTo = (seg.endMs ?: (currentDuration * 1000).toLong()) / 1000.0
                            playerView.seekTo(seekTo)
                        }
                    },
                    onSetSpeed         = { spd ->
                        stateSpeed.value = spd
                        playerView.setSpeed(spd)
                    },
                    onSetAudioTrack    = { id -> MPVLib.command(arrayOf("set", "aid", id.toString())) },
                    onSetSubTrack      = { id ->
                        if (id == null) MPVLib.command(arrayOf("set", "sid", "no"))
                        else MPVLib.command(arrayOf("set", "sid", id.toString()))
                    },
                    onAdvance          = { advance() },
                    onDismissUpNext    = { stateUpNextDismissed.value = true },
                    onBack             = { finish() },
                )
            }
        }

        playerView.loadFile(streamUrl, headers)
    }

    override fun onPause() {
        super.onPause()
        if (::playerView.isInitialized) playerView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        bufferingWatchdog?.let { mainHandler.removeCallbacks(it) }
        if (currentDuration > 0.0 && currentTimePos >= 5.0) {
            val completed = currentTimePos / currentDuration >= 0.9
            doSave(currentTimePos, currentDuration, completed)
            SyncCoordinator.syncAfterMutation()
        }
        if (::audioFocusRequest.isInitialized) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        MPVLib.removeObserver(this)
        playerView.destroy()
    }

    // ── MPVLib.EventObserver ──────────────────────────────────────────────────

    override fun eventProperty(name: String) {}

    override fun eventProperty(name: String, value: Long) {}

    override fun eventProperty(name: String, value: Double) {
        mainHandler.post {
            when (name) {
                "time-pos" -> {
                    stateTimePos.value = value
                    currentTimePos     = value

                    // Buffering watchdog: if position advanced, cancel or reset the timer.
                    if (stateBuffering.value && value != bufferingStartPos) {
                        bufferingWatchdog?.let { mainHandler.removeCallbacks(it) }
                        bufferingWatchdog = null
                        // Don't clear showError here — the buffering state handles that
                    }

                    // Active segment computation
                    val ts = stateTimestamps.value
                    val posMs = (value * 1000.0).toLong()
                    val seg = if (ts != null) computeActiveSegment(ts, posMs, currentDuration) else null
                    stateActiveSegment.value = seg

                    // Auto-skip
                    if (seg != null) {
                        val settings = cachedSettings
                        if (settings != null) {
                            val segKey = "${seg.type}-${seg.endMs ?: 0L}"
                            if (!autoSkippedSegments.contains(segKey)) {
                                val shouldSkip = when (seg.type) {
                                    "intro"   -> settings.autoSkipIntro
                                    "recap"   -> settings.autoSkipRecap
                                    "credits" -> settings.autoSkipCredits
                                    "preview" -> settings.autoSkipPreview
                                    else      -> false
                                }
                                if (shouldSkip) {
                                    autoSkippedSegments.add(segKey)
                                    val seekTo = (seg.endMs ?: (currentDuration * 1000).toLong()) / 1000.0
                                    playerView.seekTo(seekTo)
                                }
                            }
                        }
                    }

                    if (!stateIsPaused.value && !stateBuffering.value) {
                        maybeSaveProgress(value, currentDuration)
                    }

                    // Resolve next episode once per file when duration is known
                    if (!nextEpResolved && mediaType == "tv" && currentDuration > 0.0
                        && tmdbId >= 0 && season != null && episode != null) {
                        nextEpResolved = true
                        val s = season!!; val ep = episode!!
                        lifecycleScope.launch {
                            val next = NextEpisodeResolver.resolve(tmdbId, s, ep)
                            stateNextEp.value = next
                        }
                    }
                }
                "duration" -> {
                    stateDuration.value = value
                    currentDuration     = value
                    if (::mediaSession.isInitialized) {
                        mediaSession.setMetadata(
                            MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle)
                                .putLong(
                                    MediaMetadataCompat.METADATA_KEY_DURATION,
                                    (value * 1000.0).toLong(),
                                )
                                .build()
                        )
                    }
                }
            }
        }
    }

    override fun eventProperty(name: String, value: Boolean) {
        mainHandler.post {
            when (name) {
                "pause" -> {
                    stateIsPaused.value = value
                    updatePlaybackState()
                }
                "eof-reached" -> if (value) {
                    stateIsEof.value = true
                    Log.d(TAG, "EOF — saving completion")
                    val dur = currentDuration
                    val pos = currentTimePos
                    if (dur > 0.0 && pos >= 5.0) doSave(pos, dur, completed = true)
                }
                "paused-for-cache" -> {
                    stateBuffering.value = value
                    if (value) {
                        // Arm 25s watchdog: if still buffering at same position → error
                        bufferingStartPos = currentTimePos
                        bufferingWatchdog?.let { mainHandler.removeCallbacks(it) }
                        val watchdog = Runnable {
                            if (stateBuffering.value && currentTimePos == bufferingStartPos) {
                                Log.w(TAG, "Buffering watchdog fired — showing error")
                                stateShowError.value = true
                            }
                        }
                        bufferingWatchdog = watchdog
                        mainHandler.postDelayed(watchdog, 25_000L)
                    } else {
                        bufferingWatchdog?.let { mainHandler.removeCallbacks(it) }
                        bufferingWatchdog = null
                        stateShowError.value = false
                    }
                    updatePlaybackState()
                }
            }
        }
    }

    override fun eventProperty(name: String, value: String) {
        if (name != "track-list") return
        mainHandler.post {
            val (audio, sub) = parseTrackList(value)
            stateAudioTracks.value = audio
            stateSubTracks.value   = sub
            tryAutoSelectTracks()
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                Log.d(TAG, "MPV_EVENT_FILE_LOADED")
                mainHandler.post {
                    // Reset per-file guards
                    appliedAudioDefault  = false
                    appliedSubDefault    = false
                    resumeDone           = false
                    subtitlesAdded       = false
                    nextEpResolved       = false
                    advanced             = false
                    autoSkippedSegments.clear()
                    stateIsEof.value     = false
                    stateTimestamps.value = null
                    stateActiveSegment.value = null
                    stateNextEp.value    = null
                    stateUpNextDismissed.value = false
                    stateShowError.value = false
                }
                lifecycleScope.launch {
                    fetchSubtitles()
                    fetchTimestamps()
                    tryResumePosition()
                }
            }
            MPVLib.MPV_EVENT_END_FILE -> Log.d(TAG, "MPV_EVENT_END_FILE")
            MPVLib.MPV_EVENT_PLAYBACK_RESTART -> {
                Log.d(TAG, "MPV_EVENT_PLAYBACK_RESTART")
                mainHandler.post { updatePlaybackState() }
            }
        }
    }

    // ── Track-list parsing ────────────────────────────────────────────────────

    private fun parseTrackList(json: String): Pair<List<MpvTrack>, List<MpvTrack>> {
        val audio = mutableListOf<MpvTrack>()
        val sub   = mutableListOf<MpvTrack>()
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj  = arr.getJSONObject(i)
                val type = obj.optString("type")
                if (type != "audio" && type != "sub") continue
                val track = MpvTrack(
                    id       = obj.optInt("id"),
                    type     = type,
                    title    = obj.optString("title"),
                    lang     = obj.optString("lang"),
                    selected = obj.optBoolean("selected"),
                )
                if (type == "audio") audio.add(track) else sub.add(track)
            }
            Pair(audio, sub)
        } catch (e: Exception) {
            Log.w(TAG, "parseTrackList failed: ${e.message}")
            Pair(audio, sub)
        }
    }

    // ── Language auto-select ──────────────────────────────────────────────────

    /** Attempts audio + subtitle auto-select. Safe to call multiple times — guards ensure
     * it acts at most once per file for each track type. Must be called on main thread. */
    private fun tryAutoSelectTracks() {
        val settings = cachedSettings ?: return

        // Audio auto-select: only when there are multiple tracks to choose from
        if (!appliedAudioDefault && stateAudioTracks.value.size > 1) {
            val setting = settings.defaultAudioLang
            val targetLang: String? = when {
                setting == "original" -> {
                    val ol = originalLang
                    if (ol == null) return  // originalLang not yet known — retry on next call
                    ol.ifEmpty { null }     // empty = title has no original_language, nothing to match
                }
                else -> setting.ifEmpty { null }
            }
            appliedAudioDefault = true   // mark even if targetLang is null (nothing to do)
            if (targetLang != null) {
                val match = stateAudioTracks.value.find { LangUtil.langMatches(it.lang, targetLang) }
                if (match != null && !match.selected) {
                    MPVLib.command(arrayOf("set", "aid", match.id.toString()))
                    Log.d(TAG, "auto-select audio: ${match.id} (${match.lang}) for pref=$setting")
                }
            }
        }

        // Subtitle auto-select: only when subtitles are enabled
        if (!appliedSubDefault && settings.subtitlesEnabled && stateSubTracks.value.isNotEmpty()) {
            appliedSubDefault = true
            val lang = settings.defaultSubtitleLang
            val match = stateSubTracks.value.find { LangUtil.langMatches(it.lang, lang) }
            if (match != null) {
                MPVLib.command(arrayOf("set", "sid", match.id.toString()))
                Log.d(TAG, "auto-select sub: ${match.id} (${match.lang}) for pref=$lang")
            } else {
                // No matching sub — leave off (don't force random language)
                MPVLib.command(arrayOf("set", "sid", "no"))
            }
        }
    }

    // ── External subtitles ────────────────────────────────────────────────────

    private suspend fun fetchSubtitles() {
        if (subtitlesAdded) return
        if (tmdbId < 0) return
        val path = buildString {
            append("/subtitles?id=$tmdbId&type=$mediaType")
            if (season != null)  append("&season=${season}")
            if (episode != null) append("&episode=${episode}")
        }
        val subs = try {
            CoveApiClient.getOrNull<List<SubtitleDto>>(path) ?: return
        } catch (e: Exception) {
            Log.w(TAG, "fetchSubtitles failed: ${e.message}")
            return
        }
        withContext(Dispatchers.Main) {
            subtitlesAdded = true
            for (sub in subs) {
                val encodedUrl = URLEncoder.encode(sub.url, "UTF-8")
                val proxyUrl   = "${CoveApiClient.BASE}/subtitle-proxy?url=$encodedUrl"
                // mpv sub-add: url, flags, title, lang  (mirrors desktop Player.addSubtitle)
                MPVLib.command(arrayOf("sub-add", proxyUrl, "cached", sub.lang.uppercase(), sub.lang))
                Log.d(TAG, "sub-add: ${sub.lang} → $proxyUrl")
            }
            // Re-attempt subtitle auto-select now that external tracks are loaded
            if (!appliedSubDefault) tryAutoSelectTracks()
        }
    }

    // ── Timestamps (IntroDB) ──────────────────────────────────────────────────

    private suspend fun fetchTimestamps() {
        if (tmdbId < 0) return
        val path = buildString {
            append("/timestamps?id=$tmdbId")
            if (season != null)  append("&season=${season}")
            if (episode != null) append("&episode=${episode}")
        }
        val ts = try {
            CoveApiClient.getOrNull<TimestampData>(path)
        } catch (e: Exception) {
            Log.w(TAG, "fetchTimestamps failed: ${e.message}")
            null
        }
        withContext(Dispatchers.Main) {
            stateTimestamps.value = ts
            Log.d(TAG, "timestamps loaded: intro=${ts?.intro?.size} recap=${ts?.recap?.size} credits=${ts?.credits?.size}")
        }
    }

    private fun computeActiveSegment(ts: TimestampData, posMs: Long, dur: Double): ActiveSegment? {
        val durMs = (dur * 1000.0).toLong()
        fun check(segs: List<TimestampSegment>?, type: String, label: String): ActiveSegment? {
            if (segs.isNullOrEmpty()) return null
            for (seg in segs) {
                val start = seg.startMs ?: 0L
                val end   = seg.endMs   ?: durMs
                if (posMs >= start && posMs < end) return ActiveSegment(type, label, seg.endMs)
            }
            return null
        }
        // Priority: recap > intro > credits > preview (matches desktop)
        return check(ts.recap, "recap", "Recap")
            ?: check(ts.intro, "intro", "Intro")
            ?: check(ts.credits, "credits", "Credits")
            ?: check(ts.preview, "preview", "Preview")
    }

    // ── Resume position ───────────────────────────────────────────────────────

    private suspend fun tryResumePosition() {
        // Await settings so we don't race the onCreate fetch
        val settings = settingsDeferred.await()
        if (settings?.rememberPosition == false) return
        if (tmdbId < 0 || resumeDone) return

        val path = buildString {
            append("/library/progress?tmdb_id=$tmdbId&media_type=$mediaType")
            if (season != null)  append("&season=${season}")
            if (episode != null) append("&episode=${episode}")
        }
        val progress = try {
            CoveApiClient.getOrNull<WatchProgress>(path)
        } catch (e: Exception) {
            Log.w(TAG, "fetchProgress failed: ${e.message}")
            null
        } ?: return

        if (!progress.completed && progress.positionSeconds > 10.0) {
            resumeDone = true
            Log.d(TAG, "resuming at ${progress.positionSeconds}s")
            // Short delay for demuxer readiness after file-loaded
            delay(500L)
            withContext(Dispatchers.Main) {
                playerView.seekTo(progress.positionSeconds)
            }
        }
    }

    // ── Auto-advance (up-next) ────────────────────────────────────────────────

    private fun advance() {
        if (advanced) return
        advanced = true

        val nextEp = stateNextEp.value ?: return

        // Mark current episode complete before leaving
        if (currentDuration > 0.0) doSave(currentDuration, currentDuration, completed = true)

        lifecycleScope.launch(Dispatchers.IO) {
            val path = "/streams?id=$tmdbId&type=tv&season=${nextEp.season}&episode=${nextEp.episodeNumber}"
            val streams: List<Stream> = try {
                CoveApiClient.getOrNull<List<Stream>>(path) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "advance: streams fetch failed: ${e.message}")
                emptyList()
            }

            if (streams.isEmpty()) {
                Log.w(TAG, "advance: no streams for S${nextEp.season}E${nextEp.episodeNumber}")
                return@launch
            }

            val best = rankStreams(streams).firstOrNull() ?: return@launch
            val src  = if (best.infoHash.isNotEmpty()) best.infoHash else best.url
            val playUrl = CoveApiClient.playUrl(src, nextEp.season, nextEp.episodeNumber)

            // Derive new title by stripping trailing " S\d+E\d+" and appending new S/E
            val baseTitle = mediaTitle.replace(Regex(""" S\d+E\d+$"""), "")
            val newTitle  = "$baseTitle S${nextEp.season}E${nextEp.episodeNumber}"

            withContext(Dispatchers.Main) {
                val intent = Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_STREAM_URL,  playUrl)
                    putExtra(EXTRA_TMDB_ID,     tmdbId)
                    putExtra(EXTRA_MEDIA_TYPE,  "tv")
                    putExtra(EXTRA_SEASON,      nextEp.season)
                    putExtra(EXTRA_EPISODE,     nextEp.episodeNumber)
                    putExtra(EXTRA_TITLE,       newTitle)
                    originalLang?.let { putExtra(EXTRA_ORIGINAL_LANG, it) }
                    if (best.headers.isNotEmpty()) {
                        val hdr = best.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        putExtra(EXTRA_HEADERS, hdr)
                    }
                }
                startActivity(intent)
                finish()
            }
        }
    }

    // ── MediaSession helpers ──────────────────────────────────────────────────

    private fun updatePlaybackState() {
        if (!::mediaSession.isInitialized) return
        val state = when {
            stateBuffering.value -> PlaybackStateCompat.STATE_BUFFERING
            stateIsPaused.value  -> PlaybackStateCompat.STATE_PAUSED
            else                 -> PlaybackStateCompat.STATE_PLAYING
        }
        val posMs  = (currentTimePos * 1000.0).toLong()
        val speed  = if (stateIsPaused.value) 0f else stateSpeed.value
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, posMs, speed)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_PLAY_PAUSE
                        or PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .build()
        )
    }

    // ── Progress (mirrors progressSaver.svelte.ts) ────────────────────────────

    private fun maybeSaveProgress(pos: Double, dur: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSaveMs < 10_000L) return
        lastSaveMs = now
        doSave(pos, dur, completed = dur > 0.0 && pos / dur >= 0.9)
    }

    private fun doSave(pos: Double, dur: Double, completed: Boolean) {
        if (dur <= 0.0 || pos < 5.0) return
        if (tmdbId < 0)               return
        if (completedSaved && !completed) return

        if (completed) completedSaved = true

        val posSeconds = if (completed) dur else pos

        val body = JSONObject().apply {
            put("tmdb_id",          tmdbId)
            put("media_type",       mediaType)
            if (season  != null) put("season",  season)
            if (episode != null) put("episode", episode)
            put("position_seconds", posSeconds)
            put("duration_seconds", dur)
            put("completed",        completed)
        }.toString()

        Log.d(TAG, "progress save: pos=${posSeconds}s dur=${dur}s completed=$completed")

        Thread {
            try {
                val req = Request.Builder()
                    .url("${CoveApiClient.BASE}/library/progress")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                CoveApiClient.http.newCall(req).execute().use { resp ->
                    Log.d(TAG, "progress HTTP ${resp.code}")
                }
            } catch (ex: IOException) {
                Log.w(TAG, "progress save failed: ${ex.message}")
            }
        }.start()
    }

    companion object {
        const val EXTRA_STREAM_URL    = "stream_url"
        const val EXTRA_HEADERS       = "headers"
        const val EXTRA_TMDB_ID       = "tmdb_id"
        const val EXTRA_MEDIA_TYPE    = "media_type"
        const val EXTRA_SEASON        = "season"
        const val EXTRA_EPISODE       = "episode"
        const val EXTRA_TITLE         = "title"
        /**
         * ISO 639-1 original language of the title (e.g. "ja" for an anime).
         * Used for audio auto-select when defaultAudioLang == "original".
         * CALLER WIRING: MediaDetailSheet / StreamsSheet must pass
         *   intent.putExtra(PlayerActivity.EXTRA_ORIGINAL_LANG, media.originalLanguage ?: "")
         * The Media DTO already has originalLanguage from Dtos.kt. This extra is
         * optional — when absent, "original" preference is silently skipped for this title.
         */
        const val EXTRA_ORIGINAL_LANG = "original_lang"
        private const val TAG         = "PlayerActivity"
    }
}

// ── Composable UI ─────────────────────────────────────────────────────────────

@Composable
private fun PlayerScreen(
    playerView: MpvPlayerView,
    // Playback state
    timePos: Double,
    duration: Double,
    isPaused: Boolean,
    buffering: Boolean,
    showControls: Boolean,
    controlsRevision: Int,
    showError: Boolean,
    // Track state
    audioTracks: List<MpvTrack>,
    subTracks: List<MpvTrack>,
    speed: Float,
    // Currently-active IntroDB segment (already computed by Activity from timestamps + time-pos)
    activeSegment: ActiveSegment?,
    // Up-next
    nextEp: NextEp?,
    upNextDismissed: Boolean,
    autoPlay: Boolean,
    isEof: Boolean,
    // Callbacks
    onToggleControls: () -> Unit,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSkipSegment: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetAudioTrack: (Int) -> Unit,
    onSetSubTrack: (Int?) -> Unit,
    onAdvance: () -> Unit,
    onDismissUpNext: () -> Unit,
    onBack: () -> Unit,
) {
    // ── Local UI state ────────────────────────────────────────────────────────
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var audioPickerOpen by remember { mutableStateOf(false) }
    var subPickerOpen   by remember { mutableStateOf(false) }
    var speedPickerOpen by remember { mutableStateOf(false) }

    // Auto-hide controls: 3s after controls become visible while playing
    LaunchedEffect(showControls, controlsRevision, isPaused) {
        if (showControls && !isPaused) {
            delay(3_000L)
            onHideControls()
        }
    }

    // Seek flash: clear after 700 ms (mirrors desktop flash())
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(700L)
            seekFlash = null
        }
    }

    // Up-next: computed from remaining time / segment / eof
    val showUpNext = nextEp != null && !upNextDismissed && (
        activeSegment?.type == "credits" ||
        (duration > 0 && duration - timePos < 40.0) ||
        isEof
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) {
                            onSeek((timePos - 10.0).coerceAtLeast(0.0))
                            seekFlash = "−10s"
                        } else {
                            onSeek(if (duration > 0.0) (timePos + 10.0).coerceAtMost(duration) else timePos + 10.0)
                            seekFlash = "+10s"
                        }
                        onShowControls()
                    },
                )
            }
    ) {
        // MPV surface
        AndroidView(
            factory  = { playerView },
            modifier = Modifier.fillMaxSize(),
        )

        // Buffering spinner (only when buffering but not yet in watchdog-error)
        if (buffering && !showError) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White,
            )
        }

        // Buffering watchdog error
        if (showError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.80f)),
                verticalArrangement   = Arrangement.Center,
                horizontalAlignment   = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector         = Icons.Filled.Warning,
                    contentDescription  = null,
                    tint                = Color.White,
                    modifier            = Modifier.size(48.dp),
                )
                Text(
                    text  = "Could not load stream",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = onBack) {
                    Text("Go back", color = Color.White)
                }
            }
        }

        // Seek flash (double-tap feedback)
        if (seekFlash != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.65f), shape = MaterialTheme.shapes.large)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text  = seekFlash!!,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        // ── Controls overlay (dismissed on single-tap, auto-hides after 3s) ──
        if (showControls) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top bar: back
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onBack) {
                        Text("← Back", color = Color.White,
                             style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Bottom: seekbar + transport row
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
                    // Transport row: Replay10, PlayPause, Forward10, time, spacer, Audio, Sub, Speed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.fillMaxWidth(),
                    ) {
                        // ← Replay 10
                        IconButton(onClick = {
                            onSeek((timePos - 10.0).coerceAtLeast(0.0))
                            onShowControls()
                        }) {
                            Icon(Icons.Filled.Replay10, contentDescription = "-10s", tint = Color.White)
                        }
                        // Play / Pause
                        IconButton(onClick = onPlayPause) {
                            Icon(
                                imageVector        = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (isPaused) "Play" else "Pause",
                                tint               = Color.White,
                                modifier           = Modifier.size(36.dp),
                            )
                        }
                        // → Forward 10
                        IconButton(onClick = {
                            val target = if (duration > 0.0) (timePos + 10.0).coerceAtMost(duration) else timePos + 10.0
                            onSeek(target)
                            onShowControls()
                        }) {
                            Icon(Icons.Filled.Forward10, contentDescription = "+10s", tint = Color.White)
                        }

                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${formatTime(timePos)} / ${formatTime(duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        Spacer(Modifier.weight(1f))

                        // Audio track picker
                        if (audioTracks.isNotEmpty()) {
                            IconButton(onClick = { audioPickerOpen = true }) {
                                Icon(Icons.Filled.Audiotrack, contentDescription = "Audio track", tint = Color.White)
                            }
                        }

                        // Subtitle track picker
                        if (subTracks.isNotEmpty()) {
                            IconButton(onClick = { subPickerOpen = true }) {
                                Icon(Icons.Filled.ClosedCaption, contentDescription = "Subtitles", tint = Color.White)
                            }
                        }

                        // Speed picker
                        IconButton(onClick = { speedPickerOpen = true }) {
                            Icon(Icons.Filled.Speed, contentDescription = "Playback speed", tint = Color.White)
                        }
                    }
                }
            }
        }

        // ── Skip segment button (always visible when in a segment, not gated on controls) ──
        if (activeSegment != null) {
            Button(
                onClick  = onSkipSegment,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor   = Color.White,
                ),
            ) {
                Text(
                    "Skip ${activeSegment.label}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // ── Up-next overlay (TV only) ─────────────────────────────────────────
        if (showUpNext && nextEp != null) {
            UpNextCard(
                nextEp    = nextEp,
                autoPlay  = autoPlay,
                modifier  = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (activeSegment != null) 152.dp else 96.dp),
                onWatch   = onAdvance,
                onDismiss = onDismissUpNext,
            )
        }
    }

    // ── Dialogs (rendered outside the Box so they float above everything) ─────

    if (audioPickerOpen) {
        TrackPickerDialog(
            title    = "Audio Track",
            tracks   = audioTracks,
            offEntry = false,
            onSelect = { id -> onSetAudioTrack(id!!); audioPickerOpen = false },
            onDismiss = { audioPickerOpen = false },
        )
    }

    if (subPickerOpen) {
        TrackPickerDialog(
            title    = "Subtitles",
            tracks   = subTracks,
            offEntry = true,
            onSelect = { id -> onSetSubTrack(id); subPickerOpen = false },
            onDismiss = { subPickerOpen = false },
        )
    }

    if (speedPickerOpen) {
        SpeedPickerDialog(
            currentSpeed = speed,
            onSelect     = { spd -> onSetSpeed(spd); speedPickerOpen = false },
            onDismiss    = { speedPickerOpen = false },
        )
    }
}

// ── Up-next card ──────────────────────────────────────────────────────────────

@Composable
private fun UpNextCard(
    nextEp: NextEp,
    autoPlay: Boolean,
    modifier: Modifier,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var countdownSecs by remember { mutableStateOf<Int?>(null) }
    var advanceCalled by remember { mutableStateOf(false) }

    LaunchedEffect(autoPlay) {
        if (!autoPlay) { countdownSecs = null; return@LaunchedEffect }
        var secs = 10
        while (secs > 0) {
            countdownSecs = secs
            delay(1_000L)
            secs--
        }
        countdownSecs = null
        if (!advanceCalled) { advanceCalled = true; onWatch() }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.80f), shape = MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "Up next · S${nextEp.season}E${nextEp.episodeNumber}",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("✕", color = Color.White.copy(alpha = 0.60f))
            }
        }
        if (nextEp.name.isNotBlank()) {
            Text(
                text  = nextEp.name,
                color = Color.White.copy(alpha = 0.90f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        TextButton(
            onClick = { if (!advanceCalled) { advanceCalled = true; onWatch() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            val label = if (countdownSecs != null) "Watch now ($countdownSecs)" else "Watch now"
            Text(label, color = Color.White)
        }
        if (countdownSecs != null) {
            LinearProgressIndicator(
                progress      = { ((10 - (countdownSecs ?: 0)).toFloat() / 10f).coerceIn(0f, 1f) },
                modifier      = Modifier.fillMaxWidth(),
                color         = Color.White,
                trackColor    = Color.White.copy(alpha = 0.25f),
            )
        }
    }
}

// ── Track picker dialog ───────────────────────────────────────────────────────

@Composable
private fun TrackPickerDialog(
    title: String,
    tracks: List<MpvTrack>,
    offEntry: Boolean,
    onSelect: (Int?) -> Unit,   // null = "Off" for subtitles
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                if (offEntry) {
                    item {
                        TrackRow(label = "Off", selected = tracks.none { it.selected }) {
                            onSelect(null)
                        }
                    }
                }
                items(tracks) { track ->
                    val label = when {
                        track.title.isNotBlank() -> track.title
                        track.lang.isNotBlank()  -> track.lang.uppercase()
                        else                     -> "${title} ${track.id}"
                    }
                    TrackRow(label = label, selected = track.selected) {
                        onSelect(track.id)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
            colors   = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

// ── Speed picker dialog ───────────────────────────────────────────────────────

@Composable
private fun SpeedPickerDialog(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback Speed") },
        text = {
            Column {
                speeds.forEach { spd ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = spd == currentSpeed,
                            onClick  = { onSelect(spd) },
                            colors   = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                        )
                        val label = if (spd == 1f) "1× (Normal)" else "${spd}×"
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ── Time formatting ───────────────────────────────────────────────────────────

private fun formatTime(seconds: Double): String {
    val s   = seconds.toLong().coerceAtLeast(0L)
    val h   = s / 3600
    val m   = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
    else "%d:%02d".format(m, sec)
}
