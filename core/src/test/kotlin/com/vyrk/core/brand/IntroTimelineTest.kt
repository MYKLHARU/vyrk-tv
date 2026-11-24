package com.vyrk.core.brand

import com.vyrk.core.brand.IntroTimeline as T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroTimelineTest {
    private val step = 1f / 120f
    private fun times() = generateSequence(0f) { it + step }.takeWhile { it <= T.END + 0.2f }

    @Test
    fun phasesRunInTheirOrderAndFitInThreeAndAHalfSeconds() {
        assertEquals(3.5f, T.END)
        val order = listOf(0f, T.REVEAL_END, T.TRACE_START, T.TRACE_END, T.PULSE_AT, T.SETTLE_END, T.DISSOLVE_START, T.END)
        assertEquals(order, order.sorted())
        assertTrue(T.POUR_START < T.TRACE_END && T.POUR_START > T.TRACE_START)
        assertTrue(T.COOL_START > T.POUR_START && T.COOL_END <= T.PULSE_AT)
        assertTrue(T.WORDMARK_START > T.SETTLE_START && T.TAGLINE_START >= T.WORDMARK_START + 3 * T.WORDMARK_LETTER_STAGGER)
        assertTrue(T.TAGLINE_START + T.TAGLINE_FADE < T.DISSOLVE_START, "the tagline is fully in before the dissolve")
    }

    @Test
    fun everyPhaseMovesOneWayFromZeroToOne() {
        val curves = listOf<(Float) -> Float>(T::reveal, T::traceBlades, T::traceNotch, T::settle, T::push, T::tagline, T::wordmarkTracking) +
            (0..3).map { i -> { t: Float -> T.letter(t, i) } }
        for (f in curves) {
            assertEquals(0f, f(0f), 1e-4f)
            assertEquals(1f, f(T.END), 1e-4f)
            times().zipWithNext().forEach { (a, b) -> assertTrue(f(b) >= f(a) - 1e-5f, "falls between $a and $b") }
        }
    }

    @Test
    fun goldCoolsBehindThePour() {
        times().forEach { t -> assertTrue(T.coolFront(t) <= T.pourFront(t) + 8f, "at $t") }
        assertTrue(T.pourFront(T.POUR_END) >= Emblem.BOTTOM && T.coolFront(T.COOL_END) >= Emblem.BOTTOM + 4.5f)
        assertTrue(T.pourFront(T.POUR_START) <= Emblem.TOP && T.coolFront(T.COOL_START) <= Emblem.TOP - 4.5f)
    }

    @Test
    fun onePulseAtTheMomentTheShapeCompletes() {
        assertEquals(1f, T.pulse(T.PULSE_AT), 1e-4f)
        assertEquals(0f, T.pulse(T.PULSE_AT - T.PULSE_RISE - 0.01f))
        assertEquals(0f, T.pulse(T.PULSE_AT + T.PULSE_DECAY + 0.3f))
        val peaks = times().zipWithNext().zipWithNext().count { (ab, bc) -> T.pulse(ab.second) > T.pulse(ab.first) && T.pulse(ab.second) > T.pulse(bc.second) }
        assertTrue(peaks <= 1)
    }

    @Test
    fun dissolvesToNothingAtTheEnd() {
        assertEquals(1f, T.visibility(T.DISSOLVE_START))
        assertEquals(0f, T.visibility(T.END), 1e-4f)
        assertFalse(T.finished(T.END - 0.01f))
        assertTrue(T.finished(T.END))
    }

    @Test
    fun skippingFadesOutQuicklyFromWhereItWas() {
        val at = 1.2f
        assertEquals(1f, T.visibility(at, skippedAt = at))
        assertEquals(0f, T.visibility(at + T.SKIP_FADE, skippedAt = at), 1e-4f)
        assertTrue(T.visibility(at + T.SKIP_FADE / 2, skippedAt = at) in 0.1f..0.9f)
        assertTrue(T.finished(at + T.SKIP_FADE, skippedAt = at))
        assertFalse(T.finished(at + T.SKIP_FADE / 2, skippedAt = at))
        // Skipped during the dissolve: never brighter than it already was.
        val late = T.DISSOLVE_START + 0.2f
        assertTrue(T.visibility(late + 0.01f, skippedAt = late) <= T.visibility(late))
    }
}
