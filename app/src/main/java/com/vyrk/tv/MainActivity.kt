package com.vyrk.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vyrk.tv.ui.AppRoot
import com.vyrk.tv.ui.KeyBus
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Links received from other apps, consumed by the UI after a profile is open. */
    val incomingLinks = MutableStateFlow<IncomingLink?>(null)

    data class IncomingLink(val uri: String, val external: Boolean, val nonce: Long = System.nanoTime())

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.vyrk.tv.ui.phone.FormFactor.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            // Phones and tablets: any orientation, the status and navigation bars shown, drawn
            // edge to edge (the phone screens pad themselves), and the keyboard resizes the page.
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
            @Suppress("DEPRECATION")
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        handleIntent(intent)
        // Back arrives through the dispatcher on Android 16; log it for the focus trace.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                KeyBus.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        val graph = VyrkApp.graph(this)
        com.vyrk.tv.ui.Perf.watchFrames(this) { "main" }
        com.vyrk.tv.ui.Perf.watchStalls()
        setContent {
            val ready by graph.ready.collectAsState()
            AppRoot(graph = graph, ready = ready, activity = this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        // Links from other apps (and adb) are external: they always show a confirmation first.
        incomingLinks.value = IncomingLink(data, external = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyBus.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    fun setAppLanguage(tag: String) {
        getSharedPreferences("locale", MODE_PRIVATE).edit().putString("lang", tag).apply()
        recreate()
    }
}
