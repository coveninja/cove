package com.coveninja.cove.desktop.player

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
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
    fun `software render params survive resize and GC pressure`() {
        val sample = Files.createTempFile("cove-mpv-resize-", ".png")
        ImageIO.write(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB), "png", sample.toFile())

        val frames = AtomicInteger()
        val firstFrame = CountDownLatch(1)
        val highResolutionFrame = CountDownLatch(1)
        val player = MpvSoftwarePlayer(hardwareDecoding = false) { frame ->
            frames.incrementAndGet()
            firstFrame.countDown()
            if (frame.size.width == 3840 && frame.size.height == 2160) {
                highResolutionFrame.countDown()
            }
        }

        try {
            player.start()
            if (!player.snapshot.value.initialized) {
                println("SKIP: libmpv not loadable: ${player.snapshot.value.error}")
                return
            }
            player.load(sample.toAbsolutePath().toString())
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS), "software renderer produced no frame")
            player.resize(3840, 2160)
            assertTrue(
                highResolutionFrame.await(5, TimeUnit.SECONDS),
                "software renderer produced no 3840x2160 frame",
            )

            val collecting = AtomicBoolean(true)
            val collector = thread(name = "cove-mpv-test-gc", isDaemon = true) {
                while (collecting.get()) {
                    System.gc()
                    Thread.yield()
                }
            }
            val before = frames.get()
            val sizes = listOf(
                3840 to 2160, // the high-cost path this regression protects
                1791 to 1015, // dimensions from the reported native crash
                1280 to 720,
                1537 to 863,
                1920 to 1080,
            )
            try {
                val targetFrames = 12
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var index = 0
                while (frames.get() - before < targetFrames && System.nanoTime() < deadline) {
                    val (width, height) = sizes[index % sizes.size]
                    player.resize(width, height)
                    index++
                    Thread.sleep(20)
                }
            } finally {
                collecting.set(false)
                collector.join(5_000)
                assertTrue(!collector.isAlive, "GC pressure thread did not stop")
            }

            val renderedDuringStress = frames.get() - before
            println("software resize/GC stress produced $renderedDuringStress frames")
            assertTrue(renderedDuringStress >= 12, "resize stress rendered too few frames")
            assertTrue(
                player.snapshot.value.error == null,
                "player reported: ${player.snapshot.value.error}",
            )
        } finally {
            player.close()
            Files.deleteIfExists(sample)
        }
    }

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
            MpvSoftwarePlayer { frame: SoftwareVideoFrame ->
                frames.incrementAndGet()
                dimensions.set(frame.size.width to frame.size.height)
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
