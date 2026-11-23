package com.vyrk.core.stream

import com.vyrk.core.addon.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityBadgesTest {
    private fun badges(name: String) = QualityBadges.of(StreamParser.parse(Stream(name = name, url = "https://x.test/v.mkv"))).map { it.label }

    @Test fun picksOneOfEachKind() {
        assertEquals(listOf("4K", "Dolby Vision", "Atmos", "7.1"), badges("Movie 2160p DV HDR10 TrueHD Atmos 7.1 Remux"))
        assertEquals(listOf("HD", "5.1"), badges("Show S01E01 1080p WEB-DL DDP5.1 H264"))
        assertEquals(listOf("4K", "HDR"), badges("Film 2160p HDR10 HEVC"))
        assertEquals(emptyList(), badges("Clip 720p"))
    }

    private fun summary(name: String, size: Long? = null) =
        SourceSummary.of(StreamParser.parse(Stream(name = name, url = "https://x.test/v.mkv")).copy(sizeBytes = size))

    @Test fun summaryLeavesResolutionAndImmersiveSoundToTheBadges() {
        val s = summary("Movie 2160p DV HDR10 TrueHD Atmos 7.1 Remux", size = 58_300_000_000)
        assertEquals(listOf("Dolby Vision", "Atmos", "7.1"), s.badges.map { it.label })
        assertTrue(s.uhd)
        assertFalse("Atmos" in s.details)
        assertTrue(s.details.endsWith(SourceSummary.sizeLabel(58_300_000_000)))
        assertFalse(summary("Show 1080p WEB-DL").uhd)
    }

    @Test fun summaryShowsADashWhenThereIsNothingElse() {
        assertEquals("—", summary("Clip").details)
        assertEquals("", SourceSummary.sizeLabel(null))
    }
}
