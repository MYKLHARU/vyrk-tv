package com.vyrk.tv.ui

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.KeyEvent

/**
 * Timing for remote input, logged as `VyrkPerf` (no content, no identifiers). Used to measure how
 * long a key press takes to show on screen, and to find slow frames and what they spent time on.
 */
object Perf {
    const val TAG = "VyrkPerf"
    /** Uptime of the last fresh key press (not a repeat). */
    @Volatile var keyDownUptime = 0L
        private set
    @Volatile var keyName = ""
        private set

    fun onKey(e: KeyEvent) {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            keyDownUptime = e.eventTime
            keyName = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        }
    }

    fun sinceKey(): Long = SystemClock.uptimeMillis() - keyDownUptime

    fun log(msg: String) = Log.i(TAG, msg)

    /**
     * Frame timing, off unless enabled with `adb shell setprop log.tag.VyrkPerf DEBUG`: every
     * frame in the 400 ms after a key press and any frame of [slowMs] or more, split into input,
     * animation (Compose recomposition), layout, draw (Compose measures and lays out inside draw,
     * including lazy list items) and GPU. Tab, page and decode lines are always logged.
     */
    fun watchFrames(activity: Activity, slowMs: Long = 34, label: () -> String) {
        if (Build.VERSION.SDK_INT < 24) return
        val thread = HandlerThread("VyrkPerf").apply { start() }
        activity.window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            fun ms(id: Int) = m.getMetric(id) / 1_000_000
            val total = ms(FrameMetrics.TOTAL_DURATION)
            // Every frame in the first 400 ms after a key press, and any slow frame.
            if ((total >= slowMs || sinceKey() < 400) && Log.isLoggable(TAG, Log.DEBUG)) {
                log("frame ${total}ms input=${ms(FrameMetrics.INPUT_HANDLING_DURATION)} anim=${ms(FrameMetrics.ANIMATION_DURATION)} " +
                    "layout=${ms(FrameMetrics.LAYOUT_MEASURE_DURATION)} draw=${ms(FrameMetrics.DRAW_DURATION)} sync=${ms(FrameMetrics.SYNC_DURATION)} " +
                    "gpu=${ms(FrameMetrics.COMMAND_ISSUE_DURATION) + ms(FrameMetrics.SWAP_BUFFERS_DURATION)} key=$keyName+${sinceKey()}ms [${label()}]")
            }
        }, Handler(thread.looper))
    }
}
