package com.vyrk.tv.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import java.util.TreeMap

/**
 * Focus trace for the emulator journey: after every key, logs which element holds focus under
 * the tag "VyrkFocus". Labels are element kinds and catalogue IDs only, never URLs or tokens.
 *
 * Each window (main screen, each overlay, the player) is a layer; the topmost layer's last
 * focused element is the current focus, so closing an overlay falls back to the real focus
 * underneath instead of a stale label.
 */
object FocusTrace {
    private val layers = TreeMap<Int, String>()
    private var next = 1

    init { layers[0] = "none" }

    val current: String @Synchronized get() = layers.lastEntry()?.value ?: "none"

    @Synchronized fun set(layer: Int, label: String) { if (layers.containsKey(layer)) layers[layer] = label }
    @Synchronized fun set(label: String) = set(layers.lastKey(), label)
    @Synchronized fun enter(fixed: Int? = null): Int { val l = fixed ?: next++; layers[l] = "none"; return l }
    @Synchronized fun exit(layer: Int) { if (layer != 0) layers.remove(layer) }
}

val LocalTraceLayer = compositionLocalOf { 0 }

fun Modifier.traceFocus(label: String): Modifier = composed {
    val layer = LocalTraceLayer.current
    onFocusChanged { if (it.isFocused) FocusTrace.set(layer, label) }
}

object KeyBus {
    private val main = Handler(Looper.getMainLooper())

    fun onKey(event: KeyEvent): Boolean {
        Perf.onKey(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val key = KeyEvent.keyCodeToString(event.keyCode)
            // Read focus after the event has been handled and focus has moved.
            main.postDelayed({ Log.i("VyrkFocus", "$key -> ${FocusTrace.current}") }, 150)
        }
        return false
    }
}
