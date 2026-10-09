package com.coveninja.cove.desktop.player

import com.coveninja.cove.ui.state.MAX_VOLUME
import com.coveninja.cove.ui.state.RECONNECT_STREAM_OPTIONS
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * In-process libmpv player backed by mpv's software render API.
 *
 * Decoded frames land directly in a persistent Skia bitmap as rgb0 pixels.
 * Compose draws that same allocation, so there is no frame-sized AWT image or
 * per-pixel conversion between mpv and the UI. No GPU interop or Swing embedding.
 */
class MpvSoftwarePlayer internal constructor(
    // Software *rendering*, not necessarily software decoding. A copy-back decoder
    // keeps the decode on the GPU and copies finished frames back to system memory,
    // which is what this path needs and is far cheaper than decoding on the CPU. mpv
    // falls back to software decode by itself when none of them takes the file; see
    // COPY_BACK_DECODERS for which.
    private val hardwareDecoding: Boolean = true,
    /**
     * Where mpv's ytdl hook should look for yt-dlp, `:`-separated (`;` on Windows).
     * Null leaves mpv to its own defaults, which search PATH.
     */
    private val ytdlSearchPath: String? = null,
    /** What the hook asks yt-dlp for; null leaves mpv's default. See [YTDL_FORMAT]. */
    private val ytdlFormat: String? = null,
    /** Flags the hook passes yt-dlp itself; null passes none. See [ytdlRawOptions]. */
    private val ytdlRawOptions: String? = null,
    /**
     * Where `screenshot` writes. Null leaves mpv's own default, which is the *process working
     * directory* — for a packaged launch that is wherever the desktop entry started Cove, so
     * every screenshot went somewhere unfindable or failed silently against a read-only
     * install. Android has always set this; the desktop simply never did.
     */
    private val screenshotDirectory: String? = null,
    /**
     * Whether whatever draws the frames scales them on the GPU. When it does, a picture smaller
     * than its surface is rendered at its own resolution and stretched the rest of the way
     * there; see [softwareRenderSize]. When the window itself is drawn in software, that
     * stretch would land on the UI thread instead, so mpv keeps rendering at the surface size.
     */
    private val upscaleOnGpu: Boolean = false,
    private val frameConsumer: (SoftwareVideoFrame) -> Unit,
) : DesktopPlayer {
    private val _snapshot = MutableStateFlow(PlayerSnapshot(renderBackend = "Software"))
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val handle        = AtomicReference<Pointer?>()
    private val renderContext = AtomicReference<Pointer?>()
    private val closing       = AtomicBoolean(false)
    private val renderQueued  = AtomicBoolean(false)
    private val renderWidth   = AtomicInteger(1280)
    private val renderHeight  = AtomicInteger(720)
    // What [renderWidth] and [renderHeight] are derived from: the surface as laid out, the
    // picture's own display size once mpv knows it, and whether the display mode is plain Fit.
    private val surfaceWidth  = AtomicInteger(1280)
    private val surfaceHeight = AtomicInteger(720)
    private val videoWidth    = AtomicInteger(0)
    private val videoHeight   = AtomicInteger(0)
    @Volatile private var plainFit = true
    @Volatile private var copyBackDecoders = COPY_BACK_DECODERS
    @Volatile private var ytdlAvailable = false
    private val frameSequence = AtomicLong(0)
    private val lastRenderNanos = AtomicLong(0)

    private val renderExecutor = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-render"))
    private val eventExecutor  = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-events"))
    private val stateExecutor  = Executors.newSingleThreadScheduledExecutor(namedDaemon("cove-mpv-state"))
    // Every client-API call that mutates mpv goes through here, as it already does in
    // MpvOpenGlPlayer. Two reasons, both of which bit this path: mpv_command blocks
    // until the core accepts the command, so issuing one from the Compose/AWT thread
    // stalls the whole window on a slow network seek; and a single thread gives
    // commands a defined order, which "set start" before "loadfile" depends on.
    private val commandExecutor = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-commands"))

    // The render update callback fires on mpv's internal thread. It must only
    // schedule work and never touch GL or mpv API functions directly.
    private val updateCallback = MpvRenderUpdateCallback { requestRender() }

    private val renderSurface = SoftwareVideoSurface()
    @Volatile private var renderParameters: SoftwareRenderParameters? = null

    @Synchronized
    override fun start() {
        if (handle.get() != null || closing.get()) return

        try {
            val library = Mpv.library()
            // LC_NUMERIC is reset to "C" inside Mpv.create() before mpv_create().
            val created = checkNotNull(Mpv.create()) {
                "mpv_create returned null — is libmpv installed?"
            }
            handle.set(created)

            setOption(library, created, "vo",        "libmpv")
            screenshotDirectory?.let {
                setOption(library, created, "screenshot-directory", it)
            }
            setOption(library, created, "terminal",  "no")
            setOption(library, created, "msg-level", "all=warn")
            setOption(library, created, "keep-open", "yes")
            // Stated rather than left to mpv's identical default, and taken from the same
            // constant the slider is drawn against: the ceiling is enforced in four places on
            // the way to mpv and back, and a default agreeing with them by coincidence is not
            // the same as one that cannot drift from them.
            setOption(library, created, "volume-max", MAX_VOLUME.toInt().toString())
            // libmpv leaves the ytdl hook off where the mpv binary has it on. With it on,
            // a URL mpv cannot open directly is handed to yt-dlp — which is what turns the
            // YouTube page behind a trailer into a playable stream.
            //
            // On, but only for the loads that are pages: the hook fires on load *failure*,
            // so for an ordinary stream it never runs except at the exact moment something
            // has gone wrong — and then it spends a second shelling out to yt-dlp against
            // `http://127.0.0.1:6969/api/play?url=…`, which cannot be extracted, and buries
            // the real reason under "youtube-dl failed: unexpected error occurred". [load]
            // sets this per file; see [ytdlEnabledFor]. Android turns the hook off outright
            // because it resolves pages itself before mpv sees them.
            //
            // Optional, because the hook is a Lua script: a libmpv built without Lua has no
            // ytdl option at all. The Flatpak's mpv is one, and requiring the option there
            // failed every start, so nothing played — not only trailers.
            ytdlAvailable = setOptionalOption(library, created, "ytdl", "yes")
            // All three are set before initialize because ytdl_hook reads them when
            // the script loads. The search path names the managed copy first and then
            // the names mpv would have tried anyway; the format string is there
            // because mpv's own default picks streams YouTube answers with 403, and
            // the raw options are there because the *client* yt-dlp asks by default
            // hands back streams that 403 whatever format is chosen.
            if (ytdlAvailable) {
                ytdlSearchPath?.let { setOption(library, created, "script-opts", "ytdl_hook-ytdl_path=$it") }
                ytdlFormat?.let { setOption(library, created, "ytdl-format", it) }
                ytdlRawOptions?.let { setOption(library, created, "ytdl-raw-options", it) }
            }
            configureHardwareDecoding(library, created)
            // Everything from here to network-timeout was on Android and not here, which is
            // the whole reason a desktop stream that dropped mid-file ended the session where
            // a phone's recovered. See RECONNECT_STREAM_OPTIONS for what the flags do and why
            // reconnect_at_eof is not among them. Set tolerantly, as Android sets it: a libmpv
            // that does not recognise the option must cost the reconnect, not the whole player.
            setOptionalOption(library, created, "stream-lavf-o", RECONNECT_STREAM_OPTIONS)
            setOption(library, created, "cache", "yes")
            setOption(library, created, "demuxer-max-bytes", "32MiB")
            setOption(library, created, "demuxer-readahead-secs", "4")
            setOption(library, created, "cache-pause-initial", "no")
            setOption(library, created, "cache-pause-wait", "2")
            // Explicit rather than left to mpv's default, because the number on the other
            // side of it is one of ours: the torrent engine waits up to its own
            // pieceTimeoutMillis for the pieces under a read, and every one of those waits
            // happens with mpv already blocked on the socket. Whichever of the two is
            // shorter decides what the viewer is told, and the engine's version of the
            // story is the useful one. So this stays comfortably the larger of the pair.
            setOption(library, created, "network-timeout", "90")

            checkMpv(library, library.mpv_initialize(created), "initialize")
            // The only running commentary available while a file is opening.
            library.mpv_request_log_messages(created, "info")

            val context = renderExecutor.submit<Pointer> {
                createSoftwareRenderContext(library, created)
            }.get()
            renderContext.set(context)
            library.mpv_render_context_set_update_callback(context, updateCallback, null)

            _snapshot.value = _snapshot.value.copy(initialized = true, error = null)
            eventExecutor.execute { drainEvents(library, created) }
            stateExecutor.scheduleAtFixedRate(
                { pollState(library, created) },
                0, 200, TimeUnit.MILLISECONDS,
            )
            requestRender()
        } catch (error: Throwable) {
            _snapshot.value = _snapshot.value.copy(
                initialized = false,
                error = error.cause?.message ?: error.message ?: error::class.java.simpleName,
            )
            close()
        }
    }

    override fun load(source: String, startPositionSeconds: Double) {
        _snapshot.value = _snapshot.value.copy(loadError = null)
        // Per file, and before loadfile, because the hook reads it when the load fails.
        // Not at all without the hook: the failed set would surface as a playback error.
        if (ytdlAvailable) command("set", "ytdl", if (ytdlEnabledFor(source)) "yes" else "no")
        // start applies to the next file loaded, so it is set before loadfile
        // rather than passed to it — see mpvLoadFileArgs for why.
        command("set", "start", mpvStartOption(startPositionSeconds))
        command(*mpvLoadFileArgs(source))
    }

    override fun togglePause() = setPaused(!_snapshot.value.paused)

    override fun setPaused(paused: Boolean) {
        submitCommand("set pause") { library, target ->
            val value = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, if (paused) 1 else 0) }
            try {
                library.mpv_set_property(target, "pause", Mpv.FORMAT_FLAG, value)
            } finally {
                Reference.reachabilityFence(value)
            }
        }
    }

    override fun seek(seconds: Double) {
        command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute", "exact")
    }

    override fun setVolume(volume: Double) {
        submitCommand("set volume") { library, target ->
            val value = Memory(Double.SIZE_BYTES.toLong()).apply {
                setDouble(0, volume.coerceIn(0.0, MAX_VOLUME))
            }
            try {
                library.mpv_set_property(target, "volume", Mpv.FORMAT_DOUBLE, value)
            } finally {
                Reference.reachabilityFence(value)
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        submitCommand("set mute") { library, target ->
            val value = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, if (muted) 1 else 0) }
            try {
                library.mpv_set_property(target, "mute", Mpv.FORMAT_FLAG, value)
            } finally {
                Reference.reachabilityFence(value)
            }
        }
    }

    // set, not set-property: mpv accepts "no" for sid, which the typed property
    // setters cannot express. The decoder list is swapped for whatever this libmpv
    // accepted at start; see configureHardwareDecoding.
    override fun setOption(name: String, value: String) = command(
        "set",
        name,
        if (name == "hwdec" && value == COPY_BACK_DECODERS) copyBackDecoders else value,
    )

    // Flags and title are positional. mpv's "auto" means don't select — the track is
    // offered and the slang applied before the load decides — while "select" switches
    // to it at once, which is only ever right for a file the viewer chose themselves.
    override fun addSubtitle(url: String, title: String, language: String, select: Boolean) =
        command("sub-add", url, if (select) "select" else "auto", title, language)

    override fun setScaling(keepAspect: Boolean, panscan: Double, zoom: Double) {
        plainFit = keepAspect && panscan == 0.0 && zoom == 0.0
        updateRenderSize()
        command("set", "keepaspect", if (keepAspect) "yes" else "no")
        command("set", "panscan", panscan.toString())
        command("set", "video-zoom", zoom.toString())
    }

    override fun selectAudioTrack(id: Int) = command("set", "aid", id.toString())

    override fun selectSubtitleTrack(id: Int?) =
        command("set", "sid", id?.toString() ?: "no")

    override fun stop() = command("stop")

    /** Visible for tests: the size mpv is currently asked to render at. */
    internal val renderSize: Pair<Int, Int>
        get() = renderWidth.get() to renderHeight.get()

    fun resize(width: Int, height: Int) {
        surfaceWidth.set(width.coerceIn(1, 8192))
        surfaceHeight.set(height.coerceIn(1, 8192))
        updateRenderSize()
    }

    /** Visible for tests: what mpv reports as the picture's display size, as [pollState] does. */
    internal fun videoSizeChanged(width: Int, height: Int) {
        val widthChanged = videoWidth.getAndSet(width) != width
        val heightChanged = videoHeight.getAndSet(height) != height
        if (widthChanged || heightChanged) updateRenderSize()
    }

    @Synchronized
    private fun updateRenderSize() {
        val (w, h) = softwareRenderSize(
            surfaceWidth = surfaceWidth.get(),
            surfaceHeight = surfaceHeight.get(),
            videoWidth = videoWidth.get(),
            videoHeight = videoHeight.get(),
            mayRenderSmaller = upscaleOnGpu && plainFit,
        )
        // Both stores happen before the comparison on purpose. Folding them into
        // a single `||` short-circuits: when the width changes, the height is
        // never written, and mpv keeps rendering at the old height. That leaves it
        // composing the picture into a target of the wrong shape, which is where
        // the stray letterboxing on a resized or fullscreened window came from.
        val widthChanged = renderWidth.getAndSet(w) != w
        val heightChanged = renderHeight.getAndSet(h) != h
        if (widthChanged || heightChanged) requestRender()
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return

        stateExecutor.shutdownNow()
        // Before the handle is dropped, so a command already inside mpv finishes
        // against a live handle rather than racing mpv_terminate_destroy. `closing`
        // is already set, so submitCommand refuses new work; shutdownNow discards
        // whatever is merely queued, which at teardown is only stale seeks.
        commandExecutor.shutdownNow()
        runCatching { commandExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        val target = handle.getAndSet(null)
        target?.let { runCatching { Mpv.library().mpv_wakeup(it) } }

        eventExecutor.shutdown()
        runCatching { eventExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        val context = renderContext.get()
        if (context != null) {
            runCatching {
                Mpv.library().mpv_render_context_set_update_callback(context, null, null)
            }
            runCatching {
                renderExecutor.submit {
                    renderContext.getAndSet(null)
                        ?.let(Mpv.library()::mpv_render_context_free)
                    renderParameters?.close()
                    renderParameters = null
                    renderSurface.close()
                }.get(3, TimeUnit.SECONDS)
            }
        } else {
            renderSurface.close()
        }
        renderExecutor.shutdown()
        runCatching { renderExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        // shutdownNow interrupts, but a poll already inside mpv is making twenty
        // uninterruptible native calls against a handle it captured at schedule
        // time, and it only tests `closing` on entry. Destroying underneath it
        // frees the handle mid-read. Awaited here rather than beside the
        // shutdownNow above so the rest of the teardown drains it in parallel.
        runCatching { stateExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        target?.let { runCatching { Mpv.library().mpv_terminate_destroy(it) } }
        _snapshot.value = _snapshot.value.copy(initialized = false, hasMedia = false, paused = true)
    }

    private fun requestRender() {
        if (closing.get() || !renderQueued.compareAndSet(false, true)) return
        renderExecutor.execute {
            try {
                renderFrame()
            } catch (error: Throwable) {
                _snapshot.value = _snapshot.value.copy(error = "Render failed: ${error.message}")
            } finally {
                renderQueued.set(false)
                val ctx = renderContext.get()
                if (ctx != null && !closing.get() &&
                    Mpv.library().mpv_render_context_update(ctx) and Mpv.RENDER_UPDATE_FRAME != 0L
                ) {
                    requestRender()
                }
            }
        }
    }

    private fun renderFrame() {
        val context = renderContext.get() ?: return
        val width   = renderWidth.get()
        val height  = renderHeight.get()

        // mpv receives raw pointers nested inside the render-param array. JNA
        // cannot see those pointees while the native call is in progress, so a
        // local owner can become unreachable and its Cleaner can free it before
        // mpv returns. Keep the parameters for the context lifetime; the Skia
        // surface holds its bitmap and draw/write lock across the native call.
        val parameters = renderParameters
            ?: SoftwareRenderParameters().also { renderParameters = it }

        val library = Mpv.library()
        library.mpv_render_context_update(context)
        val started = System.nanoTime()
        renderSurface.render(width, height) { target, stride ->
            parameters.configure(width, height, target, stride)
            try {
                checkMpv(
                    library,
                    library.mpv_render_context_render(context, parameters.pointer),
                    "sw render frame",
                )
            } finally {
                parameters.keepAlive()
            }
        }
        lastRenderNanos.set(System.nanoTime() - started)
        frameConsumer(SoftwareVideoFrame(renderSurface, frameSequence.incrementAndGet()))
    }

    private fun drainEvents(library: MpvLibrary, target: Pointer) {
        while (!closing.get()) {
            val event = MpvEvent(library.mpv_wait_event(target, 0.1))
            when (event.eventId) {
                Mpv.EVENT_SHUTDOWN -> break
                Mpv.EVENT_START_FILE -> {
                    loggedDecoder = null
                    _snapshot.value = _snapshot.value.copy(fileLoaded = false)
                }

                Mpv.EVENT_FILE_LOADED ->
                    _snapshot.value = _snapshot.value.copy(fileLoaded = true)

                Mpv.EVENT_END_FILE -> {
                    // reason distinguishes "played to the end" from "could not be opened"; only
                    // the latter is something to tell the viewer about.
                    val failure = event.data
                        ?.let { runCatching { MpvEndFile(it) }.getOrNull() }
                        ?.takeIf { it.reason == Mpv.END_FILE_REASON_ERROR }
                        ?.let { Mpv.library().mpv_error_string(it.error) }
                        ?.takeIf(String::isNotBlank)
                    _snapshot.value = _snapshot.value.copy(
                        fileLoaded = false,
                        loadError = failure ?: _snapshot.value.loadError,
                    )
                }

                Mpv.EVENT_LOG_MESSAGE -> event.data?.let { pointer ->
                    val log = MpvLogMessage(pointer)
                    val text = log.message()
                    if (text.isNotBlank()) {
                        _snapshot.value = _snapshot.value.copy(lastMessage = text)
                        // The snapshot holds only the latest line, and the UI shows
                        // even that one just while a load is in flight. mpv's
                        // commentary is the whole account of why a file would not
                        // open, so it also goes to the log file, where a bug report
                        // can carry it.
                        System.err.println("Cove mpv: [${log.source()}] $text")
                    }
                }
            }
        }
    }

    private fun pollState(library: MpvLibrary, target: Pointer) {
        if (closing.get()) return
        try {
            val previous = _snapshot.value
            val idle     = getFlag(library, target, "idle-active")      ?: true
            val paused   = getFlag(library, target, "pause")            ?: true
            // Held rather than zeroed while mpv cannot answer — see resolveTimeProperty.
            val position = resolveTimeProperty(
                polled = getDouble(library, target, "time-pos"),
                previous = previous.positionSeconds,
                idle = idle,
            )
            val duration = resolveTimeProperty(
                polled = getDouble(library, target, "duration"),
                previous = previous.durationSeconds,
                idle = idle,
            )
            val volume   = getDouble(library, target, "volume")         ?: _snapshot.value.volume
            val muted    = getFlag(library, target, "mute")             ?: _snapshot.value.muted
            val title    = if (idle) "" else getString(library, target, "media-title").orEmpty()
            val codec    = if (idle) "" else getString(library, target, "video-codec").orEmpty()
            val tracks   = if (idle) "" else getString(library, target, "track-list").orEmpty()
            val buffering = getDouble(library, target, "cache-buffering-state") ?: 0.0
            val ended    = getFlag(library, target, "eof-reached") ?: false
            val rate     = getDouble(library, target, "speed") ?: 1.0
            val forCache  = getFlag(library, target, "paused-for-cache") ?: false
            val hwdec    = if (idle) "" else getString(library, target, "hwdec-current").orEmpty()
            val chapters = if (idle) "" else getString(library, target, "chapter-list").orEmpty()
            if (!idle) {
                videoSizeChanged(
                    width = getDouble(library, target, "dwidth")?.finiteOrNull()?.toInt() ?: 0,
                    height = getDouble(library, target, "dheight")?.finiteOrNull()?.toInt() ?: 0,
                )
                logDecoder(codec, hwdec)
            }
            // Absolute timestamp, so it is only meaningful against a live position.
            val cacheEnd = getDouble(library, target, "demuxer-cache-time").finiteOrNull()
                ?: previous.cacheEndSeconds
            val cacheAhead = getDouble(library, target, "demuxer-cache-duration") ?: 0.0
            val subDelay = getDouble(library, target, "sub-delay") ?: 0.0
            val audioDelay = getDouble(library, target, "audio-delay") ?: 0.0
            val dropped  = getDouble(library, target, "frame-drop-count") ?: 0.0
            val decoderDropped = getDouble(library, target, "decoder-frame-drop-count") ?: 0.0
            val mistimed = getDouble(library, target, "mistimed-frame-count") ?: 0.0
            val delayed = getDouble(library, target, "vo-delayed-frame-count") ?: 0.0
            val fps      = getDouble(library, target, "estimated-vf-fps") ?: 0.0
            val bitrate  = getDouble(library, target, "video-bitrate") ?: 0.0

            _snapshot.value = _snapshot.value.copy(
                initialized     = true,
                hasMedia        = !idle,
                paused          = paused,
                positionSeconds = position,
                durationSeconds = duration,
                volume          = volume.coerceIn(0.0, MAX_VOLUME),
                muted           = muted,
                title           = title,
                videoCodec      = codec,
                // Real now that the path asks for copy-back decoding: the decode can
                // still be on the GPU even though the rendering is not.
                hwdecCurrent    = hwdec,
                renderBackend   = "Software",
                trackListJson   = tracks,
                cacheBufferingPercent = buffering.finiteOrZero().toInt().coerceIn(0, 100),
                cacheEndSeconds = cacheEnd,
                cacheDurationSeconds = cacheAhead.finiteOrZero(),
                endReached      = ended,
                speed           = rate,
                pausedForCache  = forCache,
                chapterListJson = chapters,
                subtitleDelaySeconds = subDelay.takeIf(Double::isFinite) ?: 0.0,
                audioDelaySeconds = audioDelay.takeIf(Double::isFinite) ?: 0.0,
                frameDropCount  = dropped.finiteOrZero().toInt(),
                decoderFrameDropCount = decoderDropped.finiteOrZero().toInt(),
                mistimedFrameCount = mistimed.finiteOrZero().toInt(),
                delayedFrameCount = delayed.finiteOrZero().toInt(),
                estimatedFps    = fps.finiteOrZero(),
                videoBitrate    = bitrate.finiteOrZero(),
                renderWidth     = renderWidth.get(),
                renderHeight    = renderHeight.get(),
                renderTimeMillis = lastRenderNanos.get().coerceAtLeast(0L) / 1_000_000.0,
                error           = null,
            )
        } catch (error: Throwable) {
            _snapshot.value = _snapshot.value.copy(error = "State update failed: ${error.message}")
        }
    }

    @Volatile private var loggedDecoder: Pair<String, String>? = null

    /**
     * One line per change of decoder, for the log a bug report carries.
     *
     * The stats overlay shows the same thing, but only to someone looking at it while the film
     * plays; "it stutters" arrives afterwards, with only the log, and whether the picture was
     * decoded on the GPU is the first question to answer. mpv reports hwdec-current as "no" once
     * it has settled on decoding in software, and nothing at all before it has decided.
     */
    private fun logDecoder(codec: String, hwdec: String) {
        if (codec.isBlank() || hwdec.isBlank()) return
        val decoder = codec to hwdec
        if (decoder == loggedDecoder) return
        loggedDecoder = decoder
        val how = if (hwdec == "no") "in software" else "on the GPU ($hwdec)"
        System.err.println("Cove mpv: decoding $codec $how, drawn at ${renderWidth.get()}x${renderHeight.get()}")
    }

    /**
     * mpv sees only the raw address of the argument array, so the array has to be
     * held reachable across the call.
     *
     * JNA frees a Memory from its Cleaner once Java considers it unreachable, and a
     * local passed to a native function is unreachable the moment the call starts —
     * the argument is on the stack, not in any live variable. Without the fence a GC
     * landing mid-call can free the array while mpv is still reading it, and mpv then
     * parses whatever replaced it: a seek to a garbage timestamp, which clamps to the
     * end of the file. Rapid seeking is what makes this fire, because it is what
     * allocates the arrays fast enough to provoke the collection. The render path in
     * this file fences its own allocations for the same reason.
     */
    override fun command(vararg args: String) {
        submitCommand(args.firstOrNull() ?: "command") { library, target ->
            val arguments = StringArray(args)
            try {
                library.mpv_command(target, arguments)
            } finally {
                Reference.reachabilityFence(arguments)
            }
        }
    }

    /** Runs [call] on the command thread and records a failing result on the snapshot. */
    private fun submitCommand(operation: String, call: (MpvLibrary, Pointer) -> Int) {
        if (closing.get()) return
        commandExecutor.execute {
            val target = handle.get() ?: return@execute
            val result = call(Mpv.library(), target)
            if (result < 0) recordError(result, operation)
        }
    }

    private fun recordError(code: Int, operation: String) {
        _snapshot.value = _snapshot.value.copy(
            error = "$operation: ${Mpv.library().mpv_error_string(code)}",
        )
    }

    private fun createSoftwareRenderContext(library: MpvLibrary, target: Pointer): Pointer {
        val api    = Memory(3).apply { setString(0, "sw") }
        val params = renderParamArray(2)
        params[0].type = Mpv.RENDER_PARAM_API_TYPE; params[0].data = api
        params.forEach(MpvRenderParam::write)

        val result = PointerByReference()
        try {
            checkMpv(
                library,
                library.mpv_render_context_create(result, target, params[0].pointer),
                "create software render context",
            )
        } finally {
            // params contains only the native addresses. Keep their Java owners
            // reachable until mpv has finished reading the array.
            Reference.reachabilityFence(api)
            Reference.reachabilityFence(params)
        }
        return checkNotNull(result.value) { "mpv returned null render context" }
    }

    private fun setOption(library: MpvLibrary, target: Pointer, name: String, value: String) {
        checkMpv(library, library.mpv_set_option_string(target, name, value), "set option $name")
    }

    /**
     * [COPY_BACK_DECODERS], or auto-copy on a libmpv too old to take a list of decoders
     * (before 0.36). That is what Cove asked every libmpv for until the list, and still far
     * better than decoding everything on the CPU.
     */
    private fun configureHardwareDecoding(library: MpvLibrary, target: Pointer) {
        // Probe the accepted value even if decoding starts off: enabling it later uses this
        // same fallback, without reconstructing the player.
        if (library.mpv_set_option_string(target, "hwdec", COPY_BACK_DECODERS) < 0) {
            System.err.println("Cove mpv: this libmpv takes no list of decoders; using auto-copy")
            copyBackDecoders = "auto-copy"
            setOption(library, target, "hwdec", copyBackDecoders)
        }
        if (!hardwareDecoding) setOption(library, target, "hwdec", "no")
    }

    /**
     * [setOption] for an option worth having but not worth refusing to start over.
     * Returns whether this libmpv took it.
     */
    private fun setOptionalOption(library: MpvLibrary, target: Pointer, name: String, value: String): Boolean {
        val result = library.mpv_set_option_string(target, name, value)
        if (result < 0) {
            System.err.println(
                "Cove mpv: this libmpv rejected $name (${library.mpv_error_string(result)}); " +
                    "continuing without it",
            )
        }
        return result >= 0
    }

    private fun getFlag(library: MpvLibrary, target: Pointer, name: String): Boolean? {
        val v = Memory(Int.SIZE_BYTES.toLong())
        return if (library.mpv_get_property(target, name, Mpv.FORMAT_FLAG, v) >= 0) v.getInt(0) != 0
        else null
    }

    private fun getDouble(library: MpvLibrary, target: Pointer, name: String): Double? {
        val v = Memory(Double.SIZE_BYTES.toLong())
        return if (library.mpv_get_property(target, name, Mpv.FORMAT_DOUBLE, v) >= 0) v.getDouble(0)
        else null
    }

    private fun getString(library: MpvLibrary, target: Pointer, name: String): String? {
        val ptr = library.mpv_get_property_string(target, name) ?: return null
        return try { ptr.getString(0) } finally { library.mpv_free(ptr) }
    }
}

/**
 * The copy-back hardware decoders Cove lets mpv use on this platform, in mpv's own order of
 * preference — but without Vulkan.
 *
 * mpv 0.41's auto-copy tries vulkan-copy before anything else, and on an RTX 5070 Ti with
 * NVIDIA's 615 driver that decodes H.264 wrong: 182 of 200 frames of a test clip came out
 * smeared and blocky, against none from nvdec-copy, vaapi-copy or the CPU. HEVC decoded
 * cleanly; H.264 is what a large share of WEB-DL releases still are. NVIDIA has the same report
 * against its 610 driver on an RTX 5080 (developer forum thread 378828, "H.264 Vulkan Video
 * decoder produces severe artifacts"); revisit once a driver fixes it. When none of these takes
 * a file, mpv falls back to software decoding.
 *
 * Per platform because mpv logs every name in the list it was not built with, on every file.
 */
internal fun copyBackDecoders(osName: String): String = when {
    osName.startsWith("Windows", ignoreCase = true) -> "d3d11va-copy,dxva2-copy,nvdec-copy"
    osName.startsWith("Mac", ignoreCase = true) -> "videotoolbox-copy"
    // Keep the non-Vulkan fallbacks for older NVIDIA and ARM/DRM devices as well.
    else -> "nvdec-copy,vaapi-copy,vdpau-copy,drm-copy"
}

internal val COPY_BACK_DECODERS: String = copyBackDecoders(System.getProperty("os.name").orEmpty())

/**
 * The size mpv should render a [surfaceWidth]×[surfaceHeight] surface at.
 *
 * The surface's own size, unless the picture is smaller than the surface and [mayRenderSmaller]:
 * then the same shape scaled down so the picture lands at about its own resolution, and the GPU
 * stretches the result to fill the surface. mpv's software scaler is the costliest step of
 * playback on this path — a 1080p film on a 2560×1600 panel took twice the CPU of the same film
 * at 1920×1200, and on a 4K screen four times — and it was spent enlarging pixels that a GPU
 * enlarges for nothing.
 *
 * Never below 1080 lines, or the surface's own height if that is less: subtitles are drawn into
 * these same pixels, and text is what shows a stretch first.
 */
internal fun softwareRenderSize(
    surfaceWidth: Int,
    surfaceHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    mayRenderSmaller: Boolean,
): Pair<Int, Int> {
    if (!mayRenderSmaller || videoWidth <= 0 || videoHeight <= 0) return surfaceWidth to surfaceHeight
    val fit = minOf(surfaceWidth.toDouble() / videoWidth, surfaceHeight.toDouble() / videoHeight)
    if (fit <= 1.0) return surfaceWidth to surfaceHeight
    val scale = maxOf(1.0 / fit, MIN_RENDER_LINES.toDouble() / surfaceHeight).coerceAtMost(1.0)
    return (surfaceWidth * scale).roundToInt().coerceAtLeast(1) to
        (surfaceHeight * scale).roundToInt().coerceAtLeast(1)
}

private const val MIN_RENDER_LINES = 1080

/** Owns every allocation referenced indirectly by mpv's software render params. */
private class SoftwareRenderParameters : AutoCloseable {
    private val dimensions = Memory(2L * Int.SIZE_BYTES)
    private val format = Memory(5).apply { setString(0, "rgb0") }
    private val stride = Memory(Native.SIZE_T_SIZE.toLong())
    private val params = renderParamArray(5).apply {
        this[0].type = Mpv.RENDER_PARAM_SW_SIZE
        this[0].data = dimensions
        this[1].type = Mpv.RENDER_PARAM_SW_FORMAT
        this[1].data = format
        this[2].type = Mpv.RENDER_PARAM_SW_STRIDE
        this[2].data = stride
        this[3].type = Mpv.RENDER_PARAM_SW_POINTER
    }

    val pointer: Pointer
        get() = params[0].pointer

    fun configure(width: Int, height: Int, target: Pointer, rowBytes: Int) {
        dimensions.setInt(0, width)
        dimensions.setInt(Int.SIZE_BYTES.toLong(), height)
        if (Native.SIZE_T_SIZE == Long.SIZE_BYTES) {
            stride.setLong(0, rowBytes.toLong())
        } else {
            stride.setInt(0, rowBytes)
        }
        params[3].data = target
        params.forEach(MpvRenderParam::write)
    }

    fun keepAlive() {
        Reference.reachabilityFence(dimensions)
        Reference.reachabilityFence(format)
        Reference.reachabilityFence(stride)
        Reference.reachabilityFence(params)
    }

    override fun close() {
        dimensions.close()
        format.close()
        stride.close()
    }
}

private fun namedDaemon(name: String) = java.util.concurrent.ThreadFactory { task ->
    Thread(task, name).apply { isDaemon = true }
}
