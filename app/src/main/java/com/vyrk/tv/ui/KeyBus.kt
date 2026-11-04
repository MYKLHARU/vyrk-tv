package com.vyrk.tv.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Focus trace for the emulator journey: after every key, logs which element holds focus under
 * the tag "VyrkFocus". Labels are element kinds and catalogue IDs only, never URLs or tokens.
 */
object FocusTrace {
    @Volatile var current: String = "none"
        private set

    fun set(label: String) { current = label }
}

fun Modifier.traceFocus(label: String): Modifier = onFocusChanged { if (it.isFocused) FocusTrace.set(label) }

object KeyBus {
    private val main = Handler(Looper.getMainLooper())

    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            val key = KeyEvent.keyCodeToString(event.keyCode)
            // Read focus after the event has been handled and focus has moved.
            main.postDelayed({ Log.i("VyrkFocus", "$key -> ${FocusTrace.current}") }, 120)
        }
        return false
    }
}
