package com.vyrk.tv.ui.intro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vyrk.core.brand.IntroTimeline
import com.vyrk.tv.data.Diagnostics
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays [ForgedScene] on a surface of its own, with an OpenGL ES 2 context of its own, from a
 * thread of its own, at the display's frame rate. It shares nothing with the app's drawing (not
 * the main thread, not the app's render thread), so the opening stays smooth while the app starts
 * underneath, and the app never waits for it. The surface sits above the app's window and is
 * translucent, so the closing dissolve shows the app through it.
 *
 * [timeScale] stretches time like Android's animator duration scale (Developer options), which
 * is how to watch it in slow motion on a device. If drawing falls behind early on, the scene drops
 * its smoke and out-of-focus embers.
 */
@SuppressLint("ViewConstructor")
internal class ForgedIntroView(
    context: Context,
    private val scene: ForgedScene,
    private val timeScale: Float,
    private val events: Events,
) : SurfaceView(context), SurfaceHolder.Callback {

    interface Events {
        /** On the main thread, once the first frame is on screen. */
        fun onFirstFrame()

        /** On the main thread, once: [completed] if it played to its end or was skipped, false if it couldn't go on. */
        fun onEnd(completed: Boolean, stats: String)
    }

    private val thread = HandlerThread("VyrkIntro", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Shared with the main thread.
    @Volatile private var surfaceAvailable = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var skipRequested = false
    @Volatile private var ended = false

    // The opening's own thread only.
    private var loaded = false
    private var started = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glReady = false
    private var startNanos = 0L
    private var lastNanos = 0L
    private var skippedAt = Float.NaN
    private var frames = 0
    private var late = 0
    private var worstMs = 0f

    private val frameCallback = Choreographer.FrameCallback { doFrame(it) }

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        handler.post {
            loaded = runCatching { scene.load() }.isSuccess
            if (loaded) maybeStart() else end(false, "couldn't load")
        }
    }

    /** Fade out now (OK, Enter, Back or a tap). */
    fun skip() { skipRequested = true }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        handler.post { maybeStart() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        // Let the opening's thread let go of the surface before it goes; never wait long on it.
        val released = CountDownLatch(1)
        if (!handler.post { if (!ended) end(false, "surface gone"); released.countDown() }) return
        released.await(300, TimeUnit.MILLISECONDS)
    }

    private fun maybeStart() {
        if (!loaded || started || ended || !surfaceAvailable) return
        started = true
        val ok = runCatching { initEgl(); scene.initGl(); glReady = true }
        if (ok.isFailure) { end(false, "no GL: ${ok.exceptionOrNull()?.message}"); return }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no RGBA8888 config" }
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${EGL14.eglGetError()}" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent ${EGL14.eglGetError()}" }
    }

    private fun releaseEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (glReady) runCatching { scene.releaseGl() }
        glReady = false
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun doFrame(nanos: Long) {
        if (ended || !glReady) return
        if (!surfaceAvailable) { end(false, "surface gone"); return }
        if (startNanos == 0L) startNanos = nanos
        val t = ((nanos - startNanos) / 1e9f) / timeScale
        if (skipRequested && skippedAt.isNaN()) skippedAt = t
        val drawn = runCatching {
            scene.draw(surfaceWidth, surfaceHeight, t, IntroTimeline.visibility(t, skippedAt))
            EGL14.eglSwapBuffers(display, eglSurface)
        }.getOrDefault(false)
        if (!drawn) { end(false, "draw failed"); return }
        frames++
        if (lastNanos != 0L) {
            val ms = (nanos - lastNanos) / 1e6f
            if (ms > 25f) late++
            if (ms > worstMs) worstMs = ms
        }
        lastNanos = nanos
        // Falling behind from the start: a slow device. Draw less rather than stutter.
        if (frames == 15 && late >= 5 && !scene.lite) {
            scene.lite = true
            val n = late
            main.post { Diagnostics.trace("intro: lite ($n of 15 frames late)") }
        }
        if (frames == 1) main.post { if (!ended) events.onFirstFrame() }
        if (IntroTimeline.finished(t, skippedAt)) end(true, "")
        else Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** On the opening's thread: stop drawing, let go of GL and the surface, and tell the main thread. */
    private fun end(completed: Boolean, why: String) {
        if (ended) return
        ended = true
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        runCatching { releaseEgl() }
        scene.release()
        val stats = buildString {
            append("frames=$frames late=$late worst=${worstMs.toInt()}ms")
            if (!skippedAt.isNaN()) append(" skipped_at=").append(String.format(Locale.ROOT, "%.2f", skippedAt))
            if (scene.lite) append(" lite")
            if (why.isNotEmpty()) append(" (").append(why).append(')')
        }
        main.post { events.onEnd(completed, stats) }
        thread.quitSafely()
    }
}
