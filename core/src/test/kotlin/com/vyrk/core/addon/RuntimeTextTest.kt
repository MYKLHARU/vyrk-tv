package com.vyrk.core.addon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeTextTest {
    @Test
    fun readsTheWaysAddonsWriteRuntimes() {
        assertEquals(95, RuntimeText.minutes("95 min"))
        assertEquals(95, RuntimeText.minutes("95min"))
        assertEquals(120, RuntimeText.minutes("120"))
        assertEquals(120, RuntimeText.minutes("2.0h"))
        assertEquals(84, RuntimeText.minutes("1.4h"))
        assertEquals(84, RuntimeText.minutes("1,4 h"))
        assertEquals(130, RuntimeText.minutes("2h 10min"))
        assertEquals(130, RuntimeText.minutes("2 h 10 m"))
        assertNull(RuntimeText.minutes("N/A"))
        assertNull(RuntimeText.minutes(""))
        assertNull(RuntimeText.minutes(null))
        assertNull(RuntimeText.minutes("0 min"))
    }

    @Test
    fun showsOneFormat() {
        assertEquals("1h 35m", RuntimeText.format("95 min"))
        assertEquals("2h", RuntimeText.format("2.0h"))
        assertEquals("1h 24m", RuntimeText.format("1.4h"))
        assertEquals("45m", RuntimeText.format("45 min"))
        assertEquals("2h 10m", RuntimeText.format("2h 10min"))
    }

    @Test
    fun leavesOtherTextAlone() {
        assertEquals("N/A", RuntimeText.format(" N/A "))
        assertEquals("24 min per episode", RuntimeText.format("24 min per episode"))
        assertNull(RuntimeText.format("  "))
        assertNull(RuntimeText.format(null))
    }
}
