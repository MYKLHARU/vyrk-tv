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

    /**
     * Main-thread stalls, off unless `log.tag.VyrkPerf` is DEBUG when the app starts: any
     * main-thread message that runs [minMs] or more is logged with what dispatched it and where it
     * spent its time, from stacks sampled every 10 ms (raw names: retrace with the build's
     * mapping.txt). Diagnostics only; no content or identifiers.
     */
    fun watchStalls(minMs: Long = 150) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val main = android.os.Looper.getMainLooper()
        val mainThread = main.thread
        val lock = Any()
        var start = 0L
        var what = ""
        val samples = ArrayList<Array<StackTraceElement>>()
        val reporter = Handler(HandlerThread("VyrkStallReport").apply { start() }.looper)
        main.setMessageLogging { s ->
            if (s.startsWith(">")) synchronized(lock) { start = SystemClock.uptimeMillis(); what = s; samples.clear() }
            else if (s.startsWith("<")) {
                var report: (() -> Unit)? = null
                synchronized(lock) {
                    val ms = SystemClock.uptimeMillis() - start
                    if (start != 0L && ms >= minMs) { val w = what; val got = ArrayList(samples); report = { reportStall(ms, w, got) } }
                    start = 0L
                }
                report?.let { reporter.post(it) }
            }
        }
        Thread({
            while (true) {
                Thread.sleep(10)
                val s0 = synchronized(lock) { start }
                if (s0 != 0L && SystemClock.uptimeMillis() - s0 >= 20) {
                    val st = mainThread.stackTrace
                    synchronized(lock) { if (start == s0) samples += st }
                }
            }
        }, "VyrkStallSampler").apply { isDaemon = true; start() }
    }

    private val genericFrame = Regex("^(java\\.lang\\.(Thread|reflect)|android\\.os\\.(Looper|Handler)|android\\.app\\.ActivityThread|com\\.android\\.internal\\.os\\.)")

    private fun reportStall(ms: Long, what: String, samples: List<Array<StackTraceElement>>) {
        val n = samples.size
        log("stall ${ms}ms samples=$n ${what.take(240)}")
        if (n == 0) return
        val incl = HashMap<String, Int>()
        val self = HashMap<String, Int>()
        for (st in samples) {
            st.firstOrNull()?.let { self.merge(it.toString(), 1, Int::plus) }
            st.map { it.toString() }.filterNot { genericFrame.containsMatchIn(it) }.distinct().forEach { incl.merge(it, 1, Int::plus) }
        }
        self.entries.sortedByDescending { it.value }.take(10).forEach { log("stall self ${it.value}/$n at ${it.key}") }
        incl.entries.sortedByDescending { it.value }.take(40).forEach { log("stall incl ${it.value}/$n at ${it.key}") }
    }
}
