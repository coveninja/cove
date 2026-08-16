package com.coveninja.cove.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process libmpv player backed by an OpenGL render context hosted in [panel].
 *
 * The four JOGL/mpv threading traps, each documented at the call site:
 *  1. setSkipGLOrientationVerticalFlip(true) — in MpvOpenGlPanel
 *  2. Rebind JOGL's FBO after mpv_render_context_render — in MpvOpenGlPanel
 *  3. Render update callback only schedules EDT work — updateCallback below
 *  4. LC_NUMERIC=C before mpv_create — in Mpv.create()
 *
 * Normal client API calls (commands, property gets) go through commandExecutor.
 * Render calls happen on JOGL's GL thread while the context is current.
 * The event drain runs on eventExecutor. Never mix these threads.
 */
class MpvOpenGlPlayer(
    private val panel: MpvOpenGlPanel,
) : DesktopPlayer, MpvOpenGlPanel.Renderer {

    private val _snapshot     = MutableStateFlow(PlayerSnapshot(renderBackend = "OpenGL"))
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val handle        = AtomicReference<Pointer?>()
    private val renderContext = AtomicReference<Pointer?>()
    private val pendingSource = AtomicReference<PendingMedia?>()
    private val closing       = AtomicBoolean(false)

    private val commandExecutor = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-commands"))
    private val eventExecutor   = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-events"))
    private val stateExecutor   = Executors.newSingleThreadScheduledExecutor(namedDaemon("cove-mpv-state"))

    private val procAddressResolver = AtomicReference<((String) -> Long)?>()

    // Trap 3: the render update callback fires on mpv's internal thread.
    // It must only schedule EDT work and never call mpv or GL API directly.
    private val updateCallback = MpvRenderUpdateCallback {
        if (!closing.get()) panel.requestRender()
    }

    private val getProcAddressCallback = MpvOpenGlGetProcAddress { _, name ->
        procAddressResolver.get()?.invoke(name)
            ?.takeIf { it != 0L }
            ?.let(::Pointer)
    }

    @Volatile private var lastDecoderDiag = ""

    @Synchronized
    override fun start() {
        if (handle.get() != null || closing.get()) return
        try {
            val library = Mpv.library()
            // Trap 4: LC_NUMERIC is reset to "C" inside Mpv.create().
            val created = checkNotNull(Mpv.create()) {
                "mpv_create returned null — is libmpv installed?"
            }
            handle.set(created)

            setOption(library, created, "vo",        "libmpv")
            setOption(library, created, "terminal",  "no")
            setOption(library, created, "msg-level", "all=warn")
            setOption(library, created, "keep-open", "yes")
            // As in MpvSoftwarePlayer: libmpv defaults this off, and without it
            // --play cannot be handed a page URL.
            setOption(library, created, "ytdl", "yes")
            // OpenGL path: enable hardware decode. mpv will fall back to software
            // automatically if the GPU driver does not support the codec.
            setOption(library, created, "hwdec",     "auto-safe")

            checkMpv(library, library.mpv_initialize(created), "initialize")
            // The only running commentary available while a file is opening.
            library.mpv_request_log_messages(created, "info")
            _snapshot.value = _snapshot.value.copy(initialized = true, error = null)

            eventExecutor.execute { drainEvents(library, created) }
            stateExecutor.scheduleAtFixedRate(
                { pollState(library, created) },
                0, 200, TimeUnit.MILLISECONDS,
            )

            panel.attach(this)
            panel.requestRender()
        } catch (error: Throwable) {
            _snapshot.value = _snapshot.value.copy(
                initialized = false,
                error = error.cause?.message ?: error.message ?: error::class.java.simpleName,
            )
            close()
        }
    }

    override fun load(source: String, startPositionSeconds: Double) {
        if (renderContext.get() == null) {
            // GL context not yet ready; queue until initialize() is called.
            pendingSource.set(PendingMedia(source, startPositionSeconds))
        } else {
            loadNow(source, startPositionSeconds)
        }
    }

    override fun togglePause() = setPaused(!_snapshot.value.paused)

    override fun setPaused(paused: Boolean) {
        submitCommand("set pause") { library, target ->
            val v = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, if (paused) 1 else 0) }
            // Fenced for the same reason as command()'s argument array. The property
            // getters get away without one only because they read the buffer back
            // afterwards, which keeps it reachable on its own.
            try {
                library.mpv_set_property(target, "pause", Mpv.FORMAT_FLAG, v)
            } finally {
                Reference.reachabilityFence(v)
            }
        }
    }

    override fun seek(seconds: Double) {
        command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute", "exact")
    }

    override fun setVolume(volume: Double) {
        submitCommand("set volume") { library, target ->
            val v = Memory(Double.SIZE_BYTES.toLong()).apply {
                setDouble(0, volume.coerceIn(0.0, 100.0))
            }
            try {
                library.mpv_set_property(target, "volume", Mpv.FORMAT_DOUBLE, v)
            } finally {
                Reference.reachabilityFence(v)
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

    override fun setMuted(muted: Boolean) {
        submitCommand("set mute") { library, target ->
            val v = Memory(Int.SIZE_BYTES.toLong()).apply {
                setInt(0, if (muted) 1 else 0)
            }
            try {
                library.mpv_set_property(target, "mute", Mpv.FORMAT_FLAG, v)
            } finally {
                Reference.reachabilityFence(v)
            }
        }
    }

    override fun selectAudioTrack(id: Int) = command("set", "aid", id.toString())

    override fun selectSubtitleTrack(id: Int?) =
        command("set", "sid", id?.toString() ?: "no")

    override fun stop() = command("stop")

    // ---- MpvOpenGlPanel.Renderer ----

    override fun initialize(getProcAddress: (String) -> Long, nativeX11Display: Pointer?) {
        if (closing.get() || renderContext.get() != null) return
        try {
            val library = Mpv.library()
            val target  = checkNotNull(handle.get()) { "mpv closed before GL context was ready" }
            procAddressResolver.set(getProcAddress)

            val api    = Memory(7).apply { setString(0, "opengl") }
            val init   = MpvOpenGlInitParams().apply {
                this.getProcAddress = getProcAddressCallback
                write()
            }
            val adv    = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, 1) }
            val paramCount = if (nativeX11Display == null) 4 else 5
            val params = renderParamArray(paramCount)
            params[0].type = Mpv.RENDER_PARAM_API_TYPE;           params[0].data = api
            params[1].type = Mpv.RENDER_PARAM_OPENGL_INIT_PARAMS; params[1].data = init.pointer
            params[2].type = Mpv.RENDER_PARAM_ADVANCED_CONTROL;   params[2].data = adv
            if (nativeX11Display != null) {
                params[3].type = Mpv.RENDER_PARAM_X11_DISPLAY; params[3].data = nativeX11Display
            }
            params.forEach(MpvRenderParam::write)

            val result = PointerByReference()
            try {
                checkMpv(
                    library,
                    library.mpv_render_context_create(result, target, params[0].pointer),
                    "create OpenGL render context",
                )
            } finally {
                Reference.reachabilityFence(api)
                Reference.reachabilityFence(init)
                Reference.reachabilityFence(adv)
                Reference.reachabilityFence(params)
            }
            val ctx = checkNotNull(result.value) { "mpv returned null render context" }
            renderContext.set(ctx)
            library.mpv_render_context_set_update_callback(ctx, updateCallback, null)

            _snapshot.value = _snapshot.value.copy(renderBackend = "OpenGL", error = null)

            pendingSource.getAndSet(null)?.let { loadNow(it.source, it.startPositionSeconds) }
            panel.requestRender()
        } catch (error: Throwable) {
            reportError(error)
        }
    }

    override fun render(framebuffer: Int, width: Int, height: Int) {
        val ctx     = renderContext.get() ?: return
        val library = Mpv.library()

        library.mpv_render_context_update(ctx)

        val fbo = MpvOpenGlFbo().apply {
            fbo             = framebuffer
            this.width      = width.coerceAtLeast(1)
            this.height     = height.coerceAtLeast(1)
            internalFormat  = 0
            write()
        }
        val params = renderParamArray(2)
        params[0].type = Mpv.RENDER_PARAM_OPENGL_FBO; params[0].data = fbo.pointer
        params.forEach(MpvRenderParam::write)

        try {
            checkMpv(
                library,
                library.mpv_render_context_render(ctx, params[0].pointer),
                "render OpenGL frame",
            )
        } finally {
            // The native call receives only raw pointers to these objects.
            Reference.reachabilityFence(fbo)
            Reference.reachabilityFence(params)
        }
        // Trap 2: mpv_render_context_render leaves framebuffer 0 bound.
        // Rebinding is done in MpvOpenGlPanel after this call returns so
        // GLJPanel's readback pass finds the correct FBO.
    }

    override fun closeRenderer() {
        val ctx = renderContext.getAndSet(null) ?: return
        runCatching { Mpv.library().mpv_render_context_set_update_callback(ctx, null, null) }
        Mpv.library().mpv_render_context_free(ctx)
        procAddressResolver.set(null)
    }

    override fun reportError(error: Throwable) {
        _snapshot.value = _snapshot.value.copy(
            error = "OpenGL renderer: ${error.cause?.message ?: error.message}",
        )
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return

        stateExecutor.shutdownNow()
        panel.closeRenderer()   // releases GL context with mpv's FBO current

        val target = handle.getAndSet(null)
        target?.let { runCatching { Mpv.library().mpv_wakeup(it) } }

        eventExecutor.shutdown()
        runCatching { eventExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        commandExecutor.shutdownNow()
        runCatching { commandExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        // shutdownNow interrupts, but a poll already inside mpv is making twenty
        // uninterruptible native calls against a handle it captured at schedule
        // time, and it only tests `closing` on entry. Destroying underneath it
        // frees the handle mid-read.
        runCatching { stateExecutor.awaitTermination(2, TimeUnit.SECONDS) }

        if (target != null && renderContext.get() == null) {
            runCatching { Mpv.library().mpv_terminate_destroy(target) }
        }
        _snapshot.value = _snapshot.value.copy(initialized = false, hasMedia = false, paused = true)
    }

    private fun pollState(library: MpvLibrary, target: Pointer) {
        if (closing.get()) return
        try {
            val previous = _snapshot.value
            val idle     = getFlag(library, target, "idle-active")  ?: true
            val paused   = getFlag(library, target, "pause")        ?: true
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
            val volume   = getDouble(library, target, "volume")     ?: _snapshot.value.volume
            val muted    = getFlag(library, target, "mute")          ?: _snapshot.value.muted
            val title    = if (idle) "" else getString(library, target, "media-title").orEmpty()
            val codec    = if (idle) "" else getString(library, target, "video-codec").orEmpty()
            val hwdec    = if (idle) "" else getString(library, target, "hwdec-current").orEmpty()
            val tracks   = if (idle) "" else getString(library, target, "track-list").orEmpty()
            val buffering = getDouble(library, target, "cache-buffering-state") ?: 0.0
            val ended    = getFlag(library, target, "eof-reached") ?: false
            val rate     = getDouble(library, target, "speed") ?: 1.0
            val forCache  = getFlag(library, target, "paused-for-cache") ?: false
            val chapters = if (idle) "" else getString(library, target, "chapter-list").orEmpty()
            val cacheEnd = getDouble(library, target, "demuxer-cache-time").finiteOrNull()
                ?: previous.cacheEndSeconds
            val cacheAhead = getDouble(library, target, "demuxer-cache-duration") ?: 0.0
            val subDelay = getDouble(library, target, "sub-delay") ?: 0.0
            val audioDelay = getDouble(library, target, "audio-delay") ?: 0.0
            val dropped  = getDouble(library, target, "frame-drop-count") ?: 0.0
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
                hwdecCurrent    = hwdec,
                renderBackend   = "OpenGL",
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
                estimatedFps    = fps.finiteOrZero(),
                videoBitrate    = bitrate.finiteOrZero(),
                error           = null,
            )

            val diag = "$codec/$hwdec"
            if (!idle && diag != lastDecoderDiag) {
                lastDecoderDiag = diag
                println(
                    "Cove mpv: video-codec=${codec.ifBlank { "unknown" }} " +
                        "hwdec=${hwdec.ifBlank { "no" }}",
                )
            }
        } catch (error: Throwable) {
            _snapshot.value = _snapshot.value.copy(error = "State update failed: ${error.message}")
        }
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
                    }
                }
            }
        }
    }

    /**
     * The argument array is held reachable across the call: mpv receives only its
     * raw address, and JNA frees a Memory whose Java owner has gone unreachable —
     * which a local passed to a native function is, for the whole duration of that
     * call. See the identical fence in MpvSoftwarePlayer.command for what a freed
     * argument array does to a seek.
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

    private fun loadNow(source: String, startPositionSeconds: Double) {
        // start applies to the next file loaded, so it is set before loadfile
        // rather than passed to it — see mpvLoadFileArgs for why.
        command("set", "start", mpvStartOption(startPositionSeconds))
        command(*mpvLoadFileArgs(source))
    }

    private fun submitCommand(op: String, call: (MpvLibrary, Pointer) -> Int) {
        if (closing.get()) return
        commandExecutor.execute {
            val target = handle.get() ?: return@execute
            val result = call(Mpv.library(), target)
            if (result < 0) {
                _snapshot.value = _snapshot.value.copy(
                    error = "$op: ${Mpv.library().mpv_error_string(result)}",
                )
            }
        }
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

private data class PendingMedia(val source: String, val startPositionSeconds: Double)

private fun namedDaemon(name: String) = java.util.concurrent.ThreadFactory { task ->
    Thread(task, name).apply { isDaemon = true }
}
