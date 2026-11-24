package com.vyrk.tv.ui.intro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES20 as GL
import android.opengl.GLUtils
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.vyrk.core.brand.Emblem
import com.vyrk.core.brand.IntroTimeline as T
import com.vyrk.tv.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The forged V, drawn with OpenGL ES 2 on the opening's own thread and context ([ForgedIntroView]),
 * so it shares nothing with the app's drawing. Everything moves by the clock in [T]; the textures
 * come from tools/gen_intro.py and the V's shape from [Emblem], which the shaders fill and trace
 * exactly at any size. tools/preview_intro.py renders the same scene off the device: keep the two
 * in step.
 *
 * Passes, back to front: iron (lit, revealed and vignetted in one pass); smoke and embers behind;
 * the light the V throws and its pulse; the V (molten fill, gold, the pour's edge and the glint in
 * one pass, then the molten line in another); at the end, the V's window onto the app (a pass that
 * clears the pieces to transparent, so the app shows through); VYRK and the tagline; embers and
 * smoke in front; and for a skip or the last moment, one pass that fades everything into the app.
 * The camera's dive through the V is a zoom applied to every pass in the vertex shader.
 */
internal class ForgedScene(private val context: Context) {
    private val tagline = context.getString(R.string.tagline)

    // Decoded by [load], uploaded and freed by [initGl].
    private val pending = HashMap<String, Bitmap>()
    private var wordFace: Typeface = Typeface.SERIF
    private var tagFace: Typeface? = null

    /** Draw less (no smoke or out-of-focus embers) on a device that can't keep up. */
    @Volatile var lite = false

