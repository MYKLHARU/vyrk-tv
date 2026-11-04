package com.vyrk.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vyrk.tv.ui.AppRoot
import com.vyrk.tv.ui.KeyBus
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** Links received from other apps, consumed by the UI after a profile is open. */
    val incomingLinks = MutableStateFlow<IncomingLink?>(null)

    data class IncomingLink(val uri: String, val external: Boolean, val nonce: Long = System.nanoTime())

    override fun attachBaseContext(base: Context) {
        // In-app language override.
        val lang = base.getSharedPreferences("locale", MODE_PRIVATE).getString("lang", "") ?: ""
        if (lang.isEmpty()) { super.attachBaseContext(base); return }
        val locale = Locale.forLanguageTag(lang)
        val cfg = Configuration(base.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val graph = VyrkApp.graph(this)
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
