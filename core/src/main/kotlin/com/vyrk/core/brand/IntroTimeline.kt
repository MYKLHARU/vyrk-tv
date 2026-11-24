package com.vyrk.core.brand

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

/**
 * The opening's timing, in seconds from its first frame. This is the one place to adjust it:
 * the app's renderer (ui/intro/ForgedScene.kt) and the preview (tools/preview_intro.py) both
 * read these numbers.
 *
 *  0.00–0.60  Near darkness; a warm glow reveals an iron surface, embers drift, a little smoke.
 *  0.62–1.28  A line of molten gold runs round the V's outline (both blades at once, then the notch).
 *  1.16–1.50  Molten gold fills the shape from the top down,
 *  1.40–1.80  and cools from the top down into brushed gold.
 *  1.80       One pulse of light as the shape completes.
 *  1.84–2.62  The V settles up into its place in the lockup; a slow camera push begins.
 *  2.02–2.70  VYRK appears beneath it, letter by letter, with a metallic highlight.
 *  2.70–3.04  "Forged for Streaming" appears beneath that; a short hold.
 *  3.16–3.32  The V opens into a window onto the app, from its heart out, leaving a gold rim;
 *             VYRK and the tagline fall away.
 *  3.20–3.70  The camera dives through the V (into its left blade) until the app fills the screen.
 */
object IntroTimeline {
    const val REVEAL_END = 0.60f
    const val TRACE_START = 0.62f
    const val TRACE_END = 1.28f
    const val NOTCH_TRACE_START = 0.92f
    const val POUR_START = 1.16f
    const val POUR_END = 1.50f
    const val COOL_START = 1.40f
    const val COOL_END = 1.80f
    const val PULSE_AT = 1.80f
    const val PULSE_RISE = 0.08f
    const val PULSE_DECAY = 0.42f
    const val SETTLE_START = 1.84f
    const val SETTLE_END = 2.62f
    const val PUSH_START = 1.80f
    const val WORDMARK_START = 2.02f
    const val WORDMARK_LETTER_STAGGER = 0.07f
    const val WORDMARK_LETTER_FADE = 0.42f
    const val WORDMARK_HIGHLIGHT_START = 2.28f
    const val WORDMARK_HIGHLIGHT_END = 3.02f
    const val TAGLINE_START = 2.70f
    const val TAGLINE_FADE = 0.34f
    const val WINDOW_START = 3.16f
    const val WINDOW_OPEN = 0.16f
    const val WORDS_OUT = 0.18f
    const val ZOOM_START = 3.20f
    /** How far the camera goes in: the V ends this many times its size, its blade wider than the screen. */
    const val ZOOM_DEPTH = 40f
    /** A last fade under the dive, so nothing of the opening is left whatever the screen's shape. */
    const val DISSOLVE_START = 3.60f
    const val END = 3.70f

    /** How long a skip takes to fade out (OK, Enter, Back or a tap). */
    const val SKIP_FADE = 0.22f

    /** Camera push over the whole lockup and, less, over the background (parallax). */
    const val PUSH_LOCKUP = 0.035f
    const val PUSH_BACKGROUND = 0.012f

