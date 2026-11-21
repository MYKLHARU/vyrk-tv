package com.vyrk.tv.ui.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.toBitmap
import com.vyrk.tv.ui.theme.LocalReduceMotion
import com.vyrk.tv.ui.theme.Vyrk
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the opening needs to remember between launches: the title Home featured last (its id and
 * backdrop, which the V opens onto and Home starts on), and the day the full opening last played.
 */
object IntroPrefs {
    private const val FILE = "vyrk_intro"

    /** The remembered featured title: its id, its artwork key (as the wallpaper uses) and its backdrop. */
    data class Featured(val id: String, val key: String, val backdrop: String)

    fun featured(context: Context): Featured? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val id = p.getString("featured_id", null) ?: return null
        val key = p.getString("featured_key", null) ?: return null
        val url = p.getString("featured_backdrop", null) ?: return null
        return Featured(id, key, url)
    }

    /** Home calls this whenever its featured title changes. */
    fun setFeatured(context: Context, id: String, key: String, backdrop: String) {
        if (featured(context) == Featured(id, key, backdrop)) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("featured_id", id).putString("featured_key", key).putString("featured_backdrop", backdrop).apply()
    }

    /** [candidates] with the remembered featured title first, so Home opens on what the V showed. */
    fun <T> preferFeatured(context: Context, candidates: List<T>, id: (T) -> String): List<T> {
        val saved = featured(context)?.id ?: return candidates
        val i = candidates.indexOfFirst { id(it) == saved }
        return if (i <= 0) candidates else listOf(candidates[i]) + candidates.filterIndexed { j, _ -> j != i }
    }

    /** True the first time it's asked on a given day (and remembers that it was). */
    fun firstLaunchToday(context: Context): Boolean {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (p.getString("full_day", null) == today) return false
        p.edit().putString("full_day", today).apply()
        return true
    }
}

/**
 * VYRK's opening. On black, the gold V lights up and a sheen crosses it; then the camera dives
 * into the V, which turns into a window onto the title Home is featuring, and the picture opens
 * out to fill the screen as Home takes over behind it. The full version (about 2.8 s, with the
 * three-note sound logo) plays on the first launch of the day; later launches get a 1-second
 * sting. Without a featured picture yet (first run) the V bursts into gold light instead.
 * Skipped with Reduce motion.
 */