    /** Decodes the textures and fonts: on the opening's thread, before [initGl]. */
    fun load() {
        val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        for ((name, id) in listOf(
            "iron" to R.drawable.intro_iron, "gold" to R.drawable.intro_gold, "molten" to R.drawable.intro_molten,
            "smoke" to R.drawable.intro_smoke, "glow" to R.drawable.intro_glow, "bokeh" to R.drawable.intro_bokeh,
        )) pending[name] = BitmapFactory.decodeResource(context.resources, id, opts) ?: error("$name didn't decode")
        // The wordmark as the app sets it (system serif, semi-bold), and the UI font for the tagline.
        wordFace = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SERIF, 600, false) else Typeface.create(Typeface.SERIF, Typeface.BOLD)
        tagFace = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) }.getOrNull()
    }

    /** Frees what [load] decoded if it was never uploaded. */
    fun release() {
        pending.values.forEach { it.recycle() }
        pending.clear()
    }

    // ------------------------------------------------------------------ GL objects
    private var texIron = 0
    private var texGold = 0
    private var texMolten = 0
    private var texSmoke = 0
    private var texGlow = 0
    private var texBokeh = 0
    private var texWord = 0
    private var texTag = 0
    private val programs = ArrayList<Program>()
    private lateinit var bg: Program
    private lateinit var sprite: Program
    private lateinit var fill: Program
    private lateinit var trace: Program
    private lateinit var text: Program
    private lateinit var fade: Program
    private lateinit var window: Program
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** With the opening's context current: compiles the shaders and uploads the textures. */
    fun initGl() {
        bg = program(BG, "uTex", "uReveal", "uBase", "uVig")
        sprite = program(SPRITE, "uTex", "uColor")
        fill = program(FILL, "uGold", "uMolten", "uEdges", "uPpu", "uFronts", "uExtra")
        trace = program(TRACE, "uSegA", "uSegS", "uTrace", "uHeads", "uOutline", "uPpu")
        text = program(TEXT, "uTex", "uMetal", "uBand", "uTop", "uBottom", "uHi")
        fade = program(FADE, "uAlpha")
        window = program(WINDOW, "uEdges", "uWin")
        texIron = upload(pending.getValue("iron"), mip = false)
        texGold = upload(pending.getValue("gold"), mip = true)
        texMolten = upload(pending.getValue("molten"), mip = true)
        texSmoke = upload(pending.getValue("smoke"), mip = true)
        texGlow = upload(pending.getValue("glow"), mip = true)
        texBokeh = upload(pending.getValue("bokeh"), mip = true)
        release()
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_CULL_FACE)
        GL.glEnable(GL.GL_BLEND)
    }

    /** With the opening's context current: frees what [initGl] made. */
    fun releaseGl() {
        GL.glDeleteTextures(8, intArrayOf(texIron, texGold, texMolten, texSmoke, texGlow, texBokeh, texWord, texTag), 0)
        programs.forEach { GL.glDeleteProgram(it.id) }
        programs.clear()
    }

    private fun program(fs: String, vararg uniforms: String) = Program(VERTEX, fs, *uniforms).also { programs += it }

    private fun upload(bmp: Bitmap, mip: Boolean): Int {
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, if (mip) GL.GL_LINEAR_MIPMAP_LINEAR else GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0)
        if (mip) GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        return ids[0]
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

    // The wordmark's letters and the tagline, drawn once per screen size into textures.
    private var wordAtlasSize = 1f
    private val glyphs = Array(WORD.length) { FloatArray(8) }    // u0 v0 u1 v1, then left top right bottom from pen and baseline (px at atlas size)
    private val advances = FloatArray(WORD.length)
    private var tagAtlasSize = 1f
    private var tagW = 0f
    private var tagH = 0f
    private var tagAscent = 0f

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
        buildText()
    }

    private fun buildText() {
        if (texWord != 0) GL.glDeleteTextures(2, intArrayOf(texWord, texTag), 0)
        val pad = 3
        // VYRK at its largest (the end of the push), one glyph after another.
        wordAtlasSize = wordSize * (1f + T.PUSH_LOCKUP)
        val wp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = wordFace; textSize = wordAtlasSize; color = 0xFFFFFFFF.toInt() }
        val bounds = Array(WORD.length) { Rect().also { r -> wp.getTextBounds(WORD, it, it + 1, r) } }
        var aw = 0
        var ah = 0
        for (b in bounds) { aw += b.width() + 2 * pad; ah = max(ah, b.height() + 2 * pad) }
        aw = max(1, aw); ah = max(1, ah)
        val atlas = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        val c = Canvas(atlas)
        var x = 0
        for (i in WORD.indices) {
            val b = bounds[i]
            val gw = b.width() + 2 * pad
            c.drawText(WORD, i, i + 1, (x + pad - b.left).toFloat(), (pad - b.top).toFloat(), wp)
            val g = glyphs[i]
            g[0] = x.toFloat() / aw; g[1] = 0f; g[2] = (x + gw).toFloat() / aw; g[3] = 1f
            g[4] = (b.left - pad).toFloat(); g[5] = (b.top - pad).toFloat(); g[6] = (b.left - pad + gw).toFloat(); g[7] = (b.top - pad + ah).toFloat()
            advances[i] = wp.measureText(WORD, i, i + 1)
            x += gw
        }
        texWord = upload(atlas, mip = false)
        atlas.recycle()
        // The tagline whole, so every language's script is shaped properly, at its final spacing.
        tagAtlasSize = tagSize * (1f + T.PUSH_LOCKUP)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = tagFace ?: Typeface.DEFAULT; textSize = tagAtlasSize; letterSpacing = 0.16f; color = 0xFFFFFFFF.toInt()
            if (Build.VERSION.SDK_INT >= 26) setFontVariationSettings("'wght' 500")
        }
        val fm = tp.fontMetrics
        tagAscent = -fm.ascent + pad
        val tb = Bitmap.createBitmap(max(1, (tp.measureText(tagline) + 2 * pad).toInt() + 1), max(1, (fm.descent - fm.ascent + 2 * pad).toInt() + 1), Bitmap.Config.ARGB_8888)
        Canvas(tb).drawText(tagline, pad.toFloat(), tagAscent, tp)
        tagW = tb.width.toFloat()
        tagH = tb.height.toFloat()
        texTag = upload(tb, mip = false)
        tb.recycle()
    }

    // ------------------------------------------------------------------ frame
    private var w = 1f
    private var h = 1f

    /** Draws the opening at [t] seconds into a [width] × [height] surface, everything faded to [visibility]. */
    fun draw(width: Int, height: Int, t: Float, visibility: Float) {
        if (width <= 0 || height <= 0) return
        w = width.toFloat(); h = height.toFloat()
        layout(width, height)
        GL.glViewport(0, 0, width, height)
        if (visibility <= 0.002f) {
            GL.glClearColor(0f, 0f, 0f, 0f)
            GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            return
        }
        val push = T.push(t)
        val reveal = T.reveal(t)
        val flicker = 1f + 0.03f * sin(2f * PI.toFloat() * 1.7f * t) + 0.02f * sin(2f * PI.toFloat() * 4.3f * t + 1f)
        placeEmblem(t, push)
        // The dive: everything zooms about a point in the V's left blade, which drifts to the middle.
        zoom = T.zoom(t)
        pivotX = ox + DIVE_X * s
        pivotY = oy + DIVE_Y * s
        val c = T.zoomCentre(t)
        toX = pivotX + (w / 2f - pivotX) * c
        toY = pivotY + (h / 2f - pivotY) * c
        drawIron(push, min(1f, reveal * flicker))
        if (!lite) drawSmoke(t, push, reveal, front = false)
        drawEmbers(t, push, reveal, front = false)
        drawEmblem(t)
        drawWords(t, push)
        drawEmbers(t, push, reveal, front = true)
        if (!lite) drawSmoke(t, push, reveal, front = true)
        if (visibility < 0.999f) {
            // Scale everything drawn (colour and alpha) by the visibility, so the app shows through.
            GL.glBlendFunc(GL.GL_ZERO, GL.GL_SRC_ALPHA)
            fade.use()
            GL.glUniform1f(fade.u("uAlpha"), visibility)
            zoom = 1f; pivotX = 0f; pivotY = 0f; toX = 0f; toY = 0f
            rect(0f, 0f, w, h, 0f, 0f, 1f, 1f, fade)
        }
    }

    private fun drawIron(push: Float, alpha: Float) {
        // Cover the screen, centred, a little larger with the push.
        val iw = IRON_W
        val ih = IRON_H
        val sc = max(w / iw, h / ih) * (1f + T.PUSH_BACKGROUND * push)
        val x0 = w / 2f - iw * sc / 2f
        val y0 = h / 2f - ih * sc / 2f
        GL.glDisable(GL.GL_BLEND)
        bg.use()
        bindTex(texIron, bg.u("uTex"), 0)
        GL.glUniform1f(bg.u("uReveal"), alpha)
        GL.glUniform3f(bg.u("uBase"), r(BASE), g(BASE), b(BASE))
        // The edges fall away to black, to the screen's own shape.
        GL.glUniform4f(bg.u("uVig"), 0.5f * w, 0.53f * h, 0.5f * w, min(0.5f * h, 0.7f * w))
        // Larger than the screen, so it still covers it however the dive moves it.
        val m = 1.2f * max(w, h)
        rect(-m, -m, w + m, h + m, (-m - x0) / (iw * sc), (-m - y0) / (ih * sc), (w + m - x0) / (iw * sc), (h + m - y0) / (ih * sc), bg)
        GL.glEnable(GL.GL_BLEND)
    }

    // ------------------------------------------------------------------ sprites
    /** A white sprite texture, tinted [rgb] at [alpha], centred on (cx, cy), optionally turned [rotation] degrees. */
    private fun sprite(tex: Int, cx: Float, cy: Float, sw: Float, sh: Float, alpha: Float, rgb: Int, additive: Boolean, rotation: Float = 0f) {
        val a = alpha.coerceAtMost(1f)
        if (a <= 0.002f || sw < 1f || sh < 1f) return
        GL.glBlendFunc(GL.GL_ONE, if (additive) GL.GL_ONE else GL.GL_ONE_MINUS_SRC_ALPHA)
        sprite.use()
        bindTex(tex, sprite.u("uTex"), 0)
        GL.glUniform4f(sprite.u("uColor"), r(rgb) * a, g(rgb) * a, b(rgb) * a, a)
        if (rotation == 0f) {
            rect(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f, 0f, 0f, 1f, 1f, sprite)
            return
        }
        val rad = rotation * PI.toFloat() / 180f
        val cs = cos(rad)
        val sn = sin(rad)
        val hx = sw / 2f
        val hy = sh / 2f
        val v = FloatArray(16)
        var i = 0
        for ((dx, dy, uv) in listOf(Triple(-hx, -hy, 0), Triple(hx, -hy, 1), Triple(-hx, hy, 2), Triple(hx, hy, 3))) {
            v[i++] = cx + dx * cs - dy * sn
            v[i++] = cy + dx * sn + dy * cs
            v[i++] = (uv and 1).toFloat()
            v[i++] = (uv shr 1).toFloat()
        }
        quad(v, sprite)
    }

    private fun pushed(v: Float, centre: Float, amount: Float) = centre + (v - centre) * amount

    // ------------------------------------------------------------------ the V
    // Each piece's edges as an inward normal and offset: inside is where every edge's distance is positive.
    private val edges = FloatArray(11 * 3).also { out ->
        var k = 0
        for (piece in Emblem.PIECES) {
            val cx = piece.map { it.x }.average().toFloat()
            val cy = piece.map { it.y }.average().toFloat()
            for (i in piece.indices) {
                val a = piece[i]
                val b2 = piece[(i + 1) % piece.size]
                var nx = -(b2.y - a.y)
                var ny = b2.x - a.x
                val l = hypot(nx, ny)
                nx /= l; ny /= l
                if (nx * (cx - a.x) + ny * (cy - a.y) < 0f) { nx = -nx; ny = -ny }
                out[k++] = nx; out[k++] = ny; out[k++] = nx * a.x + ny * a.y
            }
        }
    }

    // The molten line runs round each piece in [Emblem.traceOrder]: four segments per piece (the
    // notch has three; its fourth is never reached).
    private val segA = FloatArray(12 * 4)
    private val segS = FloatArray(12 * 2)
    private val lengths = FloatArray(3)

    init {
        for ((k, piece) in Emblem.PIECES.withIndex()) {
            val pts = Emblem.traceOrder(piece)
            var s = 0f
            for (j in 0 until 4) {
                val i = k * 4 + j
                if (j < pts.size) {
                    val a = pts[j]
                    val b2 = pts[(j + 1) % pts.size]
                    val len = hypot(b2.x - a.x, b2.y - a.y)
                    segA[i * 4] = a.x; segA[i * 4 + 1] = a.y; segA[i * 4 + 2] = b2.x; segA[i * 4 + 3] = b2.y
                    segS[i * 2] = s; segS[i * 2 + 1] = s + len
                    s += len
                } else {
                    segS[i * 2] = 9999f; segS[i * 2 + 1] = 9999f
                }
            }
            lengths[k] = s
        }
    }

    private val traceU = FloatArray(9)
    private val headsU = FloatArray(9)

    // The V's box on screen before the dive: its top-left corner and pixels per box unit.
    private var ox = 0f
    private var oy = 0f
    private var s = 1f

    private fun placeEmblem(t: Float, push: Float) {
        // Where it forms (centred, a little larger) and where it settles in the lockup.
        val finalS = e / 100f
        val finalX = w / 2f - e / 2f
        val formS = finalS * FORM_SCALE
        val formX = w / 2f - 50f * formS
        val formY = h / 2f - 51f * formS
        val k = T.settle(t)
        val k2 = 1f + T.PUSH_LOCKUP * push
        s = (formS + (finalS - formS) * k) * k2
        ox = pushed(formX + (finalX - formX) * k, w / 2f, k2)
        oy = pushed(formY + (boxY - formY) * k, h / 2f, k2)
    }

    private fun drawEmblem(t: Float) {
        val pourP = T.progress(t, T.POUR_START, T.POUR_END)
        val coolP = T.progress(t, T.COOL_START, T.COOL_END)
        val tracing = T.traceBlades(t)
        // The light the hot metal throws on the iron, and the pulse as the shape completes.
        val open = T.window(t)
        sprite(texGlow, ox + 50f * s, oy + 57f * s, 210f * s, 185f * s, (0.12f * min(1f, tracing * 3f) + 0.40f * pourP * (1f - coolP) + 0.12f * coolP) * (1f - 0.6f * open), 0xFF7A26, true)
        val pulse = T.pulse(t)
        if (pulse > 0f) sprite(texGlow, ox + 50f * s, oy + 51f * s, 250f * s, 250f * s, 0.30f * pulse, 0xFFC878, true)

        // The V's quad in box units, with room round it for the molten line's glow.
        val m = 10f
        val x0 = ox - m * s
        val y0 = oy - m * s
        val x1 = ox + (100f + m) * s
        val y1 = oy + (100f + m) * s
        if (pourP > 0f) {
            GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA)
            fill.use()
            bindTex(texGold, fill.u("uGold"), 0)
            bindTex(texMolten, fill.u("uMolten"), 1)
            GL.glUniform3fv(fill.u("uEdges"), 11, edges, 0)
            GL.glUniform1f(fill.u("uPpu"), s * zoom)
            val cool = T.coolFront(t)
            GL.glUniform4f(fill.u("uFronts"), cool, T.pourFront(t), if (coolP > 0f) 1f else 0f, if (cool < 100f) 1f else 0f)
            val g = T.progress(t, T.PULSE_AT - 0.04f, T.PULSE_AT + 0.36f)
            val glint = if (g > 0f && g < 1f) 0.55f * sin(PI.toFloat() * g) else 0f
            GL.glUniform4f(fill.u("uExtra"), if (pourP < 1f) 0.55f else 0f, -20f + 140f * T.easeInOutSine(g), glint, 0f)
            rect(x0, y0, x1, y1, -m, -m, 100f + m, 100f + m, fill)
        }
        // The molten line, still glowing round the outline until the gold has cooled.
        val outline = 1f - T.progress(t, T.COOL_START, T.COOL_END + 0.1f)
        if (tracing > 0f && outline > 0f) {
            val progress = floatArrayOf(T.traceBlades(t), T.traceBlades(t), T.traceNotch(t))
            for (i in 0 until 3) {
                val p = progress[i]
                val d = p * lengths[i]
                traceU[i * 3] = if (p > 0f) d else -1f
                traceU[i * 3 + 1] = 0.16f * lengths[i]
                val head = p > 0f && p < 1f
                headsU[i * 3 + 2] = if (head) 1f else 0f
                if (head) headPoint(i, d, headsU, i * 3)
            }
            GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE)
            trace.use()
            GL.glUniform4fv(trace.u("uSegA"), 12, segA, 0)
            GL.glUniform2fv(trace.u("uSegS"), 12, segS, 0)
            GL.glUniform3fv(trace.u("uTrace"), 3, traceU, 0)
            GL.glUniform3fv(trace.u("uHeads"), 3, headsU, 0)
            GL.glUniform1f(trace.u("uOutline"), outline)
            GL.glUniform1f(trace.u("uPpu"), s * zoom)
            rect(x0, y0, x1, y1, -m, -m, 100f + m, 100f + m, trace)
        }
        // The window: the pieces clear to transparent from their hearts out, so the app shows
        // through; a gold rim stays until the dive is under way.
        if (open > 0f) {
            val rim = 1.1f * (1f - ((zoom - 1.5f) / 4f).coerceIn(0f, 1f))
            GL.glBlendFunc(GL.GL_ZERO, GL.GL_ONE_MINUS_SRC_ALPHA)
            window.use()
            GL.glUniform3fv(window.u("uEdges"), 11, edges, 0)
            GL.glUniform3f(window.u("uWin"), s * zoom, rim, rim + (1f - open) * 10f)
            rect(x0, y0, x1, y1, -m, -m, 100f + m, 100f + m, window)
        }
    }

    /** Writes where the molten line's head is, [d] along piece [i]'s outline, into [out] at [at]. */
    private fun headPoint(i: Int, d: Float, out: FloatArray, at: Int) {
        for (j in 0 until 4) {
            val k = i * 4 + j
            val s0 = segS[k * 2]
            val s1 = segS[k * 2 + 1]
            if (d <= s1 || j == 3 || segS[(k + 1) * 2] > 9000f) {
                val f = ((d - s0) / max(1e-3f, s1 - s0)).coerceIn(0f, 1f)
                out[at] = segA[k * 4] + (segA[k * 4 + 2] - segA[k * 4]) * f
                out[at + 1] = segA[k * 4 + 1] + (segA[k * 4 + 3] - segA[k * 4 + 1]) * f
                return
            }
        }
    }

    // ------------------------------------------------------------------ VYRK and the tagline
    private fun drawWords(t: Float, push: Float) {
        val k = 1f + T.PUSH_LOCKUP * push
        val cx = w / 2f
        val left = T.wordsLeft(t)
        if (left <= 0f) return
        GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA)
        if (t >= T.WORDMARK_START) {
            val size = wordSize * k
            val scale = size / wordAtlasSize
            // The spacing settles from wide to the wordmark's own (0.30 of its height at 0.78 size).
            val track = (0.62f + (0.385f - 0.62f) * T.wordmarkTracking(t)) * size
            var total = track * (WORD.length - 1)
            for (i in WORD.indices) total += advances[i] * scale
            val base = pushed(wordBase, h / 2f, k)
            val start = cx - total / 2f
            val cap = 0.70f * size
            val hi = T.wordmarkHighlight(t)
            text.use()
            bindTex(texWord, text.u("uTex"), 0)
            // Metal: bright at the top of the letters, darker at the foot, and a soft band of
            // light slanting across them, left to right.
            GL.glUniform3f(text.u("uTop"), 0xFB / 255f, 0xF8 / 255f, 0xF1 / 255f)
            GL.glUniform3f(text.u("uBottom"), 0xB9 / 255f, 0xB0 / 255f, 0xA2 / 255f)
            GL.glUniform3f(text.u("uHi"), 1f, 0xF2 / 255f, 0xD6 / 255f)
            GL.glUniform4f(text.u("uBand"), start - 0.6f * wordSize + hi * (total + 1.2f * wordSize), base, 0.35f, 0.28f * wordSize)
            var x = start
            for (i in WORD.indices) {
                val a = T.letter(t, i)
                if (a > 0f) {
                    val b = base + (1f - a) * 0.10f * wordSize * k
                    GL.glUniform4f(text.u("uMetal"), b - cap, b, a * left, if (hi > -0.2f && hi < 1.2f) 1f else 0f)
                    val g = glyphs[i]
                    rect(x + g[4] * scale, b + g[5] * scale, x + g[6] * scale, b + g[7] * scale, g[0], g[1], g[2], g[3], text)
                }
                x += advances[i] * scale + track
            }
        }
        val a = T.tagline(t)
        if (a > 0f) {
            val scale = tagSize * k / tagAtlasSize
            val base = pushed(tagBase, h / 2f, k) + (1f - a) * 0.25f * tagSize
            text.use()
            bindTex(texTag, text.u("uTex"), 0)
            GL.glUniform3f(text.u("uTop"), r(GOLD), g(GOLD), b(GOLD))
            GL.glUniform3f(text.u("uBottom"), r(GOLD), g(GOLD), b(GOLD))
            GL.glUniform4f(text.u("uMetal"), base - 1f, base, 0.92f * a * left, 0f)
            GL.glUniform4f(text.u("uBand"), 0f, 0f, 0f, 1f)
            val tw = tagW * scale
            val top = base - tagAscent * scale
            rect(cx - tw / 2f, top, cx + tw / 2f, top + tagH * scale, 0f, 0f, 1f, 1f, text)
        }
    }

    // ------------------------------------------------------------------ embers and smoke
    private fun drawEmbers(t: Float, push: Float, reveal: Float, front: Boolean) {
        val unit = min(w, h) / 1080f
        val k = 1f + (if (front) 0.06f else T.PUSH_BACKGROUND) * push
        for (em in EMBERS) {
            if (em.front != front || (lite && !em.spark)) continue
            val age = t - em.start
            if (age < 0f) continue
            val y = pushed((em.y - em.rise * age) * h, h / 2f, k)
            val x = pushed(em.x * w + em.sway * unit * sin(2f * PI.toFloat() * em.swayHz * t + em.phase), w / 2f, k)
            val life = min(1f, age / 0.4f) * T.smoothstep((y / h - 0.08f) / 0.25f)
            val flick = 0.72f + 0.28f * sin(2f * PI.toFloat() * em.flicker * t + em.phase * 3f)
            val a = life * flick * (0.35f + 0.65f * reveal)
            if (a <= 0.002f) continue
            if (em.spark) {
                sprite(texGlow, x, y, em.size * 9f * unit, em.size * 9f * unit, 0.42f * a, em.color, true)
                sprite(texGlow, x, y, em.size * 2.2f * unit, em.size * 2.2f * unit, a, 0xFFF0D0, true)
            } else {
                sprite(texBokeh, x, y, em.size * unit, em.size * unit, 0.16f * a, em.color, true)
            }
        }
    }

    private fun drawSmoke(t: Float, push: Float, reveal: Float, front: Boolean) {
        val big = max(w, h)
        val k = 1f + T.PUSH_BACKGROUND * push
        for (sm in SMOKE) {
            if (sm.front != front) continue
            val x = pushed((sm.x + sm.dx * t) * w, w / 2f, k)
            val y = pushed((sm.y + sm.dy * t) * h, h / 2f, k)
            val sw = sm.size * big * (1f + 0.05f * t)
            sprite(texSmoke, x, y, sw, sw * 0.5f, sm.opacity * (0.3f + 0.7f * reveal), sm.color, false, sm.spin * t)
        }
    }

    // ------------------------------------------------------------------ GL plumbing
    private fun bindTex(tex: Int, uniform: Int, unit: Int) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glUniform1i(uniform, unit)
    }

    /** An axis-aligned quad in pixels, with texture (or box) coordinates u0,v0 → u1,v1. */
    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, p: Program) {
        val v = rectBuf
        v[0] = x0; v[1] = y0; v[2] = u0; v[3] = v0
        v[4] = x1; v[5] = y0; v[6] = u1; v[7] = v0
        v[8] = x0; v[9] = y1; v[10] = u0; v[11] = v1
        v[12] = x1; v[13] = y1; v[14] = u1; v[15] = v1
        quad(v, p)
    }

    private val rectBuf = FloatArray(16)

    // The dive's zoom, applied to every quad by the vertex shader (identity until the end).
    private var zoom = 1f
    private var pivotX = 0f
    private var pivotY = 0f
    private var toX = 0f
    private var toY = 0f

    private fun quad(v: FloatArray, p: Program) {
        quad.clear(); quad.put(v); quad.position(0)
        GL.glUniform2f(p.u("uRes"), w, h)
        GL.glUniform4f(p.u("uZoom"), pivotX, pivotY, zoom, 0f)
        GL.glUniform2f(p.u("uZoomTo"), toX, toY)
        GL.glVertexAttribPointer(p.aPos, 2, GL.GL_FLOAT, false, 16, quad)
        GL.glEnableVertexAttribArray(p.aPos)
        if (p.aUv >= 0) {
            quad.position(2)
            GL.glVertexAttribPointer(p.aUv, 2, GL.GL_FLOAT, false, 16, quad)
            GL.glEnableVertexAttribArray(p.aUv)
        }
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    private class Program(vs: String, fs: String, vararg uniforms: String) {
        val id: Int = GL.glCreateProgram()
        val aPos: Int
        val aUv: Int
        private val locations = HashMap<String, Int>()

        init {
            val v = shader(GL.GL_VERTEX_SHADER, vs)
            val f = shader(GL.GL_FRAGMENT_SHADER, fs)
            GL.glAttachShader(id, v)
            GL.glAttachShader(id, f)
            GL.glLinkProgram(id)
            GL.glDeleteShader(v)
            GL.glDeleteShader(f)
            val ok = IntArray(1)
            GL.glGetProgramiv(id, GL.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "intro shader link: ${GL.glGetProgramInfoLog(id)}" }
            aPos = GL.glGetAttribLocation(id, "aPos")
            aUv = GL.glGetAttribLocation(id, "aUv")
            for (name in uniforms.toList() + listOf("uRes", "uZoom", "uZoomTo")) locations[name] = GL.glGetUniformLocation(id, name)
        }

        fun use() = GL.glUseProgram(id)
        fun u(name: String): Int = locations[name] ?: -1

        private fun shader(type: Int, src: String): Int {
            val s = GL.glCreateShader(type)
            GL.glShaderSource(s, src)
            GL.glCompileShader(s)
            val ok = IntArray(1)
            GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "intro shader compile: ${GL.glGetShaderInfoLog(s)}" }
            return s
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
        /** The near-black the opening starts from, as the splash screen and launch window (res/values/themes.xml). */
        const val BASE = 0xFF050404.toInt()
        private const val GOLD = 0xD4A65A
        private const val FORM_SCALE = 1.10f
        /** Where the dive heads: deep in the left blade, the widest part of the V (box units). */
        private const val DIVE_X = 28.5f
        private const val DIVE_Y = 32f
        private const val WORD = "VYRK"
        private const val IRON_W = 1920f
        private const val IRON_H = 1080f

        private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
        private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
        private fun b(c: Int) = (c and 0xFF) / 255f

        // x, y (fractions of the screen), rise (screen heights a second), sway (px at 1080p), sway
        // rate, phase, size (px at 1080p), flicker rate, spark or out-of-focus, in front, start, colour.
        private val EMBERS = listOf(
            Ember(0.31f, 0.86f, 0.060f, 14f, 0.35f, 0.0f, 2.6f, 5.1f, true, false, -1.0f, 0xFFB04A),
            Ember(0.44f, 0.97f, 0.075f, 10f, 0.50f, 1.3f, 2.0f, 6.3f, true, false, -0.6f, 0xFF9A36),
            Ember(0.58f, 0.90f, 0.052f, 18f, 0.28f, 2.1f, 3.0f, 4.2f, true, false, -1.4f, 0xFFC266),
            Ember(0.69f, 1.02f, 0.080f, 12f, 0.42f, 0.7f, 2.2f, 7.0f, true, false, -0.2f, 0xFF8A2E),
            Ember(0.23f, 1.05f, 0.068f, 16f, 0.31f, 2.6f, 1.8f, 5.6f, true, false, 0.2f, 0xFFA040),
            Ember(0.77f, 0.80f, 0.045f, 9f, 0.55f, 1.9f, 1.6f, 6.8f, true, false, -1.8f, 0xFFB85A),
            Ember(0.50f, 1.04f, 0.090f, 11f, 0.47f, 0.4f, 2.4f, 5.9f, true, false, 0.6f, 0xFFD27A),
            Ember(0.37f, 1.06f, 0.070f, 13f, 0.38f, 1.7f, 1.7f, 6.6f, true, false, 1.3f, 0xFF9A36),
            Ember(0.63f, 1.06f, 0.082f, 15f, 0.33f, 2.9f, 2.1f, 4.8f, true, false, 1.9f, 0xFFB04A),
            Ember(0.16f, 0.74f, 0.030f, 20f, 0.22f, 0.9f, 22f, 2.1f, false, false, -2.0f, 0xFF8A2E),
            Ember(0.86f, 0.66f, 0.026f, 22f, 0.20f, 2.4f, 28f, 1.7f, false, false, -2.0f, 0xFF9A40),
            Ember(0.72f, 0.98f, 0.040f, 18f, 0.25f, 1.1f, 18f, 2.4f, false, false, 0.4f, 0xFFB050),
            Ember(0.09f, 0.92f, 0.050f, 30f, 0.18f, 0.3f, 70f, 1.3f, false, true, -1.0f, 0xFF7A24),
            Ember(0.93f, 1.10f, 0.058f, 26f, 0.16f, 1.6f, 90f, 1.1f, false, true, 0.5f, 0xFF8A2E),
            Ember(0.26f, 1.08f, 0.110f, 8f, 0.60f, 0.2f, 2.6f, 8.0f, true, true, 1.1f, 0xFFC266),
        )

        // Centre x, y (fractions), width (of the larger side; the wisp is half as tall), drift x, y
        // (fractions a second), spin (degrees a second), opacity, colour, in front.
        private val SMOKE = listOf(
            Smoke(0.32f, 0.68f, 0.62f, 0.006f, -0.018f, 1.5f, 0.15f, 0x8A7C70, false),
            Smoke(0.68f, 0.60f, 0.56f, -0.008f, -0.014f, -1.2f, 0.13f, 0xB07A52, false),
            Smoke(0.52f, 0.95f, 0.72f, -0.010f, -0.024f, -0.6f, 0.08f, 0x7A6C62, true),
        )

        // ---------------------------------------------------------------- shaders
        // Positions in pixels from the top left; aUv is a texture coordinate or, for the V, box
        // units. The dive zooms by uZoom.z about uZoom.xy and draws that point at uZoomTo. vPx is
        // where a fragment is on screen, vLocal where it was before the dive.
        private const val VERTEX = """
attribute vec2 aPos;
attribute vec2 aUv;
uniform vec2 uRes;
uniform vec4 uZoom;
uniform vec2 uZoomTo;
varying vec2 vUv;
varying vec2 vPx;
varying vec2 vLocal;
void main() {
  vec2 pos = uZoomTo + (aPos - uZoom.xy) * uZoom.z;
  vUv = aUv;
  vPx = pos;
  vLocal = aPos;
  gl_Position = vec4(pos.x / uRes.x * 2.0 - 1.0, 1.0 - pos.y / uRes.y * 2.0, 0.0, 1.0);
}"""

        // The iron over the base colour, then the vignette: clear to 0.45 of the radius, 0.62 at
        // 0.85, 0.97 black from 1.25.
        private const val BG = """
precision mediump float;
varying vec2 vUv;
varying vec2 vPx;
uniform sampler2D uTex;
uniform float uReveal;
uniform vec3 uBase;
uniform vec4 uVig;
void main() {
  vec3 c = mix(uBase, texture2D(uTex, vUv).rgb, uReveal);
  float r = length((vPx - uVig.xy) / uVig.zw);
  float a = r < 0.85 ? max(0.0, (r - 0.45) / 0.40) * 0.62 : min(0.97, 0.62 + (r - 0.85) / 0.40 * 0.35);
  gl_FragColor = vec4(mix(c, uBase, a), 1.0);
}"""

        private const val SPRITE = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec4 uColor;
void main() { gl_FragColor = uColor * texture2D(uTex, vUv).a; }"""

        private const val HIGHP = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

        // The V's fill, in box units. Each piece is convex, so inside is a positive distance from
        // all its edges; the edge is antialiased over one pixel.
        private const val FILL = HIGHP + """
varying vec2 vUv;
uniform sampler2D uGold;
uniform sampler2D uMolten;
uniform vec3 uEdges[11];
uniform float uPpu;
uniform vec4 uFronts;   // cooling front, pour front, gold on, molten on
uniform vec4 uExtra;    // the pour's bright edge, glint centre, glint strength
float edge(vec3 n, vec2 p) { return dot(n.xy, p) - n.z; }
void main() {
  vec2 p = vUv;
  float left = min(min(edge(uEdges[0], p), edge(uEdges[1], p)), min(edge(uEdges[2], p), edge(uEdges[3], p)));
  float right = min(min(edge(uEdges[4], p), edge(uEdges[5], p)), min(edge(uEdges[6], p), edge(uEdges[7], p)));
  float notch = min(min(edge(uEdges[8], p), edge(uEdges[9], p)), edge(uEdges[10], p));
  float cover = clamp(max(max(left, right), notch) * uPpu + 0.5, 0.0, 1.0);
  if (cover <= 0.0) discard;
  vec2 uv = p / 100.0;
  vec4 gold = texture2D(uGold, uv);
  vec4 molten = texture2D(uMolten, uv);
  float cool = uFronts.x;
  float pour = uFronts.y;
  vec4 m = molten * (clamp((p.y - (cool - 4.5)) / 9.0, 0.0, 1.0) * clamp((pour + 1.5 - p.y) / 3.0, 0.0, 1.0) * uFronts.w);
  vec4 g = gold * (clamp((cool + 4.5 - p.y) / 9.0, 0.0, 1.0) * uFronts.z);
  vec4 c = g + (1.0 - g.a) * m;
  c.rgb += molten.rgb * (clamp(1.0 - abs(p.y - pour) / 2.5, 0.0, 1.0) * uExtra.x);
  float u = ((p.x + p.y) * 0.70710677 - uExtra.y) / 7.0;
  c.rgb += vec3(1.0, 0.941, 0.784) * (exp(-u * u) * uExtra.z);
  gl_FragColor = min(c, vec4(1.0)) * cover;
}"""

        // The molten line: distance to the part of each outline run so far, hottest near its head.
        private const val TRACE = HIGHP + """
varying vec2 vUv;
uniform vec4 uSegA[12];
uniform vec2 uSegS[12];
uniform vec3 uTrace[3];   // distance run so far (or -1), hot length
uniform vec3 uHeads[3];   // head x, y, shown
uniform float uOutline;
uniform float uPpu;
float stroke(float dist, float width) { return clamp((width * 0.5 - dist) * uPpu + 0.5, 0.0, 1.0); }
float glow(float r) { float x = clamp(1.0 - r, 0.0, 1.0); return pow(x, 2.4) * (0.7 + 0.3 * exp(-12.0 * r * r)); }
void main() {
  vec2 p = vUv;
  vec3 col = vec3(0.0);
  for (int k = 0; k < 3; k++) {
    float run = uTrace[k].x;
    if (run > 0.0) {
      float best = 1000.0;
      float age = 0.0;
      for (int j = 0; j < 4; j++) {
        vec4 s = uSegA[k * 4 + j];
        vec2 r = uSegS[k * 4 + j];
        if (r.x < run) {
          vec2 ab = s.zw - s.xy;
          float len = r.y - r.x;
          float tt = clamp(dot(p - s.xy, ab) / max(dot(ab, ab), 0.001), 0.0, min(1.0, (run - r.x) / max(len, 0.001)));
          float dist = length(p - (s.xy + ab * tt));
          if (dist < best) { best = dist; age = run - (r.x + tt * len); }
        }
      }
      float heat = clamp(1.0 - age / uTrace[k].y, 0.0, 1.0);
      vec3 cooled = vec3(0.784, 0.275, 0.102) * (0.16 * stroke(best, 2.2)) + vec3(0.878, 0.400, 0.165) * (0.55 * stroke(best, 0.7));
      vec3 hot = vec3(1.0, 0.416, 0.110) * (0.24 * stroke(best, 3.4)) + vec3(1.0, 0.627, 0.251) * (0.65 * stroke(best, 1.4))
               + vec3(1.0, 0.941, 0.816) * (0.95 * stroke(best, 0.5));
      col += mix(cooled, hot, heat);
    }
    vec3 h = uHeads[k];
    if (h.z > 0.0) {
      float rr = length(p - h.xy);
      col += vec3(1.0, 0.690, 0.376) * (0.9 * glow(rr / 7.0)) + vec3(1.0, 0.957, 0.863) * glow(rr / 2.0);
    }
  }
  gl_FragColor = vec4(min(col * uOutline, vec3(1.0)), 0.0);
}"""

        // Text: white glyphs coloured top to bottom, with a slanting band of light across them.
        private const val TEXT = """
precision mediump float;
varying vec2 vUv;
varying vec2 vLocal;
uniform sampler2D uTex;
uniform vec4 uMetal;   // cap top y, baseline y, opacity, highlight on
uniform vec4 uBand;    // band centre x, its y, slant, width
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec3 uHi;
void main() {
  float a = texture2D(uTex, vUv).a * uMetal.z;
  vec3 c = mix(uTop, uBottom, clamp((vLocal.y - uMetal.x) / max(uMetal.y - uMetal.x, 1.0), 0.0, 1.0));
  float q = ((vLocal.x - uBand.x) + (vLocal.y - uBand.y) * uBand.z) / uBand.w;
  c += uHi * (0.55 * exp(-q * q) * uMetal.w);
  gl_FragColor = vec4(min(c, vec3(1.0)), 1.0) * a;
}"""

        // The V's window: clears what's drawn inside the pieces deeper than uWin.z (and never within
        // the rim, uWin.y) to transparent, with glBlendFunc(ZERO, ONE_MINUS_SRC_ALPHA).
        private const val WINDOW = HIGHP + """
varying vec2 vUv;
uniform vec3 uEdges[11];
uniform vec3 uWin;   // pixels per box unit, rim width, depth the opening has reached out to
float edge(vec3 n, vec2 p) { return dot(n.xy, p) - n.z; }
void main() {
  vec2 p = vUv;
  float left = min(min(edge(uEdges[0], p), edge(uEdges[1], p)), min(edge(uEdges[2], p), edge(uEdges[3], p)));
  float right = min(min(edge(uEdges[4], p), edge(uEdges[5], p)), min(edge(uEdges[6], p), edge(uEdges[7], p)));
  float notch = min(min(edge(uEdges[8], p), edge(uEdges[9], p)), edge(uEdges[10], p));
  float hole = clamp((max(max(left, right), notch) - max(uWin.y, uWin.z)) * uWin.x + 0.5, 0.0, 1.0);
  if (hole <= 0.0) discard;
  gl_FragColor = vec4(0.0, 0.0, 0.0, hole);
}"""

        // Multiplies what's drawn (colour and alpha) by uAlpha, with glBlendFunc(ZERO, SRC_ALPHA).
        private const val FADE = """
precision mediump float;
uniform float uAlpha;
void main() { gl_FragColor = vec4(0.0, 0.0, 0.0, uAlpha); }"""
    }
}
