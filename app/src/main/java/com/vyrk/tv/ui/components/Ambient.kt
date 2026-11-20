package com.vyrk.tv.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.vyrk.tv.ui.theme.LocalReduceMotion
import coil3.request.allowHardware
import coil3.toBitmap
import com.vyrk.tv.ui.theme.Vyrk
import java.util.concurrent.ConcurrentHashMap

/**
 * The colour of whatever title is on screen (its backdrop), for a soft glow behind the page.
 * Set by the wallpaper and the title page when their artwork loads; every ambient background follows it.
 */
object AmbientTint {
    var color by mutableStateOf<Color?>(null)
        private set
    private val byUrl = ConcurrentHashMap<String, Int>()
    private const val NONE = 0

    /** Reads [url]'s colour (a 24-pixel copy, cached) and makes it the page's tint. */
    suspend fun follow(context: Context, url: String) {
        val cached = byUrl[url]
        if (cached != null) { color = cached.takeIf { it != NONE }?.let { Color(it) }; return }
        val loader = coil3.SingletonImageLoader.get(context)
        val req = coil3.request.ImageRequest.Builder(context).data(url).size(24, 24)
            .allowHardware(false).build()
        val result = runCatching { loader.execute(req) }.getOrNull() as? coil3.request.SuccessResult ?: return
        val bmp = runCatching { result.image.toBitmap() }.getOrNull() ?: return
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val tint = com.vyrk.core.art.Tint.of(px)
        byUrl[url] = tint ?: NONE
        color = tint?.let { Color(it) }
    }
}

/** A glow in the current title's colour: low on the left and high on the right, easing between titles. */
@Composable
fun AmbientTintGlow(modifier: Modifier = Modifier, strength: Float = 1f) {
    val target = AmbientTint.color ?: Color.Transparent
    val t by animateColorAsState(target, tween(if (LocalReduceMotion.current) 0 else 1_200), label = "ambient-tint")
    Box(modifier.fillMaxSize().drawBehind {
        if (t.alpha == 0f) return@drawBehind
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(listOf(t.copy(alpha = t.alpha * 0.30f * strength), Color.Transparent), center = Offset(w * 0.10f, h * 0.98f), radius = w * 0.55f))
        drawRect(Brush.radialGradient(listOf(t.copy(alpha = t.alpha * 0.16f * strength), Color.Transparent), center = Offset(w * 0.92f, h * 0.10f), radius = w * 0.45f))
    })
}

/** A soft light sweeping across a placeholder while it loads (still with Reduce motion). */
fun Modifier.shimmer(): Modifier = composed {
    val c = Vyrk.colors
    val base = c.raised.copy(alpha = 0.55f)
    if (LocalReduceMotion.current) return@composed this.drawBehind { drawRect(base) }
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 2f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "shimmer-x",
    )
    this.drawWithContent {
        drawRect(base)
        val w = size.width
        drawRect(Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
            start = Offset(w * x - w * 0.6f, 0f), end = Offset(w * x, size.height),
        ))
        drawContent()
    }
}

/** 4K · Dolby Vision · Atmos · 5.1 as small outlined badges; premium ones in gold. */
@Composable
fun QualityBadgeRow(badges: List<com.vyrk.core.stream.QualityBadge>, modifier: Modifier = Modifier) {
    val c = Vyrk.colors
    androidx.compose.foundation.layout.Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        badges.forEach { b ->
            val ink = if (b.premium) c.gold else c.text.copy(alpha = 0.85f)
            Box(
                Modifier.border(1.dp, ink.copy(alpha = if (b.premium) 0.85f else 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(c.shade.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) { androidx.tv.material3.Text(b.label, style = Vyrk.type.labelS, color = ink, maxLines = 1) }
        }
    }
}
