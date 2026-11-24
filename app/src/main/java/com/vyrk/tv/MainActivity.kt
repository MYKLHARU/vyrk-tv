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

    /** A search from Google Assistant, the system's search or the remote's Search key; [voice] starts listening. */
    val incomingSearch = MutableStateFlow<SearchRequest?>(null)

    data class SearchRequest(val query: String, val voice: Boolean = false, val nonce: Long = System.nanoTime())

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
                if (com.vyrk.tv.ui.intro.LaunchIntro.handleBack()) return
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
        // The opening, laid over the app as it starts (cold starts only); one that can't play is skipped.
        if (savedInstanceState == null) runCatching { com.vyrk.tv.ui.intro.LaunchIntro.play(this, tv = com.vyrk.tv.ui.phone.FormFactor.isTv(this)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // "Search for Frieren on VYRK" (Assistant) and the system's search hand over a query.
        if (intent?.action == Intent.ACTION_SEARCH || intent?.action == "com.google.android.gms.actions.SEARCH_ACTION") {
            val q = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty().trim()
            incomingSearch.value = SearchRequest(q, voice = false)
            return
        }
        val data = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        // Links from other apps (and adb) are external: they always show a confirmation first.
        incomingLinks.value = IncomingLink(data, external = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While the opening shows, keys skip it (OK, Enter, Back) or do nothing; none reach the screen underneath.
        if (com.vyrk.tv.ui.intro.LaunchIntro.handleKey(event)) return true
        // The remote's Search key opens Search and starts listening.
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) incomingSearch.value = SearchRequest("", voice = true)
            return true
        }
        if (KeyBus.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    fun setAppLanguage(tag: String) {
        getSharedPreferences("locale", MODE_PRIVATE).edit().putString("lang", tag).apply()
        recreate()
    }
}
