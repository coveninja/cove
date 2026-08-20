package com.coveninja.cove.desktop

import com.coveninja.cove.backend.platform.DesktopBackendEnvironment
import com.coveninja.cove.backend.platform.DesktopConfigPaths
import java.io.Closeable
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The desktop log file.
 *
 * Everything Cove reports about itself — the torrent engine, the HTTP boundary,
 * background coroutine failures, mpv's running account of why a file would not
 * open — goes to stdout or stderr, and a packaged launch throws both away:
 * jpackage builds a GUI-subsystem `Cove.exe` with no console attached, and a
 * desktop entry or a dock icon has nowhere to print either. Only a launch from a
 * terminal ever saw any of it, which is why bug reports arrived carrying none.
 *
 * [install] *tees* the two streams rather than replacing them, so a terminal
 * launch and `make hot` still print exactly as they did before.
 */
object DesktopLog {
    /** `<data>/logs/cove.log`, with [KeepPreviousFiles] older files beside it. */
    const val FileName = "cove.log"

    /**
     * Rolled at 4 MiB and three older files kept, so the log costs at most 16 MiB
     * of a directory nothing else prunes. Previous runs are kept because the
     * interesting one is usually the run that just died: a viewer who reproduces a
     * crash, restarts and only then goes looking would otherwise find the file
     * already replaced by the launch they are reading it from.
     */
    private const val MaxBytes = 4L * 1024 * 1024
    private const val KeepPreviousFiles = 3

    private var log: RotatingLog? = null

    /**
     * Points stdout and stderr at `<data>/logs/cove.log` and writes the run header.
     *
     * Returns the file, or null if it could not be opened — a log that cannot be
     * written is not a reason to refuse a launch, and the streams are left alone.
     */
    @Synchronized
    fun install(arguments: Array<String> = emptyArray()): Path? {
        log?.let { return it.file }
        val dataDirectory = runCatching { DesktopConfigPaths.dataDirectory() }.getOrNull() ?: return null
        val opened = runCatching {
            RotatingLog(
                directory = dataDirectory.resolve("logs"),
                maxBytes = MaxBytes,
                keep = KeepPreviousFiles,
                header = { header(arguments, dataDirectory) },
            )
        }.getOrNull() ?: return null

        System.setOut(tee(System.out, opened))
        System.setErr(tee(System.err, opened))
        Runtime.getRuntime().addShutdownHook(Thread(opened::close, "cove-log-close"))
        log = opened
        return opened.file
    }

    private fun tee(console: PrintStream, sink: RotatingLog): PrintStream =
        // Autoflush, so a crash still leaves on disk the line that led to it.
        //
        // UTF-8 rather than the console's own charset, which the two share now that
        // one encoder feeds both: the file is the artifact that gets attached to an
        // issue and has to survive the trip, and the only platform whose console
        // disagrees is Windows, where a packaged launch has no console to mis-encode.
        PrintStream(TeeStream(console, sink), true, Charsets.UTF_8)

    private fun header(arguments: Array<String>, dataDirectory: Path): String = buildString {
        val started = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        appendLine("=== Cove ${DesktopBackendEnvironment.appVersion()} ===")
        appendLine("started   $started")
        appendLine(
            "os        ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "(${System.getProperty("os.arch")})",
        )
        appendLine("java      ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        appendLine("data      $dataDirectory")
        appendLine("arguments ${if (arguments.isEmpty()) "(none)" else arguments.joinToString(" ")}")
        appendLine()
    }
}

/** Writes to the console stream first, then mirrors into the log file. */
private class TeeStream(
    private val console: OutputStream,
    private val sink: RotatingLog,
) : OutputStream() {
    override fun write(byte: Int) {
        console.write(byte)
        sink.write(byteArrayOf(byte.toByte()), 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        console.write(bytes, offset, length)
        sink.write(bytes, offset, length)
    }

    override fun flush() {
        console.flush()
        sink.flush()
    }
}

/**
 * A size-bounded log file that stamps each line with the time it was written.
 *
 * Every write is guarded: this sits underneath `System.out`, so a full disk or a
 * data directory that vanished mid-run must cost the log file and nothing else.
 * After a failure it stops writing permanently rather than throwing on every
 * subsequent `println`.
 */
internal class RotatingLog(
    private val directory: Path,
    private val maxBytes: Long,
    private val keep: Int,
    private val header: () -> String,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Closeable {
    private val timestamps = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone)

    private var stream: OutputStream? = null
    private var written = 0L
    private var atLineStart = true
    private var broken = false

    val file: Path = directory.resolve(DesktopLog.FileName)

    init {
        Files.createDirectories(directory)
        rotate()
        open()
    }

    @Synchronized
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        val target = stream ?: return
        guarded {
            var index = offset
            val end = offset + length
            while (index < end) {
                if (atLineStart) {
                    val prefix = timestamps.format(clock()).toByteArray()
                    target.write(prefix)
                    target.write(' '.code)
                    written += prefix.size + 1
                    atLineStart = false
                }
                val newline = bytes.indexOfNewline(index, end)
                val runEnd = if (newline == -1) end else newline + 1
                target.write(bytes, index, runEnd - index)
                written += runEnd - index
                atLineStart = newline != -1
                index = runEnd
            }
            if (written >= maxBytes) roll()
        }
    }

    @Synchronized
    fun flush() {
        guarded { stream?.flush() }
    }

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    /** Starts a fresh file once the current one is full, keeping its tail intact. */
    private fun roll() {
        runCatching { stream?.close() }
        stream = null
        rotate()
        open()
    }

    /** `cove.log` → `cove.log.1` → … → `cove.log.<keep>`, oldest dropped. */
    private fun rotate() {
        if (!Files.exists(file)) return
        // Downwards, so each move lands on a slot already copied out of. The
        // oldest needs no explicit delete: the move onto it replaces it.
        for (index in keep - 1 downTo 1) {
            val older = directory.resolve("${DesktopLog.FileName}.$index")
            if (Files.exists(older)) {
                Files.move(
                    older,
                    directory.resolve("${DesktopLog.FileName}.${index + 1}"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        Files.move(file, directory.resolve("${DesktopLog.FileName}.1"), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun open() {
        stream = Files.newOutputStream(file).buffered()
        written = 0
        atLineStart = true
        // Replayed onto every file, so a rolled one still says which build wrote it.
        guarded {
            val bytes = header().toByteArray()
            stream?.write(bytes)
            written += bytes.size
        }
    }

    private inline fun guarded(body: () -> Unit) {
        if (broken) return
        runCatching(body).onFailure {
            broken = true
            runCatching { stream?.close() }
            stream = null
        }
    }
}

private fun ByteArray.indexOfNewline(from: Int, until: Int): Int {
    for (index in from until until) if (this[index] == '\n'.code.toByte()) return index
    return -1
}
