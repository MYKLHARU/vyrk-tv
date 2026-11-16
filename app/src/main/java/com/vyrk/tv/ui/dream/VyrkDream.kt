package com.vyrk.tv.ui.dream

import android.service.dreams.DreamService
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween as tweenSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.vyrk.core.art.Artwork
import com.vyrk.tv.VyrkApp
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.ui.components.VyrkMark
import com.vyrk.tv.ui.components.Wordmark
import com.vyrk.tv.ui.theme.Vyrk
import com.vyrk.tv.ui.theme.VyrkTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VYRK's screensaver (Android's "dream"): the artwork of what you've been watching, one title at
 * a time, slowly drifting, with its name and the time. With nothing watched yet it shows the
 * gold V on the dark background. Choose it in the TV's Settings › Screen saver, where the
 * launcher offers third-party screen savers.
 */
class VyrkDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val graph = VyrkApp.graphOrNull()
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VyrkDream)
            setViewTreeSavedStateRegistryOwner(this@VyrkDream)
            setContent { VyrkTheme { DreamScreen(graph) } }
        }
        setContentView(view)
        window?.decorView?.let { it.setViewTreeLifecycleOwner(this); it.setViewTreeSavedStateRegistryOwner(this) }
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); registry.currentState = Lifecycle.State.RESUMED }
    override fun onDreamingStopped() { super.onDreamingStopped(); registry.currentState = Lifecycle.State.CREATED }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); registry.currentState = Lifecycle.State.DESTROYED }
}

private data class Slide(val title: String, val image: String)

/** Seconds each title stays on screen. */
private const val SLIDE_SECONDS = 14

/** Up to 30 titles you watched most recently, with a backdrop each. */
private suspend fun slides(graph: AppGraph?): List<Slide> {
    graph ?: return emptyList()
    // Android can start the screensaver without the app open: wait for the last profile to load.
    val session = graph.session.value ?: withTimeoutOrNull(8_000) {
        while (graph.profiles.value.isEmpty()) delay(200)
        val p = graph.profiles.value.firstOrNull { it.id == graph.deviceSettings.value.lastProfileId } ?: graph.profiles.value.first()
        graph.openProfile(p)
    } ?: return emptyList()
    withTimeoutOrNull(5_000) { while (session.library.value.isEmpty()) delay(200) }
    return session.library.value.values
        .filter { !it.removed && (it.inLibrary || it.state.lastWatchedMs != null) }
        .sortedByDescending { it.state.lastWatchedMs ?: it.mtimeMs }
        .take(30)
        .mapNotNull { li ->
            val full = graph.metaCache.peek(li.type, li.id)
            val image = Artwork.forBackdrop(full?.background, "w1280") ?: Artwork.metahubBackground(li.id) ?: return@mapNotNull null
            Slide(li.name.ifBlank { full?.name.orEmpty() }, image)
        }
        .shuffled()
}

@Composable
private fun DreamScreen(graph: AppGraph?) {
    val c = Vyrk.colors
    var list by remember { mutableStateOf<List<Slide>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { list = slides(graph) }
    LaunchedEffect(list) { while (list.size > 1) { delay(SLIDE_SECONDS * 1_000L); index = (index + 1) % list.size } }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); delay(15_000) } }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Box(Modifier.fillMaxSize().background(c.background)) {
        if (list.isEmpty()) {
            // Nothing watched yet: the mark, slowly breathing, never in one place for long.
            val t = rememberInfiniteTransition(label = "idle")
            val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(6_000), RepeatMode.Reverse), label = "alpha")
            val dx by t.animateFloat(-120f, 120f, infiniteRepeatable(tween(41_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx")
            val dy by t.animateFloat(-60f, 60f, infiniteRepeatable(tween(29_000, easing = LinearEasing), RepeatMode.Reverse), label = "dy")
            Box(Modifier.align(Alignment.Center).graphicsLayer { alpha = a; translationX = dx; translationY = dy }) {
                VyrkMark(96.dp, accent = c.gold, highlight = c.goldHighlight)
            }
        } else {
            Crossfade(index, animationSpec = androidx.compose.animation.core.tween<Float>(2_000), label = "slide") { i ->
                val s = list[i % list.size]
                KenBurns(s.image, toRight = i % 2 == 0)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.25f to Color.Transparent,
                0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
            // The name moves between the two lower corners, so nothing stays put on the panel.
            val s = list[index % list.size]
            Column(Modifier.align(if (index % 2 == 0) Alignment.BottomStart else Alignment.BottomEnd).padding(56.dp),
                horizontalAlignment = if (index % 2 == 0) Alignment.Start else Alignment.End) {
                Text(s.title, style = Vyrk.type.displayS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.align(if (index % 2 == 0) Alignment.TopEnd else Alignment.TopStart).padding(48.dp),
            horizontalAlignment = if (index % 2 == 0) Alignment.End else Alignment.Start) {
            Text(timeFmt.format(Date(clock)), style = Vyrk.type.displayS, color = c.text.copy(alpha = 0.9f))
            Wordmark(18.dp, Modifier.padding(top = 6.dp))
        }
    }
}

/** One backdrop drifting slowly across and in (the "Ken Burns" effect) while it is shown. */
@Composable
private fun KenBurns(url: String, toRight: Boolean) {
    val zoom = remember(url) { Animatable(1.04f) }
    val pan = remember(url) { Animatable(if (toRight) -0.03f else 0.03f) }
    LaunchedEffect(url) { zoom.animateTo(1.16f, tweenSpec((SLIDE_SECONDS + 3) * 1_000, easing = LinearEasing)) }
    LaunchedEffect(url) { pan.animateTo(if (toRight) 0.03f else -0.03f, tweenSpec((SLIDE_SECONDS + 3) * 1_000, easing = LinearEasing)) }
    AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
        scaleX = zoom.value; scaleY = zoom.value; translationX = pan.value * size.width
    })
}
