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
import kotlinx.coroutines.launch

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
 * The opening: on black, the gold V lights up and a sheen runs across it; then the camera dives
 * into the V and it bursts into ribbons of gold and white light that sweep out to the edges and
 * fade into VYRK. About 2.6 s, once per launch, with VYRK's own sound ([sound]); skipped with
 * Reduce motion.
 */
@Composable
fun LaunchIntro(sound: Boolean = false) {
    if (LaunchIntroState.done || LocalReduceMotion.current) return
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val appear = remember { Animatable(0f) }
    val sheen = remember { Animatable(0f) }
    val dive = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        // The sound is prepared off the main thread, so the animation's first frames aren't held up.
        if (sound) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { android.media.MediaPlayer.create(context.applicationContext, com.vyrk.tv.R.raw.intro)?.apply {
                setVolume(0.8f, 0.8f); setOnCompletionListener { it.release() }; start()
            } }
        }
        appear.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        sheen.animateTo(1f, tween(650, easing = androidx.compose.animation.core.LinearEasing))
        dive.animateTo(1f, tween(1_050, easing = androidx.compose.animation.core.CubicBezierEasing(0.55f, 0f, 0.85f, 0.6f)))
        fade.animateTo(0f, tween(420))
        LaunchIntroState.done = true
        visible = false
    }
    val gold = Vyrk.colors.gold
    val ribbons = remember(gold) { Ribbon.make(44, gold) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        // The light ribbons, spreading out from the V's centre as the camera dives in.
        val fadeEnds = remember {
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Black, 0.22f to androidx.compose.ui.graphics.Color.Transparent,
                0.78f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black)
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val d = dive.value
            if (d <= 0f) return@Canvas
            val w = size.width; val h = size.height; val cx = w / 2f
            val spread = d * d
            for (r in ribbons) {
                val x = cx + r.offset * spread * w * 0.75f
                val bw = w * r.width * (0.4f + spread * 2.2f)
                val a = (r.alpha * (0.25f + d) * (1f - (spread - 0.7f).coerceAtLeast(0f) * 2.2f)).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val top = h * (0.5f - (0.08f + d * 0.7f) * r.reach)
                val bottom = h * (0.5f + (0.08f + d * 0.7f) * r.reach)
                // Plain bars (no per-frame gradients); the soft ends come from the vignette below.
                drawRect(r.color, topLeft = Offset(x - bw / 2f, top), size = androidx.compose.ui.geometry.Size(bw, bottom - top), alpha = a)
            }
            // Fade the ribbons' ends into black, top and bottom.
            drawRect(fadeEnds)
            // A white core flaring at the centre as the dive peaks.
            val flare = (d - 0.55f).coerceAtLeast(0f) * 2.2f
            if (flare > 0f) drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(androidx.compose.ui.graphics.Color.White.copy(alpha = (flare * 0.9f).coerceAtMost(0.9f)), androidx.compose.ui.graphics.Color.Transparent),
                center = Offset(cx, h / 2f), radius = w * (0.1f + flare * 0.5f)))
        }
        // The V: lights up, catches a sheen, then fills the screen as the camera passes through it.
        VyrkMark(150.dp, accent = gold, modifier = Modifier
            .graphicsLayer {
                val a = appear.value; val d = dive.value
                val s = (0.86f + 0.14f * a) * (1f + d * d * 9f)
                scaleX = s; scaleY = s
                alpha = a * (1f - ((d - 0.45f).coerceAtLeast(0f) * 2.4f)).coerceIn(0f, 1f)
                // An offscreen layer only while the sheen needs it; scaling one up during the dive is costly.
                compositingStrategy = if (sheen.value < 1f) androidx.compose.ui.graphics.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                // The sheen: a bright diagonal band crossing the mark once.
                val x = size.width * (-0.4f + sheen.value * 1.8f)
                if (sheen.value in 0.001f..0.999f) drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), androidx.compose.ui.graphics.Color.Transparent),
                        start = Offset(x - size.width * 0.25f, 0f), end = Offset(x + size.width * 0.05f, size.height),
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                )
            })
    }
}

/** One light ribbon: where it sits across the burst, how wide, bright and tall, and its shade. */
private class Ribbon(val offset: Float, val width: Float, val alpha: Float, val reach: Float, val color: androidx.compose.ui.graphics.Color) {
    companion object {
        fun make(n: Int, gold: androidx.compose.ui.graphics.Color): List<Ribbon> {
            val shades = listOf(gold, androidx.compose.ui.graphics.Color(0xFFF3DEB0), androidx.compose.ui.graphics.Color(0xFFB07A2E),
                androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color(0xFFE8B86A))
            val rnd = java.util.Random(4)
            return List(n) {
                val o = (rnd.nextGaussian() * 0.45).toFloat().coerceIn(-1.2f, 1.2f)
                Ribbon(o, 0.003f + rnd.nextFloat() * 0.014f, 0.35f + rnd.nextFloat() * 0.65f, 0.55f + rnd.nextFloat() * 0.6f, shades[rnd.nextInt(shades.size)])
            }
        }
    }
}

private object LaunchIntroState { var done = false }
