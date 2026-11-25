package com.vyrk.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportCodecTest {
    @Test
    fun roundTripsAndStaysUrlSafe() {
        val text = "VYRK 0.1.66 Android 34 SHIELD ✓\nline two ünïcødé\n"
        val packed = ReportCodec.encode(text)
        assertTrue(packed.all { it.isLetterOrDigit() || it == '-' || it == '_' }, packed)
        assertEquals(text, ReportCodec.decode(packed))
        assertTrue(ReportCodec.link(text).startsWith("https://myklharu.github.io/vyrk-tv/report.html#r="))
    }

    @Test
    fun keepsTheNewestLinesThatFit() {
        val log = (1..400).map { "2026-11-25T03:${it % 60}:00Z [player] candidate $it/3 from org.example.addon${it * 7919 % 1000}" }
        val text = ReportCodec.fit("VYRK test header", log, maxChars = 1200)
        assertTrue(ReportCodec.link(text).length <= 1200)
        assertTrue(text.startsWith("VYRK test header\n"))
        assertTrue(text.trimEnd().endsWith(log.last()))
        assertTrue("earlier lines left out" in text)
        val kept = text.lines().count { it.contains("[player]") }
        // One more line would no longer fit.
        assertTrue(kept in 20 until log.size)
    }

    @Test
    fun aShortLogIsKeptWhole() {
        val text = ReportCodec.fit("h", listOf("a", "b"))
        assertEquals("h\na\nb\n", text)
    }
}
