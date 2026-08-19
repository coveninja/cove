package com.coveninja.cove.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingLogTest {

    private val directory: Path = Files.createTempDirectory("cove-log-test")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun log(
        maxBytes: Long = 1_000_000,
        keep: Int = 3,
        header: () -> String = { "" },
        now: Instant = Instant.parse("2026-08-19T09:41:07.123Z"),
    ) = RotatingLog(
        directory = directory,
        maxBytes = maxBytes,
        keep = keep,
        header = header,
        clock = { now },
        zone = ZoneId.of("UTC"),
    )

    private fun RotatingLog.print(text: String) {
        val bytes = text.toByteArray()
        write(bytes, 0, bytes.size)
    }

    private fun contents(name: String = DesktopLog.FileName) = directory.resolve(name).readText()

    /** Every file in the directory, for assertions that must survive a roll. */
    private fun everything() =
        directory.toFile().listFiles().orEmpty().joinToString("") { it.readText() }

    // Mutation applied to verify: wrote the bytes without the prefix → test
    // failed, the line arrived with no time on it.
    @Test
    fun `each line is stamped with the time it was written`() {
        log().use { it.print("torrent: piece 4 ready\n") }

        assertEquals("09:41:07.123 torrent: piece 4 ready\n", contents())
    }

    // A println arrives as several writes, and a stack trace as one write holding
    // many lines; the stamp belongs at the start of a line either way.
    // Mutation applied to verify: stamped every write call instead of every line
    // → test failed, the timestamp landed mid-line after "half ".
    @Test
    fun `a stamp is written per line, not per write call`() {
        log().use {
            it.print("half ")
            it.print("a line\nand another\n")
        }

        assertEquals(
            "09:41:07.123 half a line\n09:41:07.123 and another\n",
            contents(),
        )
    }

    // Mutation applied to verify: dropped the rotate before opening → test failed,
    // the previous run was truncated away instead of kept beside the new one.
    @Test
    fun `a new run moves the previous log aside rather than appending to it`() {
        log().use { it.print("first run\n") }
        log().use { it.print("second run\n") }

        assertTrue("second run" in contents(), "the current file holds this run")
        assertFalse("first run" in contents(), "the current file holds only this run")
        assertTrue("first run" in contents("${DesktopLog.FileName}.1"), "the previous run was kept")
    }

    // The reason previous runs are kept at all: reproduce a crash, restart, and
    // the log being read is from the launch doing the reading.
    // Mutation applied to verify: walked the slots upward instead of downward →
    // test failed, each move landed on a slot not yet copied out of and cove.log.2
    // no longer existed.
    @Test
    fun `older runs shift down one slot each launch`() {
        repeat(4) { index -> log().use { it.print("run $index\n") } }

        assertTrue("run 3" in contents(), "the newest run is the current file")
        assertTrue("run 2" in contents("${DesktopLog.FileName}.1"))
        assertTrue("run 1" in contents("${DesktopLog.FileName}.2"))
        assertTrue("run 0" in contents("${DesktopLog.FileName}.3"))
    }

    // Mutation applied to verify: shifted one slot too many (keep downTo 1) →
    // test failed, a cove.log.3 existed beyond the two files asked for.
    @Test
    fun `only the configured number of previous runs is kept`() {
        repeat(6) { index -> log(keep = 2).use { it.print("run $index\n") } }

        val files = directory.toFile().list().orEmpty().toSet()
        assertEquals(setOf("cove.log", "cove.log.1", "cove.log.2"), files)
    }

    // A runaway logger must cost a bounded amount of disk, and the tail is the
    // part worth keeping — it is where the failure that prompted the report is.
    // Mutations applied to verify: dropped the size check, so one file grew without
    // bound → test failed, no rolled file was kept; and stopped writing at the cap
    // rather than rolling → test failed, the last line reached no file at all.
    @Test
    fun `a full file rolls mid-run and keeps writing`() {
        log(maxBytes = 200).use { sink ->
            repeat(20) { sink.print("a line of chatter number $it\n") }
            sink.print("the line that mattered\n")
        }

        assertTrue(Files.exists(directory.resolve("${DesktopLog.FileName}.1")), "the full file was kept")
        assertTrue("the line that mattered" in everything(), "writing continued past the cap")
    }

    // Mutation applied to verify: wrote the header from the constructor rather than
    // from every open → test failed, the file the roll started carried no build.
    @Test
    fun `every file carries the header, including one started by a roll`() {
        log(maxBytes = 120, header = { "=== Cove 1.1.0 ===\n" }).use { sink ->
            repeat(20) { sink.print("chatter $it\n") }
        }

        assertTrue(contents().startsWith("=== Cove 1.1.0 ==="), "the current file identifies the build")
        assertTrue(
            contents("${DesktopLog.FileName}.1").startsWith("=== Cove 1.1.0 ==="),
            "a rolled file identifies the build too",
        )
    }

    // The log sits underneath System.out, so a file that cannot be written must
    // cost the log and nothing else — including at construction, where the header
    // is the first thing written.
    // Mutation applied to verify: wrote the header outside the guard → test failed
    // with the header's exception thrown out of the constructor.
    @Test
    fun `a sink that fails to write gives up instead of throwing`() {
        val sink = log(header = { error("the data directory went away") })

        sink.print("after the failure\n")
        sink.flush()
        sink.close()

        assertEquals("", contents(), "nothing was written after the failure")
    }

    // The shutdown hook closes the log, and teardown keeps printing after it —
    // the torrent engine's own shutdown chatter arrives on the way out.
    // Mutation applied to verify: dereferenced the stream without the null check
    // → test failed with a NullPointerException from a println during shutdown.
    @Test
    fun `writes after close are ignored rather than throwing`() {
        val sink = log()
        sink.print("before\n")
        sink.close()

        sink.print("after the log was closed\n")
        sink.flush()

        assertEquals("09:41:07.123 before\n", contents())
    }
}
