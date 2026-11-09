package com.vyrk.tv.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.art.Artwork
import com.vyrk.tv.VyrkApp
import com.vyrk.tv.ui.LocalGraph
import com.vyrk.tv.ui.browse.Wallpaper
import com.vyrk.tv.ui.browse.HeroLogo
import com.vyrk.tv.ui.browse.rememberTitleArt
import com.vyrk.tv.ui.theme.AccentChoice
import com.vyrk.tv.ui.theme.VyrkTheme
import com.vyrk.tv.ui.theme.Vyrk

/** Loopback fixture for delayed artwork/metadata. Excluded from release APKs. */
class ArtworkProbeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.vyrk.tv.buffercheck")
        val base = intent.getStringExtra("base") ?: error("loopback base required")
        check(Uri.parse(base).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        val token = System.nanoTime()
        fun image(name: String) = "$base/$name.png?probe=$token"
        fun preview(id: String, type: String, name: String) = MetaPreview(id, type, id, background = image(name), logo = image(name))
        val meta = mutableStateOf(preview("shared", "movie", "red"))
        val full = mutableStateOf<MetaItem?>(null)
        val case = intent.getStringExtra("case")
        when (case) {
            "metadata" -> handler.postDelayed({ full.value = MetaItem("shared", "movie", background = image("blue"), logo = image("blue")) }, 3000)
            "type" -> handler.postDelayed({
                full.value = MetaItem("shared", "movie", background = image("red"), logo = image("red"))
                meta.value = preview("shared", "series", "blue")
            }, 3000)
            "missing" -> {
                meta.value = preview("slow", "movie", "slow-red")
                handler.postDelayed({ meta.value = preview("second", "movie", "blue") }, 1500)
                handler.postDelayed({ meta.value = preview("third", "movie", "missing") }, 4000)
            }
            "logo-fallback", "logo-empty", "logo-missing", "logo-slow" -> Unit
            "logo-update" -> {
                meta.value = preview("kitsu:fixture", "series", "missing")
                handler.postDelayed({ full.value = MetaItem("kitsu:fixture", "series", logo = image("blue")) }, 3000)
            }
            else -> error("unknown case")
        }
        setContent {
            CompositionLocalProvider(LocalGraph provides VyrkApp.graph(this)) {
                VyrkTheme(AccentChoice.FROST) {
                    val m = meta.value
                    val art = rememberTitleArt(m, full.value)
                    if (case?.startsWith("logo-") == true) {
                        val logos = when (case) {
                            "logo-fallback" -> listOf(image("missing"), image("blue"))
                            "logo-empty" -> listOf(image("empty"), image("blue"))
                            "logo-missing" -> listOf(image("missing"))
                            "logo-slow" -> listOf(image("long-red"))
                            else -> art.logos
                        }
                        Box(Modifier.fillMaxSize().background(Vyrk.colors.background), contentAlignment = Alignment.Center) {
                            HeroLogo("$case:$token", "Anime fixture", logos, 600.dp, 300.dp, Vyrk.type.displayL)
                        }
                    } else Wallpaper(Artwork.titleKey(m.type, m.id), art.backdrops, Modifier.fillMaxSize())
                }
            }
        }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
