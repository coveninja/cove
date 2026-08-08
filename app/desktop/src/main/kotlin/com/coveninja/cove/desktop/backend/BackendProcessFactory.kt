package com.coveninja.cove.desktop.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Abstraction over a running backend process, injectable for testing. */
interface ManagedProcess {
    val isAlive: Boolean
    fun inputStream(): InputStream
    fun destroy()
    fun destroyForcibly()
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean

    /** Suspends until the process exits and returns its exit code. */
    suspend fun awaitExit(): Int
}

/** Creates a [ManagedProcess] for the Go sidecar. Injectable for testing. */
fun interface BackendProcessFactory {
    fun spawn(executable: Path, port: Int, parentPid: Long): ManagedProcess
}

private class RealManagedProcess(private val process: Process) : ManagedProcess {
    override val isAlive: Boolean get() = process.isAlive
    override fun inputStream(): InputStream = process.inputStream
    override fun destroy() = process.destroy()
    override fun destroyForcibly() { process.destroyForcibly() }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        process.waitFor()
        process.exitValue()
    }
}

/**
 * Production factory.  Two decisions baked in:
 *
 * - Working directory = executable parent so the sidecar's [godotenv] loader
 *   finds the `.env` sitting next to the binary.
 * - `COVE_PARENT_PID` is set to the current JVM PID.  The Go `monitorParent`
 *   goroutine polls this PID and calls `os.Exit` when it disappears, so a
 *   hard-killed JVM (SIGKILL, OOM, power loss) cannot orphan a process
 *   holding port 6969 and breaking the next launch.
 */
val RealBackendProcessFactory = BackendProcessFactory { executable, _, parentPid ->
    val pb = ProcessBuilder(executable.toString())
    pb.directory(executable.parent.toFile())
    pb.redirectErrorStream(true)
    pb.environment()["COVE_PARENT_PID"] = parentPid.toString()
    // anacrolix/torrent's init() selects mmap IO before main() runs; this env
    // var is the only control point.  mmap maps multi-GB torrent files into
    // virtual address space, which balloons RSS on slow-spinning disks.
    pb.environment()["TORRENT_STORAGE_DEFAULT_FILE_IO"] = "classic"
    RealManagedProcess(pb.start())
}
