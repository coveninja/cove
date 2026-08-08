package com.coveninja.cove.desktop.player

import com.jogamp.opengl.GL
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.GLRunnable
import com.jogamp.opengl.awt.GLJPanel
import com.sun.jna.Pointer
import java.awt.Color
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight Swing OpenGL surface for libmpv.
 *
 * GLJPanel renders into an offscreen hardware FBO and then composites as
 * ordinary Swing pixels, so it clips and z-orders correctly inside Compose
 * Desktop's SwingPanel. GLCanvas is a heavyweight peer and would float above
 * everything — never use it here.
 */
class MpvOpenGlPanel : GLJPanel(openGlCapabilities()) {

    interface Renderer {
        /** Called once with the GL context current so mpv can resolve function pointers. */
        fun initialize(getProcAddress: (String) -> Long, nativeX11Display: Pointer?)
        /** Called each frame with the GL context current. */
        fun render(framebuffer: Int, width: Int, height: Int)
        /** Called with the GL context current so mpv can tear down its render context. */
        fun closeRenderer()
        fun reportError(error: Throwable)
    }

    @Volatile private var renderer: Renderer? = null

    private val renderQueued = AtomicBoolean(false)
    private val closed       = AtomicBoolean(false)
    private val initialized  = AtomicBoolean(false)   // GL init has run

    init {
        isOpaque = true
        background = Color.BLACK

        // mpv's FBO is already correctly oriented. Skipping GLJPanel's GLSL
        // vertical flip avoids a second state-sensitive pass and produces the
        // correct image without extra setup. Without this flag, video is upside down.
        setSkipGLOrientationVerticalFlip(true)

        addGLEventListener(object : GLEventListener {
            override fun init(drawable: GLAutoDrawable) {
                initialized.set(true)
                initRenderer(drawable)
            }

            override fun display(drawable: GLAutoDrawable) {
                renderQueued.set(false)
                // If a renderer attached after GL was initialized (late attach),
                // the first display() call must initialize mpv's render context.
                initRenderer(drawable)

                val fbo = IntArray(1)
                drawable.gl.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, fbo, 0)

                renderer?.render(
                    framebuffer = fbo[0],
                    width       = drawable.surfaceWidth.coerceAtLeast(1),
                    height      = drawable.surfaceHeight.coerceAtLeast(1),
                )

                // mpv_render_context_render() returns with framebuffer 0 bound.
                // GLJPanel's readback/composition pass expects its own offscreen
                // FBO still bound when this listener exits — rebind it.
                drawable.gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[0])
            }

            override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, w: Int, h: Int) = Unit

            override fun dispose(drawable: GLAutoDrawable) {
                if (initialized.compareAndSet(true, false)) {
                    renderer?.closeRenderer()
                }
            }
        })
    }

    fun attach(renderer: Renderer) {
        check(this.renderer == null) { "A renderer is already attached" }
        this.renderer = renderer
        requestRender()
    }

    fun requestRender() {
        if (closed.get() || !isDisplayable || !renderQueued.compareAndSet(false, true)) return
        // The render update callback fires on mpv's thread — delegate to EDT.
        EventQueue.invokeLater {
            if (!closed.get() && isDisplayable) {
                runCatching(::display).onFailure { renderer?.reportError(it) }
            } else {
                renderQueued.set(false)
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        requestRender()
    }

    /**
     * Releases libmpv's OpenGL render context with this panel's GL context current,
     * then destroys the panel. Must be called from any thread; marshals to the GL thread.
     */
    fun closeRenderer() {
        if (!closed.compareAndSet(false, true)) return

        val current = renderer
        if (current != null && initialized.get()) {
            val invoked = runCatching {
                invoke(true, GLRunnable {
                    if (initialized.compareAndSet(true, false)) {
                        current.closeRenderer()
                    }
                    true
                })
            }.getOrElse {
                current.reportError(it)
                false
            }
            if (!invoked) {
                current.reportError(
                    IllegalStateException("JOGL could not make the GL context current for shutdown"),
                )
            }
        }
        runCatching(::destroy)
        renderer = null
    }

    private fun initRenderer(drawable: GLAutoDrawable) {
        val current = renderer ?: return
        // Only initialize once per panel lifetime.
        if (initialized.get() && !isRendererInitialized) {
            isRendererInitialized = true
            try {
                val ctx = checkNotNull(drawable.context) { "JOGL did not create a GL context" }
                current.initialize(
                    getProcAddress    = ctx.dynamicLibraryBundle::dynamicLookupFunction,
                    nativeX11Display  = x11Display(drawable),
                )
            } catch (error: Throwable) {
                isRendererInitialized = false
                current.reportError(error)
            }
        }
    }

    // Guarded by single-threaded GL dispatch — no lock needed.
    private var isRendererInitialized = false

    private fun x11Display(drawable: GLAutoDrawable): Pointer? {
        if (!System.getProperty("os.name").contains("linux", ignoreCase = true)) return null
        val handle = runCatching {
            drawable.delegatedDrawable
                .nativeSurface
                .graphicsConfiguration
                .screen
                .device
                .handle
        }.getOrNull() ?: return null
        return if (handle != 0L) Pointer(handle) else null
    }
}

private fun openGlCapabilities(): GLCapabilities {
    GLProfile.initSingleton()
    return GLCapabilities(GLProfile.get(GLProfile.GL3)).apply {
        hardwareAccelerated = true
        doubleBuffered      = false
        redBits   = 8; greenBits = 8; blueBits = 8; alphaBits = 8
        depthBits = 0; stencilBits = 0
    }
}
