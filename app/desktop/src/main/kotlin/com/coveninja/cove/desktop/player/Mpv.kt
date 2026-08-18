package com.coveninja.cove.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path

internal object Mpv {
    // mpv_format enum values from client.h
    const val FORMAT_FLAG   = 3
    const val FORMAT_DOUBLE = 5

    // mpv_event_id enum values from client.h
    const val EVENT_NONE        = 0
    const val EVENT_SHUTDOWN    = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_START_FILE  = 6
    const val EVENT_END_FILE    = 7
    const val EVENT_FILE_LOADED = 8

    // mpv_render_param_type enum values from render.h
    const val RENDER_PARAM_API_TYPE            = 1
    const val RENDER_PARAM_OPENGL_INIT_PARAMS  = 2
    const val RENDER_PARAM_OPENGL_FBO          = 3
    const val RENDER_PARAM_X11_DISPLAY         = 8
    const val RENDER_PARAM_ADVANCED_CONTROL    = 10
    const val RENDER_PARAM_SW_SIZE             = 17
    const val RENDER_PARAM_SW_FORMAT           = 18
    const val RENDER_PARAM_SW_STRIDE           = 19
    const val RENDER_PARAM_SW_POINTER          = 20

    // mpv_render_update_flag from render.h
    const val RENDER_UPDATE_FRAME = 1L

    fun library(): MpvLibrary = LibraryHolder.instance

    /**
     * LC_NUMERIC must be "C" before mpv_create — AWT sets the process-wide C
     * locale from the user's system locale, and mpv then misparses every float
     * option (e.g. volume=100.0 silently breaks on Turkish or German locales).
     */
    @Synchronized
    fun create(): Pointer? {
        NativeNumericLocale.forceC()
        return library().mpv_create()
    }

    private object LibraryHolder {
        val instance: MpvLibrary = load()

        private fun load(): MpvLibrary {
            mpvLibrarySearchPaths(
                osName = System.getProperty("os.name"),
                environment = System.getenv(),
                javaHome = System.getProperty("java.home"),
            ).forEach { path -> NativeLibrary.addSearchPath("mpv", path) }
            // Windows ships the DLL as mpv-2; Linux/macOS as mpv.
            val candidates = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                listOf("mpv-2", "mpv")
            } else {
                listOf("mpv")
            }
            var lastFailure: Throwable? = null
            for (candidate in candidates) {
                try {
                    return Native.load(candidate, MpvLibrary::class.java)
                } catch (error: Throwable) {
                    lastFailure = error
                }
            }
            throw IllegalStateException(
                "libmpv was not found; install the mpv development/runtime package",
                lastFailure,
            )
        }
    }
}

/**
 * Finder-launched macOS apps do not inherit Homebrew's shell path. Add the two
 * standard Homebrew prefixes explicitly while retaining an override for other
 * package layouts. JNA still performs its normal system search afterwards.
 */
internal fun mpvLibrarySearchPaths(
    osName: String,
    environment: Map<String, String>,
    javaHome: String = System.getProperty("java.home"),
): List<String> = buildList {
    environment["COVE_MPV_LIBRARY_DIR"]?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
    if (osName.startsWith("Mac", ignoreCase = true)) {
        // jpackage puts the runtime at Cove.app/Contents/runtime/Contents/Home.
        // Release builds place a self-contained libmpv closure beside it.
        Path.of(javaHome).parent?.parent?.parent
            ?.resolve("Frameworks")
            ?.toString()
            ?.let(::add)
        add("/opt/homebrew/opt/mpv/lib")
        add("/usr/local/opt/mpv/lib")
    }
}.distinct()

private object NativeNumericLocale {
    private val lib: NativeLocaleLibrary by lazy {
        Native.load(Platform.C_LIBRARY_NAME, NativeLocaleLibrary::class.java)
    }

    fun forceC() {
        configureMpvNumericLocale(
            osName    = System.getProperty("os.name"),
            setLocale = lib::setlocale,
        )
    }
}

/** Exposed for unit-testing locale category selection without a real libmpv. */
internal fun configureMpvNumericLocale(
    osName: String,
    setLocale: (category: Int, locale: String) -> String?,
) {
    val result = setLocale(lcNumericCategory(osName), "C")
    check(result != null) { "Could not set LC_NUMERIC=C before creating libmpv" }
}

/**
 * LC_NUMERIC category constant is OS-specific:
 *   Linux:         1  (from /usr/include/locale.h)
 *   macOS/Windows: 4  (POSIX-defined on those platforms)
 */
internal fun lcNumericCategory(osName: String): Int =
    when {
        osName.startsWith("Mac",     ignoreCase = true) -> 4
        osName.startsWith("Windows", ignoreCase = true) -> 4
        else                                            -> 1
    }

private interface NativeLocaleLibrary : Library {
    fun setlocale(category: Int, locale: String): String?
}

