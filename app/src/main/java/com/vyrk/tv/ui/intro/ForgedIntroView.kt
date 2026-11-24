package com.vyrk.tv.ui.intro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vyrk.core.brand.IntroTimeline

/**
 * Plays [ForgedScene] on a surface of its own, drawn from a thread of its own at the display's
 * frame rate with a hardware canvas. Nothing here waits for the app's main thread, so the
 * opening stays smooth while the app starts underneath it. The surface sits above the app's
 * window and is translucent, so the closing dissolve shows the app through it.
 *
 * [timeScale] stretches time like Android's animator duration scale (Developer options), which
 * is how to watch it in slow motion on a device.
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

        /** On the main thread, once: [completed] if it played to its end or was skipped, false if it couldn't play on. */
        fun onEnd(completed: Boolean, stats: String)
    }

    private val thread = HandlerThread("VyrkIntro", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Shared with the main thread.
    private val lock = Any()
    private var surfaceReady = false          // guarded by lock
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var skipRequested = false
    @Volatile private var ended = false

    // The intro thread's own.
    private var loaded = false
    private var started = false
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
            if (loaded) maybeStart() else end(false)
        }
    }

    /** Fade out now (OK, Enter, Back or a tap). */
    fun skip() { skipRequested = true }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) { surfaceReady = true }
        handler.post { maybeStart() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Blocks until any frame being drawn is done, so nothing draws on a dead surface.
        synchronized(lock) { surfaceReady = false }
        // Gone before its end (VYRK left, or the screen turned off): the app is simply there next time.
        if (!ended) handler.post { end(started && frames > 0 && IntroTimeline.finished(timeNow(), skippedAt)) }
    }

    private fun maybeStart() {
        if (!loaded || started || ended) return
        if (!synchronized(lock) { surfaceReady }) return
        started = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun timeNow(): Float = if (startNanos == 0L) 0f else ((System.nanoTime() - startNanos) / 1e9f) / timeScale

    private fun doFrame(nanos: Long) {
        if (ended) return
        if (startNanos == 0L) startNanos = nanos
        val t = ((nanos - startNanos) / 1e9f) / timeScale
        if (skipRequested && skippedAt.isNaN()) skippedAt = t
        val visibility = IntroTimeline.visibility(t, skippedAt)
        val drawn = synchronized(lock) {
            if (!surfaceReady) return
            val surface = holder.surface
            val canvas: Canvas? = try { surface.lockHardwareCanvas() } catch (e: Exception) { null }
            if (canvas == null) return@synchronized false
            try {
                scene.draw(canvas, surfaceWidth, surfaceHeight, t, visibility)
            } finally {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
            true
        }
        if (!drawn) { end(false); return }
        frames++
        if (lastNanos != 0L) {
            val ms = (nanos - lastNanos) / 1e6f
            if (ms > 25f) late++
            if (ms > worstMs) worstMs = ms
        }
        lastNanos = nanos
        if (frames == 1) main.post { if (!ended) events.onFirstFrame() }
        if (IntroTimeline.finished(t, skippedAt)) end(true)
        else Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** On the intro thread: stop drawing, free the textures and tell the main thread. */
    private fun end(completed: Boolean) {
        if (ended) return
        ended = true
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val stats = "frames=$frames late=$late worst=${worstMs.toInt()}ms" +
            if (skippedAt.isNaN()) "" else " skipped_at=${String.format(java.util.Locale.ROOT, "%.2f", skippedAt)}"
        main.post {
            events.onEnd(completed, stats)
            // Free the textures only once the view is gone, so no frame can still be using them.
            handler.post { scene.release(); thread.quitSafely() }
        }
    }
}
