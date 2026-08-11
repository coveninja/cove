package com.coveninja.cove.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process libmpv player backed by mpv's software render API.
 *
 * Decoded frames land in a direct ByteBuffer as bgr0 pixels and are converted
 * to a Compose ImageBitmap for display. No GPU interop or Swing embedding.
 * Establish this path before introducing OpenGL so there is always a working
 * fallback if GL context creation fails.
 */
class MpvSoftwarePlayer(
    // Software *rendering*, not necessarily software decoding. auto-copy keeps the
    // decode on the GPU and copies finished frames back to system memory, which is
    // what this path needs and is far cheaper than decoding on the CPU. mpv falls
    // back to software decode by itself when no copy-back decoder is available.
    private val hardwareDecoding: Boolean = true,
    private val frameConsumer: (BufferedImage) -> Unit,
) : DesktopPlayer {
    private val _snapshot = MutableStateFlow(PlayerSnapshot(renderBackend = "Software"))
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val handle        = AtomicReference<Pointer?>()
    private val renderContext = AtomicReference<Pointer?>()
    private val closing       = AtomicBoolean(false)
    private val renderQueued  = AtomicBoolean(false)
    private val renderWidth   = AtomicInteger(1280)
    private val renderHeight  = AtomicInteger(720)

    private val renderExecutor = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-render"))
    private val eventExecutor  = Executors.newSingleThreadExecutor(namedDaemon("cove-mpv-events"))
    private val stateExecutor  = Executors.newSingleThreadScheduledExecutor(namedDaemon("cove-mpv-state"))

    // The render update callback fires on mpv's internal thread. It must only
    // schedule work and never touch GL or mpv API functions directly.
    private val updateCallback = MpvRenderUpdateCallback { requestRender() }

    @Volatile private var renderBuffer: ByteBuffer? = null
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
        val target = handle.get() ?: return
        val value = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, if (paused) 1 else 0) }
        val result = Mpv.library().mpv_set_property(target, "pause", Mpv.FORMAT_FLAG, value)
        if (result < 0) recordError(result, "set pause")
    }

    override fun seek(seconds: Double) {
        command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute", "exact")
    }

    override fun setVolume(volume: Double) {
        val target = handle.get() ?: return
        val value = Memory(Double.SIZE_BYTES.toLong()).apply {
            setDouble(0, volume.coerceIn(0.0, 100.0))
        }
        val result = Mpv.library().mpv_set_property(target, "volume", Mpv.FORMAT_DOUBLE, value)
        if (result < 0) recordError(result, "set volume")
    }

    override fun setMuted(muted: Boolean) {
        val target = handle.get() ?: return
        val value = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, if (muted) 1 else 0) }
        val result = Mpv.library().mpv_set_property(target, "mute", Mpv.FORMAT_FLAG, value)
        if (result < 0) recordError(result, "set mute")
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
                }.get(3, TimeUnit.SECONDS)
            }
        }
        renderExecutor.shutdown()
        runCatching { renderExecutor.awaitTermination(2, TimeUnit.SECONDS) }

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
        val bytes   = Math.multiplyExact(Math.multiplyExact(width, height), Int.SIZE_BYTES)

        val target = renderBuffer
            ?.takeIf { it.capacity() == bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .also { renderBuffer = it }

        // mpv receives raw pointers nested inside the render-param array. JNA
        // cannot see those pointees while the native call is in progress, so a
        // local Memory can become unreachable and its Cleaner can free it before
        // mpv returns. Keep one owner for the context lifetime and fence both it
        // and the direct pixel buffer across the native boundary.
        val parameters = renderParameters
            ?: SoftwareRenderParameters().also { renderParameters = it }
        parameters.configure(width, height, target)

        val library = Mpv.library()
        library.mpv_render_context_update(context)
        try {
            checkMpv(
                library,
                library.mpv_render_context_render(context, parameters.pointer),
                "sw render frame",
            )
        } finally {
            parameters.keepAlive()
            Reference.reachabilityFence(target)
        }
        frameConsumer(bgr0ToBufferedImage(target, width, height))
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

    private fun pollState(library: MpvLibrary, target: Pointer) {
        if (closing.get()) return
        try {
            val idle     = getFlag(library, target, "idle-active")      ?: true
            val paused   = getFlag(library, target, "pause")            ?: true
            val position = getDouble(library, target, "time-pos")       ?: 0.0
            val duration = getDouble(library, target, "duration")       ?: 0.0
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

            _snapshot.value = _snapshot.value.copy(
                initialized     = true,
                hasMedia        = !idle,
                paused          = paused,
                positionSeconds = position.finiteOrZero(),
                durationSeconds = duration.finiteOrZero(),
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
                endReached      = ended,
                speed           = rate,
                pausedForCache  = forCache,
                error           = null,
            )
        } catch (error: Throwable) {
            _snapshot.value = _snapshot.value.copy(error = "State update failed: ${error.message}")
        }
    }

    private fun command(vararg args: String) {
        val target = handle.get() ?: return
        val result = Mpv.library().mpv_command(target, StringArray(args))
        if (result < 0) recordError(result, args.firstOrNull() ?: "command")
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

    fun configure(width: Int, height: Int, target: ByteBuffer) {
        dimensions.setInt(0, width)
        dimensions.setInt(Int.SIZE_BYTES.toLong(), height)
        if (Native.SIZE_T_SIZE == Long.SIZE_BYTES) {
            stride.setLong(0, width.toLong() * Int.SIZE_BYTES)
        } else {
            stride.setInt(0, width * Int.SIZE_BYTES)
        }
        params[3].data = Native.getDirectBufferPointer(target)
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

/**
 * Convert a bgr0 direct ByteBuffer (little-endian) to a TYPE_INT_RGB image.
 *
 * bgr0 pixel layout: [B, G, R, 0] at byte offsets 0–3.
 * Read as a little-endian int32: value = B | G<<8 | R<<16 | 0<<24 = 0x00RRGGBB.
 * TYPE_INT_RGB stores 0x00RRGGBB, so the in-memory representation is identical
 * and a single bulk int-buffer copy is sufficient.
 */
internal fun bgr0ToBufferedImage(source: ByteBuffer, width: Int, height: Int): BufferedImage {
    require(width > 0 && height > 0) { "Frame dimensions must be positive" }
    val pixelCount = Math.multiplyExact(width, height)
    require(source.capacity() >= pixelCount * Int.SIZE_BYTES) {
        "Frame buffer is smaller than the declared ${width}x${height} dimensions"
    }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val dest  = (image.raster.dataBuffer as DataBufferInt).data
    source.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { clear() }.asIntBuffer()
        .get(dest, 0, pixelCount)
    return image
}

private fun namedDaemon(name: String) = java.util.concurrent.ThreadFactory { task ->
    Thread(task, name).apply { isDaemon = true }
}
