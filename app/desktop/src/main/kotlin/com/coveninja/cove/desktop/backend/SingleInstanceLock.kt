package com.coveninja.cove.desktop.backend

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Advisory lock that prevents two instances of the app from running at once.
 * Prefers `$XDG_RUNTIME_DIR` (per-user on Linux) and falls back to
 * `java.io.tmpdir`.
 *
 * The lock file is never deleted on [close].  Deleting it races another
 * process that may have already opened the same path but not yet called
 * `tryLock` — both would then succeed and the guard would be defeated.
 * The file is cheap to leave on disk and the OS cleans it up eventually.
 */
class SingleInstanceLock(
    lockDir: Path = defaultLockDir(),
    lockFileName: String = "cove.lock",
) : Closeable {

    private val channel: FileChannel
    private val lock: FileLock?

    /** `true` if this instance holds the lock; `false` if another process does. */
    val acquired: Boolean

    init {
        val lockPath = lockDir.resolve(lockFileName)
        channel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        // OverlappingFileLockException is thrown when the same JVM already holds
        // the lock (the JVM deduplicates lock ownership per-process, so two
        // FileChannels in one JVM behave as one holder).  Treat it identically
        // to a null return — the lock is not ours.
        lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        acquired = lock != null
    }

    override fun close() {
        lock?.release()
        channel.close()
        // Intentionally not deleting the file — see class-level doc.
    }
}

private fun defaultLockDir(): Path {
    val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")
    return if (!xdgRuntime.isNullOrBlank()) Paths.get(xdgRuntime)
    else Paths.get(System.getProperty("java.io.tmpdir"))
}
