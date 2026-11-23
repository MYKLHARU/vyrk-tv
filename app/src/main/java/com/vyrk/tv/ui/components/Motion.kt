package com.vyrk.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import com.vyrk.tv.ui.theme.LocalReduceMotion

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
