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
 * A new-frame notification for the one persistent software-render target.
 *
 * [sequence] deliberately makes every notification distinct even though the
 * underlying bitmap is reused. Compose therefore invalidates the canvas for
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
 * Both native writes and Compose reads hold [monitor]. That keeps a single
 * bounded allocation safe: mpv cannot overwrite pixels while Skia is drawing
 * them, and a resize cannot close a bitmap which a draw still references.
 */
internal class SoftwareVideoSurface : AutoCloseable {
    private val monitor = Any()

    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var capacityWidth = 0
    private var capacityHeight = 0
    private var width = 0
    private var height = 0
    private var closed = false

    internal var allocationCount: Int = 0
        private set

    val contentSize: IntSize
        get() = synchronized(monitor) { IntSize(width, height) }

    internal val capacitySize: IntSize
        get() = synchronized(monitor) { IntSize(capacityWidth, capacityHeight) }

    /**
     * Hands mpv a stable address and stride while holding the same lock used by
     * [draw]. The block must finish its native write before returning.
     */
    fun render(width: Int, height: Int, block: (Pointer, Int) -> Unit) {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        synchronized(monitor) {
            check(!closed) { "Software video surface is closed" }
            ensureCapacity(width, height)

            val target = checkNotNull(bitmap)
            val pixels = checkNotNull(target.peekPixels()) {
                "Skia did not expose the software frame pixels"
            }
            try {
                this.width = width
                this.height = height
                block(Pointer(pixels.addr), pixels.rowBytes)
                target.notifyPixelsChanged()
            } finally {
                pixels.close()
                Reference.reachabilityFence(target)
            }
        }
    }

    fun draw(scope: DrawScope, destination: IntSize) {
        if (destination.width <= 0 || destination.height <= 0) return
        synchronized(monitor) {
            val current = image ?: return
            if (closed || width <= 0 || height <= 0) return
            with(scope) {
                drawImage(
                    image = current,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(width, height),
                    dstOffset = IntOffset.Zero,
                    dstSize = destination,
                )
            }
        }
    }

    /** Test-only pixel inspection without exposing the mutable bitmap. */
    internal fun colorAt(x: Int, y: Int): Int = synchronized(monitor) {
        require(x in 0 until width && y in 0 until height)
        checkNotNull(bitmap).getColor(x, y)
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            image = null
            bitmap?.close()
            bitmap = null
            capacityWidth = 0
            capacityHeight = 0
            width = 0
            height = 0
        }
    }

    private fun ensureCapacity(requiredWidth: Int, requiredHeight: Int) {
        if (bitmap != null && requiredWidth <= capacityWidth && requiredHeight <= capacityHeight) return

        val nextWidth = grownCapacity(capacityWidth, requiredWidth)
        val nextHeight = grownCapacity(capacityHeight, requiredHeight)
        val next = Bitmap()
        val info = ImageInfo(
            nextWidth,
            nextHeight,
            ColorType.BGRA_8888,
            ColorAlphaType.OPAQUE,
            ColorSpace.sRGB,
        )
        check(next.allocPixels(info)) {
            "Could not allocate ${nextWidth}x$nextHeight software video surface"
        }

        val previous = bitmap
        bitmap = next
        image = next.asComposeImageBitmap()
        capacityWidth = nextWidth
        capacityHeight = nextHeight
        allocationCount++
        previous?.close()
    }
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
