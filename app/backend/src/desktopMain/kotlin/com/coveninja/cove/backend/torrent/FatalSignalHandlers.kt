package com.coveninja.cove.backend.torrent

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.lang.ref.Reference
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the JVM's fatal-signal handlers installed while libtorrent is running.
 *
 * HotSpot has to own SIGSEGV. JIT-compiled code omits explicit null tests and lets
 * the CPU fault instead, and the handler turns that fault into a
 * NullPointerException. libtorrent installs handlers of its own, and from that
 * moment the next null dereference *anywhere in the process* is fatal — on whatever
 * thread reaches one first, with no hs_err log, because the JVM never sees the
 * signal. That is what took the app down seconds after any torrent started, always
 * pointing somewhere unrelated to torrents.
 *
 * Measured, rather than assumed: the handlers survive `SessionManager` construction,
 * `start()`, and an idle session untouched, and are replaced only once a torrent has
 * been *added* — asynchronously, on libtorrent's own threads, after the adding call
 * has already returned. Saving and restoring around our own calls therefore restores
 * too early and does nothing, which is exactly how it behaved.
 *
 * So the disposition is snapshotted before jlibtorrent is loaded at all, and a
 * watchdog puts it back whenever it changes. The poll is a handful of syscalls a
 * second and the window it leaves is a fraction of a second, against a crash that
 * was otherwise certain. What is lost is libtorrent's own crash reporting, which the
 * JVM's error report supersedes anyway.
 *
 * The real fix is libjsig, which interposes sigaction so both handlers live rather
 * than racing to own the slot, and it is preloaded by every launcher: the Gradle
 * `run` task in app/desktop/build.gradle.kts, flatpak/cove.sh, and the `bin/cove`
 * script the release workflow generates. It cannot be done from in here, because
 * libjsig has to observe the JVM installing its handlers in order to chain to them,
 * so loading it after startup does nothing at all.
 *
 * This watchdog stays as the backstop for a launch that misses the preload. It
 * closes the window to a fraction of a second rather than closing it outright — a
 * fault landing inside one poll interval is still fatal — so a
 * "reclaimed the JVM's handler" line in the log means the preload did not apply and
 * that launcher needs fixing, not that everything is fine.
 */
internal object FatalSignalHandlers {
    private const val SIGILL = 4
    private const val SIGBUS = 7
    private const val SIGFPE = 8
    private const val SIGSEGV = 11

    /** SIGABRT is deliberately absent: HotSpot leaves it at its default. */
    private val SIGNALS = listOf(SIGILL, SIGBUS, SIGFPE, SIGSEGV)

    /** `struct sigaction` is 152 bytes on glibc/x86_64; the slack covers other ABIs. */
    private const val STRUCT_BYTES = 512L

    private const val POLL_MILLIS = 200L

    private interface LibC : Library {
        fun sigaction(signum: Int, act: Pointer?, oldact: Pointer?): Int
    }

    private val libc: LibC? = runCatching { Native.load("c", LibC::class.java) }.getOrNull()
    private val saved = ConcurrentHashMap<Int, Memory>()

    @Volatile
    private var watching = false

    /** Snapshots the JVM's handlers and starts putting them back when they change. */
    @Synchronized
    fun guard() {
        if (watching) return
        val library = libc ?: return
        for (signal in SIGNALS) {
            val buffer = Memory(STRUCT_BYTES).apply { clear() }
            val read = library.sigaction(signal, null, buffer)
            Reference.reachabilityFence(buffer)
            if (read == 0) saved[signal] = buffer
        }
        if (saved.isEmpty()) return
        watching = true
        Thread({ watch(library) }, "cove-signal-guard").apply { isDaemon = true }.start()
    }

    private fun watch(library: LibC) {
        val current = Memory(STRUCT_BYTES)
        while (true) {
            for ((signal, expected) in saved) {
                current.clear()
                if (library.sigaction(signal, null, current) != 0) continue
                if (Pointer.nativeValue(current.getPointer(0)) == Pointer.nativeValue(expected.getPointer(0))) {
                    continue
                }
                library.sigaction(signal, expected, null)
                Reference.reachabilityFence(expected)
                System.err.println("Cove torrent: reclaimed the JVM's handler for signal $signal")
            }
            Reference.reachabilityFence(current)
            runCatching { Thread.sleep(POLL_MILLIS) }.getOrElse { return }
        }
    }
}
