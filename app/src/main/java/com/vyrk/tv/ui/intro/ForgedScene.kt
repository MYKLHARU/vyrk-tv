package com.vyrk.tv.ui.intro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.vyrk.core.brand.Emblem
import com.vyrk.core.brand.IntroTimeline as T
import com.vyrk.tv.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The forged V, drawn frame by frame on a hardware canvas (see [ForgedIntroView]). Everything
 * moves by the clock in [T]; the textures come from tools/gen_intro.py and the V's geometry from
 * [Emblem]. tools/preview_intro.py renders the same scene off the device: keep the two in step.
 *
 * Layers, back to front: iron lit by the forge, a vignette to the screen's shape, smoke and
 * embers behind; the V (its light on the iron, molten fill, gold, the molten line, the pulse and
 * its glint); VYRK and the tagline; embers and smoke in front.
 */
internal class ForgedScene(private val context: Context) {
    private lateinit var iron: Bitmap
    private lateinit var gold: Bitmap
    private lateinit var molten: Bitmap
    private lateinit var smokeBmp: Bitmap
    private lateinit var glow: Bitmap
    private lateinit var bokeh: Bitmap
    private lateinit var goldShader: BitmapShader
    private lateinit var moltenShader: BitmapShader
    private lateinit var wordFace: Typeface
    private var tagFace: Typeface? = null
    private val tagline = context.getString(R.string.tagline)

    private val bitmaps = ArrayList<Bitmap>()

