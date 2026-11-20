package com.vyrk.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.dp
import com.vyrk.tv.ui.theme.LocalReduceMotion
import com.vyrk.tv.ui.theme.Vyrk

/** Where the last title was opened from (its card's centre, as a fraction of the screen), for the title page to grow out of. */
object OpenOrigin {
    private var center: Offset? = null
    private var atMs = 0L

    fun from(coords: LayoutCoordinates?) {
        val c = coords?.takeIf { it.isAttached } ?: return
        val root = generateSequence(c) { it.parentLayoutCoordinates }.last().size
        if (root.width == 0 || root.height == 0) return
        val b = c.boundsInWindow()
        center = Offset((b.center.x / root.width).coerceIn(0f, 1f), (b.center.y / root.height).coerceIn(0f, 1f))
        atMs = System.currentTimeMillis()
    }

    /** The origin, if a card was just clicked; used once. */
    fun take(): Offset? = center.takeIf { System.currentTimeMillis() - atMs < 2_000 }.also { center = null }
}

/** The title page grows out of the card that opened it and fades in (just appears with Reduce motion). */
@Composable
fun OpenTransition(content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val origin = remember { OpenOrigin.take() }
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val v = p.value
        val s = (if (origin != null) 0.55f else 0.94f) + (1f - if (origin != null) 0.55f else 0.94f) * v
        scaleX = s; scaleY = s
        alpha = (v * 1.6f).coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(origin?.x ?: 0.5f, origin?.y ?: 0.5f)
    }) { content() }
}

/**
 * The opening moment: the gold V draws itself from top to bottom, the wordmark fades in under it,
 * then it all fades away. Once per launch; skipped with Reduce motion.
 */
@Composable
fun LaunchIntro() {
    if (LaunchIntroState.done || LocalReduceMotion.current) return
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val draw = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
        word.animateTo(1f, tween(300))
        kotlinx.coroutines.delay(250)
        fade.animateTo(0f, tween(380))
        LaunchIntroState.done = true
        visible = false
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Vyrk.colors.background), contentAlignment = Alignment.Center) {
        AmbientBackground()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            VyrkMark(120.dp, accent = Vyrk.colors.gold, modifier = Modifier
                .graphicsLayer { val s = 0.9f + 0.1f * draw.value; scaleX = s; scaleY = s }
                .drawWithContent { clipRect(bottom = size.height * draw.value) { this@drawWithContent.drawContent() } })
            Box(Modifier.graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 12f }) { Wordmark(28.dp) }
        }
    }
}

private object LaunchIntroState { var done = false }
