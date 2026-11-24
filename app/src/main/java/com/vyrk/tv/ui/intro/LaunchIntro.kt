package com.vyrk.tv.ui.intro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import com.vyrk.core.brand.IntroTimeline
import com.vyrk.tv.data.Diagnostics

/**
 * VYRK's opening on a cold start: a V forged from molten gold on dark iron, then VYRK and
 * "Forged for Streaming" beneath it, dissolving into the app (about 3.5 s; see [IntroTimeline]).
 *
 * It lies over the app, which starts and loads underneath it as usual, and is drawn on its own
 * thread ([ForgedIntroView]), so the app's start-up can't make it stutter. OK, Enter, Back or a
 * tap skips it; that press, and any other key pressed while it shows, never reaches the screen
 * underneath. The remote's focus stays with the app the whole time, so it is where the app put
 * it when the opening ends. It plays once per process: not on resume, rotation or a new window.
 * Off with Reduce motion, when Android's animations are off, and with a screen reader. Its sound
 * is off unless switched on (Settings › Opening sound, TV only).
 */
object LaunchIntro {
    private var played = false
    private var active: Layer? = null
    private val swallowing = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())

    /** Keys left alone while the opening shows: the TV's and phone's own volume and power keys. */
    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_TV_POWER,
        KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
    )

    /** Keys that skip it. */
    private val SKIP_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_B,
    )

    fun play(activity: Activity, tv: Boolean) {
        if (played) return
        played = true
        val off = when {
            IntroPrefs.reduceMotion(activity) -> "reduce motion"
            animatorScale(activity) == 0f -> "animations off"
            screenReader(activity) -> "screen reader"
            else -> null
        }
        if (off != null) { Diagnostics.trace("intro: off ($off)"); return }
        val layer = Layer(activity, animatorScale(activity), sound = tv && IntroPrefs.sound(activity))
        active = layer
        activity.addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Diagnostics.trace("intro: start")
    }

    /**
     * From the activity's dispatchKeyEvent, before anything else: true if the opening took the key.
     * A key it took is taken to its release, even if the opening ends in between.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode in swallowing) {
            if (event.action == KeyEvent.ACTION_UP) swallowing.remove(event.keyCode)
            return true
        }
        val layer = active ?: return false
        if (event.keyCode in SYSTEM_KEYS) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            swallowing += event.keyCode
            if (event.keyCode in SKIP_KEYS) layer.skip("key ${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")}")
        }
        return true
    }

    /** Back that arrives through the back dispatcher (Android 16) rather than as a key: skips, if the opening shows. */
    fun handleBack(): Boolean {
        val layer = active ?: return false
        layer.skip("back")
        return true
    }

    /** Android's animator duration scale: 0 when animations are off; above 1 plays the opening in slow motion. */
    private fun animatorScale(context: Context): Float = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private fun screenReader(context: Context): Boolean =
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

    /** The opening's views over the app: a plain cover until the first frame is up, and the surface it plays on. */
    @SuppressLint("ViewConstructor")
    private class Layer(private val activity: Activity, timeScale: Float, sound: Boolean) : FrameLayout(activity), ForgedIntroView.Events {
        private val cover = View(activity).apply { setBackgroundColor(ForgedScene.BASE) }
        private val surface = ForgedIntroView(activity, ForgedScene(activity), timeScale, this)
        private val longest = ((IntroTimeline.END * timeScale.coerceAtLeast(1f) + 2f) * 1000f).toLong()
        private var player: MediaPlayer? = null
        private var started = false
        private var gone = false

        init {
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            if (sound) {
                val app = activity.applicationContext
                Thread({
                    val p = runCatching { MediaPlayer.create(app, com.vyrk.tv.R.raw.intro) }.getOrNull()
                    if (p != null) main.post { if (gone || started) p.release() else player = p }
                }, "VyrkIntroSound").start()
            }
            // If the first frame hasn't come in 3 s (a very slow start, or no surface), let the app show.
            main.postDelayed({ if (!started) end(false, "no first frame") }, 3_000L)
        }

        fun skip(how: String) {
            if (gone) return
            Diagnostics.trace("intro: skip ($how)")
            surface.skip()
            player?.let { fadeOut(it) }
        }

        // Taps anywhere skip; nothing reaches the app underneath.
        override fun onInterceptTouchEvent(ev: MotionEvent) = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) skip("tap")
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent) = true

        override fun onFirstFrame() {
            if (gone) return
            started = true
            cover.visibility = GONE
            // The sound starts with the picture, or not at all (a late sound would be out of step).
            player?.let { p ->
                runCatching {
                    p.setVolume(0.8f, 0.8f)
                    p.setOnCompletionListener { done -> done.release(); if (player === done) player = null }
                    p.start()
                }.onFailure { p.release(); player = null }
            }
            Diagnostics.trace("intro: first frame")
            // Never leave it up, whatever happens to its thread.
            main.postDelayed({ end(false, "timed out") }, longest)
        }

        override fun onEnd(completed: Boolean, stats: String) = end(completed, stats)

        private fun end(completed: Boolean, stats: String) {
            if (gone) return
            gone = true
            (parent as? ViewGroup)?.removeView(this)
            if (active === this) active = null
            // Played to the end, the sound finishes by itself; otherwise it fades out with the picture.
            if (!completed) player?.let { fadeOut(it) }
            Diagnostics.trace("intro: end ${if (completed) "completed" else "interrupted"} $stats")
            // The app's own view had the remote's focus throughout; make sure it still does.
            val root = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            if (root != null && !root.hasFocus()) root.requestFocus()
        }

        private fun fadeOut(p: MediaPlayer) {
            if (player === p) player = null
            var v = 0.8f
            val step = object : Runnable {
                override fun run() {
                    v -= 0.16f
                    if (v <= 0f) { runCatching { p.stop() }; runCatching { p.release() }; return }
                    runCatching { p.setVolume(v, v) }
                    main.postDelayed(this, 40)
                }
            }
            main.post(step)
        }
    }
}
