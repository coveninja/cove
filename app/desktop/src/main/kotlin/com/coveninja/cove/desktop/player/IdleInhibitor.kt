package com.coveninja.cove.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * Keeps the display awake while something is playing.
 *
 * mpv has `stop-screensaver` and it is on by default, but it is implemented *inside the video
 * output backends* — x11, wayland, win32 — and Cove's in-app player runs `vo=libmpv` (see
 * MpvSoftwarePlayer), which has no window and therefore no backend to do it. The result was
 * that a film nobody touched for twenty minutes was interrupted by the screensaver, or the
 * lock screen, on every desktop. Android never had the problem: MainActivity holds
 * FLAG_KEEP_SCREEN_ON for the same span.
 *
 * Every route here is best-effort. A desktop that will not be inhibited must cost the
 * inhibition and nothing else, so failures are swallowed — but logged once, naming the route,
 * because "my screen still blanked" and "this was never implemented" are otherwise the same
 * bug report.
 */
internal interface IdleInhibitor {
    /** Idempotent: repeated calls while already held do nothing. */
    fun acquire()

    /** Idempotent, and safe to call without a matching [acquire]. */
    fun release()

    companion object {
        fun forThisPlatform(): IdleInhibitor = when {
            Platform.isWindows() -> WindowsIdleInhibitor()
            Platform.isMac() -> ProcessIdleInhibitor(
                route = "caffeinate",
                // -d is the display specifically. Sleep alone would still let the screen dim.
                command = listOf("caffeinate", "-d"),
            )
            Platform.isLinux() -> ProcessIdleInhibitor(
                route = "systemd-inhibit",
                // A long-lived child, not a one-shot: an inhibitor lives exactly as long as
                // the connection that took it, so anything that exits immediately — dbus-send,
                // a bare gdbus call — releases its own lock on the way out and achieves
                // nothing. This covers logind's idle inhibition, which is what session
                // managers honour; a desktop that only listens to org.freedesktop.ScreenSaver
                // would need that route adding beside this one.
                command = listOf(
                    "systemd-inhibit",
                    "--what=idle",
                    "--who=Cove",
                    "--why=Playing video",
                    "--mode=block",
                    "sleep",
                    "infinity",
                ),
            )
            else -> NoIdleInhibitor
        }
    }
}

/** Where there is no known way to ask. Says so once rather than pretending to work. */
private object NoIdleInhibitor : IdleInhibitor {
    private var warned = false

    override fun acquire() {
        if (!warned) {
            warned = true
            println("Cove: no screensaver inhibitor for this platform; the display may sleep")
        }
    }

    override fun release() = Unit
}

/**
 * Windows: one call, no handle to keep.
 *
 * ES_CONTINUOUS makes the state stick until it is cleared rather than resetting the idle timer
 * once, so release is the same call without the display flag. Declared inline instead of
 * pulling in jna-platform for a single function, matching how Mpv binds libmpv.
 */
private class WindowsIdleInhibitor : IdleInhibitor {
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val kernel32: Kernel32? = runCatching {
        Native.load("kernel32", Kernel32::class.java)
    }.onFailure { failed("load kernel32", it) }.getOrNull()

    private var held = false

    override fun acquire() {
        if (held) return
        val library = kernel32 ?: return
        runCatching {
            library.SetThreadExecutionState(ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED)
            held = true
        }.onFailure { failed("SetThreadExecutionState", it) }
    }

    override fun release() {
        if (!held) return
        held = false
        runCatching { kernel32?.SetThreadExecutionState(ES_CONTINUOUS) }
            .onFailure { failed("SetThreadExecutionState release", it) }
    }

    private companion object {
        const val ES_SYSTEM_REQUIRED = 0x00000001
        const val ES_DISPLAY_REQUIRED = 0x00000002
        const val ES_CONTINUOUS = -0x80000000
    }
}

/**
 * macOS and Linux: hold the inhibition for as long as a child process lives.
 *
 * Destroyed rather than left to the JVM's exit, and registered as a shutdown hook besides: a
 * crash between acquire and release would otherwise leave a `sleep infinity` behind holding
 * someone's display awake with nothing left to release it.
 */
private class ProcessIdleInhibitor(
    private val route: String,
    private val command: List<String>,
) : IdleInhibitor {
    private var process: Process? = null
    private var failedOnce = false

    private val shutdownHook = Thread { process?.destroy() }

    override fun acquire() {
        if (process?.isAlive == true) return
        runCatching {
            val started = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            process = started
            runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
        }.onFailure {
            // Usually the binary is simply absent — a container, a non-systemd Linux — which
            // is a fact about the machine rather than a fault, so it is said once and dropped.
            if (!failedOnce) {
                failedOnce = true
                failed(route, it)
            }
        }
    }

    override fun release() {
        process?.destroy()
        process = null
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}

private fun failed(route: String, error: Throwable) {
    println(
        "Cove: screensaver inhibition via $route unavailable " +
            "(${error.message ?: error::class.java.simpleName}); the display may sleep",
    )
}