@Composable
fun LaunchIntro(sound: Boolean = false) {
    if (IntroState.done || LocalReduceMotion.current) return
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val context = LocalContext.current
    val full = remember { IntroPrefs.firstLaunchToday(context) }
    val featured = remember { IntroPrefs.featured(context) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val appear = remember { Animatable(0f) }
    val sheen = remember { Animatable(0f) }
    val dive = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        // The featured backdrop is usually in the image cache already; don't hold the opening for it.
        featured?.backdrop?.let { url ->
            launch {
                picture = withTimeoutOrNull(1_500) {
                    val dm = context.resources.displayMetrics
                    val req = coil3.request.ImageRequest.Builder(context).data(url).size(dm.widthPixels, dm.heightPixels).build()
                    (coil3.SingletonImageLoader.get(context).execute(req) as? coil3.request.SuccessResult)?.image?.toBitmap()?.asImageBitmap()
                }
            }
        }
        if (sound) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                android.media.MediaPlayer.create(context.applicationContext, if (full) com.vyrk.tv.R.raw.intro else com.vyrk.tv.R.raw.intro_sting)?.apply {
                    setVolume(0.8f, 0.8f); setOnCompletionListener { it.release() }; start()
                }
            }
        }
        if (full) {
            appear.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            sheen.animateTo(1f, tween(600, easing = LinearEasing))
            dive.animateTo(1f, tween(1_300, easing = CubicBezierEasing(0.5f, 0f, 0.75f, 1f)))
        } else {
            appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            sheen.snapTo(1f)
            dive.animateTo(1f, tween(700, easing = CubicBezierEasing(0.45f, 0f, 0.7f, 1f)))
        }
        fade.animateTo(0f, tween(if (full) 420 else 280))
        IntroState.done = true
        visible = false
    }
    val gold = Vyrk.colors.gold
    val light = Color(0xFFF3DEB0)
    val ribbons = remember(gold) { Ribbon.make(44, gold) }
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        drawRect(Color.Black)
        val a = appear.value
        val d = dive.value
        val img = picture
        val mark = 150.dp.toPx()
        // Zoom grows exponentially, so the dive feels like a steady push forward; at the end one
        // blade of the V is wider than the screen.
        val zoom = (0.86f + 0.14f * a) * exp(d * ln(MAX_ZOOM))
        val start = Offset(size.width / 2f + (PIVOT.x - 50f) / 100f * mark, size.height / 2f + (PIVOT.y - 50f) / 100f * mark)
        val pivotOnScreen = start + (Offset(size.width / 2f, size.height / 2f) - start) * d
        val unit = mark / 100f * zoom
        fun pt(x: Float, y: Float) = Offset(pivotOnScreen.x + (x - PIVOT.x) * unit, pivotOnScreen.y + (y - PIVOT.y) * unit)
        val v = vPath(::pt)
        val alpha = a
        // Soft gold light behind the mark as it appears.
        if (d < 0.6f) drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.22f * alpha * (1f - d / 0.6f)), Color.Transparent),
            center = pt(50f, 50f), radius = 1.4f * unit * 100f), radius = 1.4f * unit * 100f, center = pt(50f, 50f))
        val window = if (img != null) ((d - 0.12f) / 0.4f).coerceIn(0f, 1f) else 0f
        if (img == null && d > 0f) drawRibbons(ribbons, d)
        // The V: gold, turning into a window onto the featured picture.
        if (window < 1f) {
            drawPath(v, Brush.linearGradient(listOf(light, gold, gold.copy(alpha = 0.8f)), pt(92f, 14f), pt(30f, 88f)), alpha = alpha * (1f - window) *
                (if (img == null) (1f - ((d - 0.45f).coerceAtLeast(0f) * 2.4f)).coerceIn(0f, 1f) else 1f))
        }
        if (img != null && window > 0f) clipPath(v) {
            val s = 1.12f - 0.12f * d
            drawCover(img, size, s, window)
        }
        // The sheen: one bright band crossing the gold.
        if (sheen.value in 0.001f..0.999f) clipPath(v) {
            val x = pt(50f, 50f).x + (sheen.value * 1.8f - 0.9f) * unit * 100f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.8f), Color.Transparent),
                start = Offset(x - unit * 30f, 0f), end = Offset(x + unit * 6f, size.height)))
        }
        // A thin gold rim keeps the V's shape readable as the picture shows through it.
        if (img != null && window > 0f) {
            val rim = (1f - ((d - 0.75f) / 0.25f).coerceIn(0f, 1f)) * alpha
            if (rim > 0f) drawPath(v, gold.copy(alpha = 0.9f * rim), style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

private const val MAX_ZOOM = 140f
/** Where the camera dives in: a point inside the V's long left blade, in the mark's 0–100 space. */
private val PIVOT = Offset(40f, 55f)

/** The V's two blades and its rune notch, as in [VyrkMark], placed by [pt]. */
private fun vPath(pt: (Float, Float) -> Offset): Path = Path().apply {
    fun poly(vararg xy: Float) {
        val o = pt(xy[0], xy[1]); moveTo(o.x, o.y)
        var i = 2
        while (i < xy.size) { val q = pt(xy[i], xy[i + 1]); lineTo(q.x, q.y); i += 2 }
        close()
    }
    poly(8f, 14f, 30f, 14f, 54f, 70f, 50f, 88f)
    poly(92f, 14f, 70f, 14f, 56f, 47f, 63f, 63f)
}

/** [img] scaled to cover the screen (times [scale]), centred. */
private fun DrawScope.drawCover(img: ImageBitmap, screen: Size, scale: Float, alpha: Float) {
    val k = max(screen.width / img.width, screen.height / img.height) * scale
    val w = img.width * k; val h = img.height * k
    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(((screen.width - w) / 2f).toInt(), ((screen.height - h) / 2f).toInt()), dstSize = IntSize(w.toInt(), h.toInt()), alpha = alpha)
}

/** First run (no featured picture yet): the V bursts into gold and white light instead. */
private fun DrawScope.drawRibbons(ribbons: List<Ribbon>, d: Float) {
    val w = size.width; val h = size.height; val cx = w / 2f
    val spread = d * d
    for (r in ribbons) {
        val x = cx + r.offset * spread * w * 0.75f
        val bw = w * r.width * (0.4f + spread * 2.2f)
        val a = (r.alpha * (0.25f + d) * (1f - (spread - 0.7f).coerceAtLeast(0f) * 2.2f)).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val top = h * (0.5f - (0.08f + d * 0.7f) * r.reach)
        val bottom = h * (0.5f + (0.08f + d * 0.7f) * r.reach)
        drawRect(r.color, topLeft = Offset(x - bw / 2f, top), size = Size(bw, bottom - top), alpha = a)
    }
    drawRect(Brush.verticalGradient(0f to Color.Black, 0.22f to Color.Transparent, 0.78f to Color.Transparent, 1f to Color.Black))
    val flare = (d - 0.55f).coerceAtLeast(0f) * 2.2f
    if (flare > 0f) drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = (flare * 0.9f).coerceAtMost(0.9f)), Color.Transparent),
        center = Offset(cx, h / 2f), radius = w * (0.1f + flare * 0.5f)))
}

private class Ribbon(val offset: Float, val width: Float, val alpha: Float, val reach: Float, val color: Color) {
    companion object {
        fun make(n: Int, gold: Color): List<Ribbon> {
            val shades = listOf(gold, Color(0xFFF3DEB0), Color(0xFFB07A2E), Color.White, Color(0xFFE8B86A))
            val rnd = java.util.Random(4)
            return List(n) {
                val o = (rnd.nextGaussian() * 0.45).toFloat().coerceIn(-1.2f, 1.2f)
                Ribbon(o, 0.003f + rnd.nextFloat() * 0.014f, 0.35f + rnd.nextFloat() * 0.65f, 0.55f + rnd.nextFloat() * 0.6f, shades[rnd.nextInt(shades.size)])
            }
        }
    }
}

private object IntroState { var done = false }