    /** Decodes the textures and fonts. Call on the intro's own thread, before the first [draw]. */
    fun load() {
        val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        fun bmp(id: Int, mip: Boolean) = BitmapFactory.decodeResource(context.resources, id, opts)!!.also {
            bitmaps += it
            it.setHasMipMap(mip)
            if (Build.VERSION.SDK_INT >= 24) it.prepareToDraw() // upload now, not on the first visible frame
        }
        iron = bmp(R.drawable.intro_iron, mip = false)
        gold = bmp(R.drawable.intro_gold, mip = true)
        molten = bmp(R.drawable.intro_molten, mip = true)
        smokeBmp = bmp(R.drawable.intro_smoke, mip = false)
        glow = bmp(R.drawable.intro_glow, mip = true)
        bokeh = bmp(R.drawable.intro_bokeh, mip = true)
        // The textures cover the mark's 0-100 box.
        goldShader = BitmapShader(gold, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(100f / gold.width, 100f / gold.height) })
        }
        moltenShader = BitmapShader(molten, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(100f / molten.width, 100f / molten.height) })
        }
        // The wordmark as the app sets it (system serif, semi-bold), and the UI font for the tagline.
        wordFace = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SERIF, 600, false) else Typeface.create(Typeface.SERIF, Typeface.BOLD)
        tagFace = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) }.getOrNull()
        wordPaint.typeface = wordFace
        wordHighlight.typeface = wordFace
        tagPaint.typeface = tagFace ?: Typeface.DEFAULT
        if (Build.VERSION.SDK_INT >= 26) tagPaint.setFontVariationSettings("'wght' 500")
    }

    /** Frees the textures. Call on the intro's own thread, after its last [draw]. */
    fun release() {
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }

    // ------------------------------------------------------------------ paints and scratch objects
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = add
    }
    private val vignettePaint = Paint()
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val wordHighlight = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { xfermode = add }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tints = HashMap<Int, PorterDuffColorFilter>()
    private val rect = RectF()
    private val segment = Path()
    private val pos = FloatArray(2)

    private fun tint(color: Int) = tints.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    // The V: one path for filling all three pieces, and each piece's outline in the order the molten line runs.
    private val vPath = Path().apply { for (p in Emblem.PIECES) addPiece(this, p) }
    private val traces = Emblem.PIECES.map { piece ->
        val path = Path().also { addPiece(it, Emblem.traceOrder(piece)) }
        PathMeasure(path, false)
    }

    private fun addPiece(path: Path, pts: List<Emblem.Point>) {
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        path.close()
    }

    // ------------------------------------------------------------------ layout
    private var lw = 0
    private var lh = 0
    private var e = 0f          // the mark's box at its final size, px
    private var boxY = 0f
    private var wordSize = 0f
    private var wordBase = 0f
    private var tagSize = 0f
    private var tagBase = 0f

    private fun layout(w: Int, h: Int) {
        if (w == lw && h == lh) return
        lw = w; lh = h
        // The lockup (V, VYRK, tagline) fills about half the height on a TV, and stays inside
        // the width on a phone held upright.
        e = min(0.41f * h, 0.60f * w)
        wordSize = 0.255f * e
        tagSize = 0.090f * e
        val capW = 0.70f * wordSize
        val capT = 0.70f * tagSize
        val gap1 = 0.15f * e
        val gap2 = 0.11f * e
        val total = 0.74f * e + gap1 + capW + gap2 + capT
        val top = h * 0.5f - total / 2f - 0.012f * h
        boxY = top - 0.14f * e
        wordBase = top + 0.74f * e + gap1 + capW
        tagBase = wordBase + gap2 + capT
        // The edges fall away to black, to the screen's own shape.
        val rx = 0.5f * w
        val ry = min(0.5f * h, 0.7f * w)
        vignettePaint.shader = RadialGradient(
            0f, 0f, 1.25f,
            intArrayOf(BASE and 0xFFFFFF, BASE and 0xFFFFFF, alpha(BASE, 0.62f), alpha(BASE, 0.97f)),
            floatArrayOf(0f, 0.45f / 1.25f, 0.85f / 1.25f, 1f), Shader.TileMode.CLAMP,
        ).apply { setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(0.5f * w, 0.53f * h) }) }
    }

    // ------------------------------------------------------------------ frame
    /** Draws the opening at [t] seconds, everything faded to [visibility]. */
    fun draw(c: Canvas, w: Int, h: Int, t: Float, visibility: Float) {
        if (w <= 0 || h <= 0) return
        layout(w, h)
        val fading = visibility < 0.999f
        if (fading) {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (visibility <= 0.002f) return
            c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (visibility * 255).toInt())
        }
        c.drawColor(BASE, PorterDuff.Mode.SRC)
        val push = T.push(t)
        val reveal = T.reveal(t)
        val flicker = 1f + 0.03f * sin(2f * PI.toFloat() * 1.7f * t) + 0.02f * sin(2f * PI.toFloat() * 4.3f * t + 1f)
        drawIron(c, w, h, push, reveal, flicker)
        sprite(c, glow, w / 2f, h * 0.64f, 1.3f * max(w, h), 0.9f * max(w, h), 0.035f * reveal * flicker, 0xFFFF7A2A.toInt(), true)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignettePaint)
        drawSmoke(c, t, w, h, push, reveal, front = false)
        drawEmbers(c, t, w, h, push, reveal, front = false)
        drawEmblem(c, t, w, h, push)
        drawWords(c, t, w, h, push)
        drawEmbers(c, t, w, h, push, reveal, front = true)
        drawSmoke(c, t, w, h, push, reveal, front = true)
        if (fading) c.restore()
    }

    private fun drawIron(c: Canvas, w: Int, h: Int, push: Float, reveal: Float, flicker: Float) {
        val sc = max(w.toFloat() / iron.width, h.toFloat() / iron.height) * (1f + T.PUSH_BACKGROUND * push)
        val iw = iron.width * sc
        val ih = iron.height * sc
        rect.set(w / 2f - iw / 2f, h / 2f - ih / 2f, w / 2f + iw / 2f, h / 2f + ih / 2f)
        bitmapPaint.alpha = (min(1f, reveal * flicker) * 255).toInt()
        c.drawBitmap(iron, null, rect, bitmapPaint)
    }

    /** A white sprite, tinted, centred on (cx, cy). */
    private fun sprite(c: Canvas, bmp: Bitmap, cx: Float, cy: Float, sw: Float, sh: Float, alpha: Float, color: Int, additive: Boolean, rotation: Float = 0f) {
        if (alpha <= 0.002f || sw < 1f || sh < 1f) return
        spritePaint.colorFilter = tint(color)
        spritePaint.xfermode = if (additive) add else null
        spritePaint.alpha = (alpha.coerceAtMost(1f) * 255).toInt()
        rect.set(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f)
        if (rotation != 0f) {
            c.save(); c.rotate(rotation, cx, cy)
            c.drawBitmap(bmp, null, rect, spritePaint)
            c.restore()
        } else c.drawBitmap(bmp, null, rect, spritePaint)
    }

    private fun pushed(v: Float, centre: Float, amount: Float) = centre + (v - centre) * amount

    // ------------------------------------------------------------------ the V
    private fun drawEmblem(c: Canvas, t: Float, w: Int, h: Int, push: Float) {
        // Where it forms (centred, a little larger) and where it settles in the lockup.
        val finalS = e / 100f
        val finalX = w / 2f - e / 2f
        val formS = finalS * FORM_SCALE
        val formX = w / 2f - 50f * formS
        val formY = h / 2f - 51f * formS
        val k = T.settle(t)
        val k2 = 1f + T.PUSH_LOCKUP * push
        val s = (formS + (finalS - formS) * k) * k2
        val ox = pushed(formX + (finalX - formX) * k, w / 2f, k2)
        val oy = pushed(formY + (boxY - formY) * k, h / 2f, k2)

        val cool = T.coolFront(t)
        val pour = T.pourFront(t)
        val pourP = T.progress(t, T.POUR_START, T.POUR_END)
        val coolP = T.progress(t, T.COOL_START, T.COOL_END)
        val moltenAmount = pourP * (1f - coolP)
        val tracing = T.traceBlades(t)

        c.save()
        c.translate(ox, oy)
        c.scale(s, s)
        // The light the hot metal throws on the iron, and the pulse as the shape completes.
        sprite(c, glow, 50f, 57f, 260f, 230f, 0.10f * min(1f, tracing * 3f) + 0.34f * moltenAmount + 0.10f * coolP, 0xFFFF7A26.toInt(), true)
        val pulse = T.pulse(t)
        if (pulse > 0f) sprite(c, glow, 50f, 51f, 250f, 250f, 0.30f * pulse, 0xFFFFC878.toInt(), true)

        // Molten fill: between the cooling front (soft) and the pour front.
        if (pourP > 0f && cool < 100f) {
            val y0 = cool - 4.5f
            val y1 = cool + 4.5f
            val y3 = pour + 1.5f
            val y2 = max(pour - 1.5f, y1)
            fillPaint.xfermode = null
            fillPaint.alpha = 255
            fillPaint.shader = masked(moltenShader, floatArrayOf(y0, y1, y2, max(y3, y2 + 0.01f)), floatArrayOf(0f, 1f, 1f, 0f))
            c.drawPath(vPath, fillPaint)
            if (pourP < 1f) { // the bright edge of the pour
                fillPaint.xfermode = add
                fillPaint.alpha = (0.55f * 255).toInt()
                fillPaint.shader = masked(moltenShader, floatArrayOf(pour - 2.5f, pour, pour + 2.5f), floatArrayOf(0f, 1f, 0f))
                c.drawPath(vPath, fillPaint)
            }
        }
        // Gold, above the cooling front.
        if (coolP > 0f) {
            fillPaint.xfermode = null
            fillPaint.alpha = 255
            fillPaint.shader = if (coolP >= 1f) goldShader else masked(goldShader, floatArrayOf(cool - 4.5f, cool + 4.5f), floatArrayOf(1f, 0f))
            c.drawPath(vPath, fillPaint)
        }
        // The glint that crosses the gold with the pulse.
        val g = T.progress(t, T.PULSE_AT - 0.04f, T.PULSE_AT + 0.36f)
        if (g > 0f && g < 1f) {
            val u = -20f + 140f * T.easeInOutSine(g)
            val a = 0.55f * sin(PI.toFloat() * g)
            // Along the diagonal: u = (x + y) / √2, so a point at distance u from the origin is (u/√2, u/√2).
            val d = 0.70710677f
            val col = 0xFFF0C8
            fillPaint.shader = LinearGradient(
                (u - 14f) * d, (u - 14f) * d, (u + 14f) * d, (u + 14f) * d,
                intArrayOf(col, alpha(col, 0.37f * a), alpha(col, a), alpha(col, 0.37f * a), col),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
            fillPaint.xfermode = add
            fillPaint.alpha = 255
            c.drawPath(vPath, fillPaint)
        }
        fillPaint.shader = null
        fillPaint.xfermode = null
        // The molten line, still glowing round the outline until the gold has cooled.
        val outline = 1f - T.progress(t, T.COOL_START, T.COOL_END + 0.1f)
        if (tracing > 0f && outline > 0f) drawTrace(c, t, outline)
        c.restore()
    }

    /** [base] faded by a vertical ramp in the mark's box: [alphas] at box heights [ys]. */
    private fun masked(base: Shader, ys: FloatArray, alphas: FloatArray): Shader {
        val span = ys.last() - ys.first()
        val ramp = LinearGradient(
            0f, ys.first(), 0f, ys.last(),
            IntArray(ys.size) { alpha(0xFFFFFF, alphas[it]) },
            FloatArray(ys.size) { (ys[it] - ys.first()) / span }, Shader.TileMode.CLAMP,
        )
        return ComposeShader(base, ramp, PorterDuff.Mode.DST_IN)
    }

    private fun drawTrace(c: Canvas, t: Float, outline: Float) {
        val progress = floatArrayOf(T.traceBlades(t), T.traceBlades(t), T.traceNotch(t))
        for ((i, pm) in traces.withIndex()) {
            val p = progress[i]
            if (p <= 0f) continue
            val len = pm.length
            val d = p * len
            val hot = 0.16f * len
            // The cooling trail behind, then the hot run near the head.
            stroke(c, pm, 0f, d - hot, 2.2f, 0xC8461A, 0.16f * outline)
            stroke(c, pm, 0f, d - hot, 0.7f, 0xE0662A, 0.55f * outline)
            stroke(c, pm, d - hot, d, 3.4f, 0xFF6A1C, 0.24f * outline)
            stroke(c, pm, d - hot, d, 1.4f, 0xFFA040, 0.65f * outline)
            stroke(c, pm, d - hot, d, 0.5f, 0xFFF0D0, 0.95f * outline)
            if (p < 1f && pm.getPosTan(d, pos, null)) {
                sprite(c, glow, pos[0], pos[1], 14f, 14f, 0.9f, 0xFFFFB060.toInt(), true)
                sprite(c, glow, pos[0], pos[1], 4f, 4f, 1f, 0xFFFFF4DC.toInt(), true)
            }
        }
    }

    private fun stroke(c: Canvas, pm: PathMeasure, from: Float, to: Float, width: Float, rgb: Int, alpha: Float) {
        val a = max(0f, from)
        if (to <= a || alpha <= 0.002f) return
        segment.reset()
        if (!pm.getSegment(a, to, segment, true)) return
        linePaint.strokeWidth = width
        linePaint.color = alpha(rgb, alpha)
        c.drawPath(segment, linePaint)
    }

    // ------------------------------------------------------------------ VYRK and the tagline
    private fun drawWords(c: Canvas, t: Float, w: Int, h: Int, push: Float) {
        val k = 1f + T.PUSH_LOCKUP * push
        val cx = w / 2f
        if (t >= T.WORDMARK_START) {
            val size = wordSize * k
            wordPaint.textSize = size
            wordHighlight.textSize = size
            // The spacing settles from wide to the wordmark's own (0.30 of its height at 0.78 size).
            val track = (0.62f + (0.385f - 0.62f) * T.wordmarkTracking(t)) * size
            var total = track * (WORD.length - 1)
            for (i in WORD.indices) { advances[i] = wordPaint.measureText(WORD, i, i + 1); total += advances[i] }
            val base = pushed(wordBase, h / 2f, k)
            val left = cx - total / 2f
            val cap = 0.70f * size
            // Metal: bright at the top of the letters, darker at the foot.
            wordPaint.shader = LinearGradient(0f, base - cap, 0f, base, 0xFFFBF8F1.toInt(), 0xFFB9B0A2.toInt(), Shader.TileMode.CLAMP)
            val hi = T.wordmarkHighlight(t)
            val highlight = hi > -0.2f && hi < 1.2f
            if (highlight) {
                // A soft band of light slanting across the letters, left to right.
                val bandX = left - 0.6f * wordSize + hi * (total + 1.2f * wordSize)
                val dx = 1f
                val dy = 0.35f
                val norm = kotlin.math.sqrt(dx * dx + dy * dy)
                val sigma = 0.28f * wordSize / norm
                val ux = dx / norm * 2f * sigma
                val uy = dy / norm * 2f * sigma
                val col = 0xFFF2D6
                wordHighlight.shader = LinearGradient(
                    bandX - ux, base - uy, bandX + ux, base + uy,
                    intArrayOf(col, alpha(col, 0.37f * 0.55f), alpha(col, 0.55f), alpha(col, 0.37f * 0.55f), col),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP,
                )
            }
            var x = left
            for (i in WORD.indices) {
                val a = T.letter(t, i)
                if (a > 0f) {
                    val rise = (1f - a) * 0.10f * wordSize * k
                    wordPaint.alpha = (a * 255).toInt()
                    c.drawText(WORD, i, i + 1, x, base + rise, wordPaint)
                    if (highlight) {
                        wordHighlight.alpha = (a * 255).toInt()
                        c.drawText(WORD, i, i + 1, x, base + rise, wordHighlight)
                    }
                }
                x += advances[i] + track
            }
        }
        val a = T.tagline(t)
        if (a > 0f) {
            tagPaint.textSize = tagSize * k
            tagPaint.letterSpacing = 0.30f + (0.16f - 0.30f) * a
            tagPaint.color = alpha(0xD4A65A, 0.92f * a)
            c.drawText(tagline, cx, pushed(tagBase, h / 2f, k) + (1f - a) * 0.25f * tagSize, tagPaint)
        }
    }

    private val advances = FloatArray(WORD.length)

    // ------------------------------------------------------------------ embers and smoke
    private fun drawEmbers(c: Canvas, t: Float, w: Int, h: Int, push: Float, reveal: Float, front: Boolean) {
        val unit = min(w, h) / 1080f
        val k = 1f + (if (front) 0.06f else T.PUSH_BACKGROUND) * push
        for (em in EMBERS) {
            if (em.front != front) continue
            val age = t - em.start
            if (age < 0f) continue
            val y = pushed((em.y - em.rise * age) * h, h / 2f, k)
            val x = pushed(em.x * w + em.sway * unit * sin(2f * PI.toFloat() * em.swayHz * t + em.phase), w / 2f, k)
            val life = min(1f, age / 0.4f) * T.smoothstep((y / h - 0.08f) / 0.25f)
            val flick = 0.72f + 0.28f * sin(2f * PI.toFloat() * em.flicker * t + em.phase * 3f)
            val a = life * flick * (0.35f + 0.65f * reveal)
            if (a <= 0.002f) continue
            if (em.spark) {
                sprite(c, glow, x, y, em.size * 9f * unit, em.size * 9f * unit, 0.42f * a, em.color, true)
                sprite(c, glow, x, y, em.size * 2.2f * unit, em.size * 2.2f * unit, a, 0xFFFFF0D0.toInt(), true)
            } else {
                sprite(c, bokeh, x, y, em.size * unit, em.size * unit, 0.16f * a, em.color, true)
            }
        }
    }

    private fun drawSmoke(c: Canvas, t: Float, w: Int, h: Int, push: Float, reveal: Float, front: Boolean) {
        val big = max(w, h).toFloat()
        val k = 1f + T.PUSH_BACKGROUND * push
        for (sm in SMOKE) {
            if (sm.front != front) continue
            val x = pushed((sm.x + sm.dx * t) * w, w / 2f, k)
            val y = pushed((sm.y + sm.dy * t) * h, h / 2f, k)
            val sw = sm.size * big * (1f + 0.05f * t)
            sprite(c, smokeBmp, x, y, sw, sw * 0.75f, 1.6f * sm.opacity * (0.3f + 0.7f * reveal), sm.color, false, sm.spin * t)
        }
    }

    private class Ember(
        val x: Float, val y: Float, val rise: Float, val sway: Float, val swayHz: Float, val phase: Float,
        val size: Float, val flicker: Float, val spark: Boolean, val front: Boolean, val start: Float, val color: Int,
    )

    private class Smoke(
        val x: Float, val y: Float, val size: Float, val dx: Float, val dy: Float, val spin: Float,
        val opacity: Float, val color: Int, val front: Boolean,
    )

    companion object {
        /** The near-black the opening starts from, matching the splash screen. */
        const val BASE = 0xFF050404.toInt()
        private const val FORM_SCALE = 1.10f
        private const val WORD = "VYRK"

        private fun alpha(rgb: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

        // x, y (fractions of the screen), rise (screen heights a second), sway (px at 1080p), sway
        // rate, phase, size (px at 1080p), flicker rate, spark or out-of-focus, in front, start, colour.
        private val EMBERS = listOf(
            Ember(0.31f, 0.86f, 0.060f, 14f, 0.35f, 0.0f, 2.6f, 5.1f, true, false, -1.0f, 0xFFFFB04A.toInt()),
            Ember(0.44f, 0.97f, 0.075f, 10f, 0.50f, 1.3f, 2.0f, 6.3f, true, false, -0.6f, 0xFFFF9A36.toInt()),
            Ember(0.58f, 0.90f, 0.052f, 18f, 0.28f, 2.1f, 3.0f, 4.2f, true, false, -1.4f, 0xFFFFC266.toInt()),
            Ember(0.69f, 1.02f, 0.080f, 12f, 0.42f, 0.7f, 2.2f, 7.0f, true, false, -0.2f, 0xFFFF8A2E.toInt()),
            Ember(0.23f, 1.05f, 0.068f, 16f, 0.31f, 2.6f, 1.8f, 5.6f, true, false, 0.2f, 0xFFFFA040.toInt()),
            Ember(0.77f, 0.80f, 0.045f, 9f, 0.55f, 1.9f, 1.6f, 6.8f, true, false, -1.8f, 0xFFFFB85A.toInt()),
            Ember(0.50f, 1.04f, 0.090f, 11f, 0.47f, 0.4f, 2.4f, 5.9f, true, false, 0.6f, 0xFFFFD27A.toInt()),
            Ember(0.37f, 1.06f, 0.070f, 13f, 0.38f, 1.7f, 1.7f, 6.6f, true, false, 1.3f, 0xFFFF9A36.toInt()),
            Ember(0.63f, 1.06f, 0.082f, 15f, 0.33f, 2.9f, 2.1f, 4.8f, true, false, 1.9f, 0xFFFFB04A.toInt()),
            Ember(0.16f, 0.74f, 0.030f, 20f, 0.22f, 0.9f, 22f, 2.1f, false, false, -2.0f, 0xFFFF8A2E.toInt()),
            Ember(0.86f, 0.66f, 0.026f, 22f, 0.20f, 2.4f, 28f, 1.7f, false, false, -2.0f, 0xFFFF9A40.toInt()),
            Ember(0.72f, 0.98f, 0.040f, 18f, 0.25f, 1.1f, 18f, 2.4f, false, false, 0.4f, 0xFFFFB050.toInt()),
            Ember(0.09f, 0.92f, 0.050f, 30f, 0.18f, 0.3f, 70f, 1.3f, false, true, -1.0f, 0xFFFF7A24.toInt()),
            Ember(0.93f, 1.10f, 0.058f, 26f, 0.16f, 1.6f, 90f, 1.1f, false, true, 0.5f, 0xFFFF8A2E.toInt()),
            Ember(0.26f, 1.08f, 0.110f, 8f, 0.60f, 0.2f, 2.6f, 8.0f, true, true, 1.1f, 0xFFFFC266.toInt()),
        )

        // Centre x, y (fractions), width (of the larger side), drift x, y (fractions a second),
        // spin (degrees a second), opacity, colour, in front.
        private val SMOKE = listOf(
            Smoke(0.30f, 0.70f, 0.95f, 0.006f, -0.018f, 1.5f, 0.10f, 0xFF8A7C70.toInt(), false),
            Smoke(0.72f, 0.62f, 0.85f, -0.008f, -0.014f, -1.2f, 0.08f, 0xFF857668.toInt(), false),
            Smoke(0.50f, 0.58f, 0.70f, 0.003f, -0.020f, 0.8f, 0.09f, 0xFFB07A52.toInt(), false),
            Smoke(0.55f, 0.96f, 1.10f, -0.010f, -0.024f, -0.6f, 0.05f, 0xFF7A6C62.toInt(), true),
        )
    }
}
