package com.vyrk.tv.data

import com.vyrk.core.addon.Redact

/**
 * Recent events for "Copy diagnostics". Every message passes through [Redact.text] so URLs
 * collapse to scheme://host/… and token-like values are removed. Never logs to logcat.
 */
object Diagnostics {
    private const val MAX = 200
    private val events = ArrayDeque<String>()

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${java.time.Instant.now()} [$tag] ${Redact.text(message)}"
        events.addLast(line)
        while (events.size > MAX) events.removeFirst()
    }

    @Synchronized
    fun dump(): List<String> = events.toList()

    /** A step for the test journeys, off unless switched on with `adb shell setprop log.tag.VyrkEvent DEBUG`. */
    fun trace(message: String) {
        if (android.util.Log.isLoggable("VyrkEvent", android.util.Log.DEBUG)) android.util.Log.i("VyrkEvent", message)
    }
}
