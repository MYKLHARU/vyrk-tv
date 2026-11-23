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

    @Test fun sourceFiltersOfferResolutionsThenLanguages() {
        fun ranked(name: String, langs: List<String> = emptyList()) =
            Ranked(Candidate(Stream(name = name, url = "https://x.test/$name.mkv"), "a", "A", 0, 0),
                StreamParser.parse(Stream(name = name, url = "https://x.test/v.mkv")).copy(languages = langs), 0, emptyList(), null)
        val uhd = ranked("2160p", listOf("en", "fr"))
        val fhd = ranked("1080p", listOf("fr", "de"))
        val sd = ranked("480p")
        assertEquals(listOf("all", "4K", "1080p", "720p", "480p", "en", "fr", "de"), SourceFilters.of(listOf(uhd, fhd, sd)))
        assertEquals(listOf(uhd), listOf(uhd, fhd, sd).filter { SourceFilters.matches(it, "4K") })
        assertEquals(listOf(sd), listOf(uhd, fhd, sd).filter { SourceFilters.matches(it, "480p") })
        assertEquals(listOf(uhd, fhd), listOf(uhd, fhd, sd).filter { SourceFilters.matches(it, "fr") })
        assertEquals(3, listOf(uhd, fhd, sd).count { SourceFilters.matches(it, SourceFilters.ALL) })
    }
}