internal interface MpvLibrary : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_command(handle: Pointer, args: Pointer): Int
    fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer

    /** Opts the handle into MPV_EVENT_LOG_MESSAGE at [minLevel] and above. */
    fun mpv_request_log_messages(handle: Pointer, minLevel: String): Int
    fun mpv_wakeup(handle: Pointer)
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_free(data: Pointer?)
    fun mpv_error_string(error: Int): String

    fun mpv_render_context_create(
        result: PointerByReference,
        handle: Pointer,
        params: Pointer,
    ): Int

    fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: MpvRenderUpdateCallback?,
        callbackCtx: Pointer?,
    )

    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer?)
}

/** Called by mpv's render thread — must only schedule work, never touch GL directly. */
internal fun interface MpvRenderUpdateCallback : Callback {
    fun invoke(ctx: Pointer?)
}

/** Called by mpv to resolve GL function pointers; invoked with GL context current. */
internal fun interface MpvOpenGlGetProcAddress : Callback {
    fun invoke(ctx: Pointer?, name: String): Pointer?
}

/**
 * Mirrors mpv_render_param { int type; void *data; } from render.h.
 * JNA Structure field order must match the C struct declaration exactly.
 */
@Structure.FieldOrder("type", "data")
internal open class MpvRenderParam : Structure() {
    @JvmField var type: Int      = 0
    @JvmField var data: Pointer? = null
}

/**
 * Mirrors mpv_opengl_init_params from render_gl.h.
 * get_proc_address_ctx is always null here (we pass context via closure).
 */
@Structure.FieldOrder("getProcAddress", "getProcAddressCtx")
internal open class MpvOpenGlInitParams : Structure() {
    @JvmField var getProcAddress:    MpvOpenGlGetProcAddress? = null
    @JvmField var getProcAddressCtx: Pointer?                 = null
}

/**
 * Mirrors mpv_opengl_fbo from render_gl.h.
 * internal_format = 0 lets mpv choose (GL_RGBA8).
 */
@Structure.FieldOrder("fbo", "width", "height", "internalFormat")
internal open class MpvOpenGlFbo : Structure() {
    @JvmField var fbo:            Int = 0
    @JvmField var width:          Int = 0
    @JvmField var height:         Int = 0
    @JvmField var internalFormat: Int = 0
}

/**
 * Mirrors mpv_event from client.h.
 * We only inspect eventId and error; the data pointer is unused here.
 */
@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal open class MpvEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var eventId:       Int      = Mpv.EVENT_NONE
    @JvmField var error:         Int      = 0
    @JvmField var replyUserdata: Long     = 0
    @JvmField var data:          Pointer? = null

    init { read() }
}

/**
 * Mirrors mpv_event_log_message from client.h, which arrives as the data pointer
 * of an MPV_EVENT_LOG_MESSAGE. This is the only running commentary mpv offers
 * while it is opening a file — no property reports progress before the demuxer
 * is up.
 */
@Structure.FieldOrder("prefix", "level", "text", "logLevel")
internal open class MpvLogMessage(pointer: Pointer) : Structure(pointer) {
    @JvmField var prefix:   Pointer? = null
    @JvmField var level:    Pointer? = null
    @JvmField var text:     Pointer? = null
    @JvmField var logLevel: Int      = 0

    init { read() }

    fun message(): String = text?.getString(0).orEmpty().trim()
    fun source(): String = prefix?.getString(0).orEmpty()
}

/** Allocates a contiguous JNA Structure array of [count] render params. */
@Suppress("UNCHECKED_CAST")
internal fun renderParamArray(count: Int): Array<MpvRenderParam> =
    MpvRenderParam().toArray(count) as Array<MpvRenderParam>

internal fun checkMpv(library: MpvLibrary, result: Int, operation: String) {
    check(result >= 0) { "$operation: ${library.mpv_error_string(result)}" }
}

/** Build the argument array for mpv's "loadfile" command. */
/**
 * Always the three-argument form.
 *
 * The resume point is **not** passed here. mpv 0.38 inserted an `<index>`
 * parameter into loadfile between `<flags>` and `<options>`, so the old
 * four-argument `loadfile <url> replace start=N` is now read as an index and
 * rejected with "argument index can't be parsed" — the file never loads. Adding
 * an index instead would fix new mpv and break old, so the start position goes
 * through the `start` option ([mpvStartOption]), which every version accepts and
 * which lets mpv begin demuxing at the offset rather than seeking after load.
 */
internal fun mpvLoadFileArgs(source: String): Array<String> =
    arrayOf("loadfile", source, "replace")

/**
 * Value for mpv's `start` option, applied to the next file loaded.
 *
 * Whole seconds: mpv parses fractions, but a resume point is not worth the
 * precision, and integers keep the value readable in logs.
 */
internal fun mpvStartOption(startPositionSeconds: Double): String =
    if (startPositionSeconds > 0.0) startPositionSeconds.toLong().toString() else "0"
