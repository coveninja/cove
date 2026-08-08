package com.coveninja.cove.desktop.player

import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

// Exercises the real libmpv software render path end to end: decode a file and
// count frames that actually reach the consumer. Exiting 0 only proves the
// process did not crash; it does not prove a single pixel was produced, which
// is the failure mode this path is prone to.
//
// Skips rather than fails when libmpv or the sample file is unavailable, so the
// suite still runs on machines without them. COVE_MPV_SAMPLE points at a video.
class SoftwareFrameSmokeTest {

    @Test
    fun `software path decodes frames from a real file`() {
        val sample = System.getenv("COVE_MPV_SAMPLE")
        if (sample.isNullOrBlank() || !java.io.File(sample).isFile) {
            println("SKIP: set COVE_MPV_SAMPLE to a video file to run this test")
            return
        }

        val frames = AtomicInteger()
        val dimensions = AtomicReference<Pair<Int, Int>>()
        val player = try {
            MpvSoftwarePlayer { frame: BufferedImage ->
                frames.incrementAndGet()
                dimensions.set(frame.width to frame.height)
            }
        } catch (e: UnsatisfiedLinkError) {
            println("SKIP: libmpv not loadable: ${e.message}")
            return
        }

        try {
            player.start()
            player.load(sample)
            // Well above a frame interval at any sane rate, but short enough to
            // keep the suite quick.
            Thread.sleep(3_000)

            val count = frames.get()
            println("software path produced $count frames, size=${dimensions.get()}")
            assertTrue(count > 0, "software render path produced no frames at all")
            assertTrue(
                player.snapshot.value.error == null,
                "player reported: ${player.snapshot.value.error}",
            )
        } finally {
            player.close()
        }
    }
}