    /** 0 before [start], 1 after [end], linear between. */
    fun progress(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

    fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)
    fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    fun easeInOutSine(x: Float): Float = (-(cos(PI * x) - 1) / 2).toFloat()
    fun smoothstep(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /** How far the iron surface is lit: 0 → 1 over the reveal, still brightening a little after. */
    fun reveal(t: Float): Float = 0.75f * easeOutCubic(progress(t, 0f, REVEAL_END)) + 0.25f * smoothstep(progress(t, REVEAL_END, 1.2f))

    /** How far round its outline the molten line has run, for the blades and for the notch. */
    fun traceBlades(t: Float): Float = easeInOutSine(progress(t, TRACE_START, TRACE_END))
    fun traceNotch(t: Float): Float = easeInOutSine(progress(t, NOTCH_TRACE_START, TRACE_END))

    /** The molten fill's lower edge and the cooled gold's lower edge, in box units (14 → 88, plus margin). */
    fun pourFront(t: Float): Float = 10f + 82f * easeInOutSine(progress(t, POUR_START, POUR_END))
    fun coolFront(t: Float): Float = 6f + 94f * easeInOutSine(progress(t, COOL_START, COOL_END))

    /** The completing pulse: a quick rise, then an exponential fall. 0 → 1 → 0. */
    fun pulse(t: Float): Float = when {
        t < PULSE_AT - PULSE_RISE -> 0f
        t < PULSE_AT -> smoothstep(progress(t, PULSE_AT - PULSE_RISE, PULSE_AT))
        else -> exp(-(t - PULSE_AT) / (PULSE_DECAY / 3f)).let { if (it < 0.01f) 0f else it }
    }

    /** The V's move from where it forms (0) to its place in the lockup (1). */
    fun settle(t: Float): Float = easeInOutCubic(progress(t, SETTLE_START, SETTLE_END))

    /** The camera push, 0 → 1 from [PUSH_START] to [END]. */
    fun push(t: Float): Float = easeInOutSine(progress(t, PUSH_START, END))

    /** Wordmark letter [i] (0-based): 0 → 1. */
    fun letter(t: Float, i: Int): Float {
        val start = WORDMARK_START + i * WORDMARK_LETTER_STAGGER
        return easeOutCubic(progress(t, start, start + WORDMARK_LETTER_FADE))
    }

    /** Letter spacing settling from wide to the wordmark's own, 0 → 1. */
    fun wordmarkTracking(t: Float): Float = easeOutCubic(progress(t, WORDMARK_START, WORDMARK_START + 0.8f))

    /** The highlight's position across the wordmark: −1 before, 0 → 1 across, 2 after. */
    fun wordmarkHighlight(t: Float): Float = when {
        t < WORDMARK_HIGHLIGHT_START -> -1f
        t > WORDMARK_HIGHLIGHT_END -> 2f
        else -> easeInOutSine(progress(t, WORDMARK_HIGHLIGHT_START, WORDMARK_HIGHLIGHT_END))
    }

    fun tagline(t: Float): Float = easeOutCubic(progress(t, TAGLINE_START, TAGLINE_START + TAGLINE_FADE))

    /** How far the V has opened into a window onto the app, from its heart out: 0 → 1. */
    fun window(t: Float): Float = easeOutCubic(progress(t, WINDOW_START, WINDOW_START + WINDOW_OPEN))

    /** VYRK and the tagline, 1 → 0 as the camera goes in. */
    fun wordsLeft(t: Float): Float = 1f - easeOutCubic(progress(t, WINDOW_START, WINDOW_START + WORDS_OUT))

    /** The dive's zoom: 1 → [ZOOM_DEPTH], slow to start and accelerating, like flying in. */
    fun zoom(t: Float): Float = ZOOM_DEPTH.pow(progress(t, ZOOM_START, END).pow(1.8f))

    /** How far the dive has brought the point it heads for to the middle of the screen: 0 → 1. */
    fun zoomCentre(t: Float): Float = smoothstep(progress(t, ZOOM_START, END))

    /**
     * How visible the whole opening is: 1 until the dissolve, then down to 0 at [END]. After a skip
     * at [skippedAt], it fades out over [SKIP_FADE] from wherever it was.
     */
    fun visibility(t: Float, skippedAt: Float = Float.NaN): Float {
        val natural = 1f - easeInOutSine(progress(t, DISSOLVE_START, END))
        if (skippedAt.isNaN()) return natural
        val from = 1f - easeInOutSine(progress(skippedAt, DISSOLVE_START, END))
        return (from * (1f - progress(t, skippedAt, skippedAt + SKIP_FADE))).coerceAtMost(natural)
    }

    /** True once nothing of the opening is left to show. */
    fun finished(t: Float, skippedAt: Float = Float.NaN): Boolean =
        t >= END || (!skippedAt.isNaN() && t >= skippedAt + SKIP_FADE)
}
