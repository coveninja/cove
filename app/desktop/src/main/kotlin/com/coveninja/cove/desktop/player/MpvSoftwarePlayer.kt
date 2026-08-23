package com.coveninja.cove.desktop.player

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

/**
 * In-process libmpv player backed by mpv's software render API.
 *
 * Decoded frames land directly in a persistent Skia bitmap as bgr0 pixels.
 * Compose draws that same allocation, so there is no frame-sized AWT image or
 * per-pixel conversion between mpv and the UI. No GPU interop or Swing embedding.
 */
class MpvSoftwarePlayer internal constructor(
    // Software *rendering*, not necessarily software decoding. auto-copy keeps the
    // decode on the GPU and copies finished frames back to system memory, which is
    // what this path needs and is far cheaper than decoding on the CPU. mpv falls
    // back to software decode by itself when no copy-back decoder is available.
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
            setOption(library, created, "terminal",  "no")
            setOption(library, created, "msg-level", "all=warn")
            setOption(library, created, "keep-open", "yes")
            // libmpv leaves the ytdl hook off where the mpv binary has it on. With
            // it on, a URL mpv cannot open directly is handed to yt-dlp — which is
            // what turns the YouTube page behind a trailer into a playable stream.
            // Costs nothing for ordinary streams: the hook runs on load failure,
            // not on load. See MpvVideoPlayerHost.playsWebVideos.
            setOption(library, created, "ytdl", "yes")
            // All three are set before initialize because ytdl_hook reads them when
            // the script loads. The search path names the managed copy first and then
            // the names mpv would have tried anyway; the format string is there
            // because mpv's own default picks streams YouTube answers with 403, and
            // the raw options are there because the *client* yt-dlp asks by default
            // hands back streams that 403 whatever format is chosen.
            ytdlSearchPath?.let { setOption(library, created, "script-opts", "ytdl_hook-ytdl_path=$it") }
            ytdlFormat?.let { setOption(library, created, "ytdl-format", it) }
            ytdlRawOptions?.let { setOption(library, created, "ytdl-raw-options", it) }
            setOption(
                library,
                created,
                "hwdec",
                if (hardwareDecoding) "auto-copy" else "no",
            )

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
                setDouble(0, volume.coerceIn(0.0, 100.0))
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
    // setters cannot express.
    override fun setOption(name: String, value: String) = command("set", name, value)

    // "auto" selects the track immediately if nothing else is selected, which is
    // what a viewer adding a subtitle expects; flags and title are positional.
    override fun addSubtitle(url: String, title: String, language: String) =
        command("sub-add", url, "auto", title, language)

    override fun setScaling(keepAspect: Boolean, panscan: Double, zoom: Double) {
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
        val w = width.coerceIn(1, 8192)
        val h = height.coerceIn(1, 8192)
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
                Mpv.EVENT_START_FILE ->
                    _snapshot.value = _snapshot.value.copy(fileLoaded = false)

                Mpv.EVENT_FILE_LOADED ->
                    _snapshot.value = _snapshot.value.copy(fileLoaded = true)

                Mpv.EVENT_END_FILE ->
                    _snapshot.value = _snapshot.value.copy(fileLoaded = false)

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
                volume          = volume.coerceIn(0.0, 100.0),
                muted           = muted,
                title           = title,
                videoCodec      = codec,
                // Real now that the path asks for auto-copy: the decode can still be
                // on the GPU even though the rendering is not.
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

/** Owns every allocation referenced indirectly by mpv's software render params. */
private class SoftwareRenderParameters : AutoCloseable {
    private val dimensions = Memory(2L * Int.SIZE_BYTES)
    private val format = Memory(5).apply { setString(0, "bgr0") }
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
