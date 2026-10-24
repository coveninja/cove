package com.coveninja.cove.desktop.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.sun.jna.Pointer
import java.lang.ref.Reference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * A new-frame notification for the persistent software-render surfaces.
 *
 * [sequence] deliberately makes every notification distinct even though the
 * underlying bitmaps are reused. Compose therefore invalidates the canvas for
 * every frame without allocating another full-size image.
 */
internal data class SoftwareVideoFrame(
    private val surface: SoftwareVideoSurface,
    val sequence: Long,
) {
    val size: IntSize
        get() = surface.contentSize

    fun draw(scope: DrawScope, destination: IntSize) {
        surface.draw(scope, destination)
    }
}

/**
 * Skia-owned pixels shared by libmpv's software renderer and Compose.
 *
 * mpv's `bgr0` bytes are exactly Skia's little-endian BGRA layout when the
 * bitmap is declared opaque. Rendering straight into this memory removes the
 * old frame-sized direct-buffer -> BufferedImage copy and Compose's second,
 * per-pixel BufferedImage -> Skia conversion.
 *
 * mpv renders into a back buffer while Compose reads the last completed front
 * buffer. Only publishing and drawing hold [monitor]: the native render call
 * can wait for a frame's presentation time and must never hold the UI's lock.
 * [renderMonitor] serializes writers and close without blocking readers.
 */
internal class SoftwareVideoSurface : AutoCloseable {
    private val monitor = Any()
    private val renderMonitor = Any()
    private var front: Buffer? = null
    private var back: Buffer? = null
    private var closed = false

    internal var allocationCount: Int = 0
        private set

    val contentSize: IntSize
        get() = synchronized(monitor) { front?.size ?: IntSize.Zero }

    internal val capacitySize: IntSize
        get() = synchronized(monitor) { front?.capacity ?: IntSize.Zero }

    /**
     * Hands mpv a stable back-buffer address and stride. The block must finish
     * its native write before returning; only then is the frame published.
     */
    fun render(width: Int, height: Int, block: (Pointer, Int) -> Unit) {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        synchronized(renderMonitor) {
            check(!closed) { "Software video surface is closed" }
            val target = writableBuffer(width, height)

            val pixels = checkNotNull(target.bitmap.peekPixels()) {
                "Skia did not expose the software frame pixels"
            }
            try {
                block(Pointer(pixels.addr), pixels.rowBytes)
                target.bitmap.notifyPixelsChanged()
                target.size = IntSize(width, height)
            } finally {
                pixels.close()
                Reference.reachabilityFence(target)
            }
            synchronized(monitor) {
                back = front
                front = target
            }
        }
    }

    fun draw(scope: DrawScope, destination: IntSize) {
        if (destination.width <= 0 || destination.height <= 0) return
        synchronized(monitor) {
            val current = front ?: return
            with(scope) {
                drawImage(
                    image = current.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = current.size,
                    dstOffset = IntOffset.Zero,
                    dstSize = destination,
                )
            }
        }
    }

    /** Test-only pixel inspection without exposing the mutable bitmap. */
    internal fun colorAt(x: Int, y: Int): Int = synchronized(monitor) {
        val current = checkNotNull(front)
        require(x in 0 until current.size.width && y in 0 until current.size.height)
        current.bitmap.getColor(x, y)
    }

    override fun close() {
        synchronized(renderMonitor) {
            synchronized(monitor) {
                if (closed) return
                closed = true
                front?.bitmap?.close()
                back?.bitmap?.close()
                front = null
                back = null
            }
        }
    }

    private fun writableBuffer(requiredWidth: Int, requiredHeight: Int): Buffer {
        val current = back
        if (current != null && requiredWidth <= current.capacity.width && requiredHeight <= current.capacity.height) {
            return current
        }

        val nextWidth = grownCapacity(current?.capacity?.width ?: 0, requiredWidth)
        val nextHeight = grownCapacity(current?.capacity?.height ?: 0, requiredHeight)
        val next = Bitmap()
        val info = ImageInfo(
            nextWidth,
            nextHeight,
            ColorType.BGRA_8888,
            ColorAlphaType.OPAQUE,
            ColorSpace.sRGB,
        )
        if (!next.allocPixels(info)) {
            next.close()
            error("Could not allocate ${nextWidth}x$nextHeight software video surface")
        }

        val result = Buffer(next, next.asComposeImageBitmap(), IntSize(nextWidth, nextHeight))
        back = result
        allocationCount++
        current?.bitmap?.close()
        return result
    }

    private class Buffer(
        val bitmap: Bitmap,
        val image: ImageBitmap,
        val capacity: IntSize,
        var size: IntSize = IntSize.Zero,
    )
}

/** Grow geometrically so a dragged window does not allocate at every pixel. */
internal fun grownCapacity(current: Int, required: Int): Int {
    require(current >= 0 && required > 0)
    val rounded = Math.addExact(required, CAPACITY_QUANTUM - 1) / CAPACITY_QUANTUM * CAPACITY_QUANTUM
    if (current == 0) return rounded
    val grown = Math.addExact(current, current / 2)
    return maxOf(rounded, grown)
}

private const val CAPACITY_QUANTUM = 256
