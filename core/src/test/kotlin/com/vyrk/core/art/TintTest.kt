package com.vyrk.core.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TintTest {
    private fun hue(c: Int): Float { val o = FloatArray(3); Tint.toHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, o); return o[0] }

    @Test fun mostlyRedArtGivesARedGlow() {
        val px = IntArray(100) { if (it < 70) 0xFFC0201A.toInt() else 0xFF1030A0.toInt() }
        val t = Tint.of(px)!!
        assertTrue(hue(t) < 20f || hue(t) > 340f, "hue ${hue(t)}")
    }

    @Test fun darkOrGreyArtGivesNothing() {
        assertNull(Tint.of(IntArray(100) { 0xFF777777.toInt() }))
        assertNull(Tint.of(IntArray(100) { 0xFF050505.toInt() }))
    }

    @Test fun evensOutBrightness() {
        val o = FloatArray(3)
        val t = Tint.of(IntArray(50) { 0xFF2A6B30.toInt() })!!
        Tint.toHsv((t shr 16) and 0xFF, (t shr 8) and 0xFF, t and 0xFF, o)
        assertEquals(0.72f, o[2], 0.01f)
        assertTrue(o[1] in 0.44f..0.86f)
    }
}
