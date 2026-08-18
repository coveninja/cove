package com.coveninja.cove.backend.torrent

import com.sun.jna.Library
import com.sun.jna.NativeLibrary

/**
 * Everything that has to be in place before libtorrent's native library is loaded.
 *
 * Both of the problems handled here were crashes that took the whole app down
 * within seconds of starting any torrent, on threads with no Java frames and with
 * no hs_err log to explain them — one from libtorrent claiming the JVM's signal
 * handlers, the other from its statically linked C++ runtime. Order matters: the
 * symbol interposition below only applies to objects loaded after it, and the
 * handler snapshot has to be taken while the handlers are still the JVM's own.
 */
internal object NativePreloads {
    // dlfcn.h values, stable across Linux architectures for these two flags.
    private const val RTLD_NOW = 0x00002
    private const val RTLD_GLOBAL = 0x00100

    @Volatile
    private var done = false

    @Synchronized
    fun install(osName: String = System.getProperty("os.name")) {
        // These mitigations target glibc/libstdc++ and Linux signal ownership.
        // Darwin uses libc++ and different signal constants/structures.
        if (!needsLinuxNativePreloads(osName)) return
        if (done) return
        done = true
        FatalSignalHandlers.guard()
        cxxRuntime()
    }

    /**
     * The system C++ runtime, so libtorrent's exceptions unwind against the same
     * copy that threw them.
     *
     * jlibtorrent statically links its own copy of the C++ exception machinery,
     * while the process already has the system libstdc++ mapped — libmpv, Skiko and
     * JOGL all pull it in. A throw inside libtorrent then entered `__cxa_throw` in
     * the system copy and unwound through `__gxx_personality_v0` in jlibtorrent's
     * static copy; the two keep separate exception state, so the personality routine
     * could not see the exception the other copy had just registered, concluded no
     * handler existed anywhere on the stack, and called std::terminate. libtorrent
     * throws and catches internally as ordinary operation — a failed tracker
     * announce is enough — so any swarm that produced an error aborted the process.
     */
    private fun cxxRuntime() {
        runCatching {
            NativeLibrary.getInstance(
                "stdc++",
                mapOf(Library.OPTION_OPEN_FLAGS to (RTLD_NOW or RTLD_GLOBAL)),
            )
        }.onFailure { System.err.println("Cove torrent: could not preload libstdc++ (${it.message})") }
    }
}

internal fun needsLinuxNativePreloads(osName: String): Boolean =
    osName.startsWith("Linux", ignoreCase = true)
