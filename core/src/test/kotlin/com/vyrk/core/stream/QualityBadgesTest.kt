package com.vyrk.core.stream

import com.vyrk.core.addon.Stream
import kotlin.test.Test
import kotlin.test.assertEquals

class QualityBadgesTest {
    private fun badges(name: String) = QualityBadges.of(StreamParser.parse(Stream(name = name, url = "https://x.test/v.mkv"))).map { it.label }

    @Test fun picksOneOfEachKind() {
        assertEquals(listOf("4K", "Dolby Vision", "Atmos", "7.1"), badges("Movie 2160p DV HDR10 TrueHD Atmos 7.1 Remux"))
        assertEquals(listOf("HD", "5.1"), badges("Show S01E01 1080p WEB-DL DDP5.1 H264"))
        assertEquals(listOf("4K", "HDR"), badges("Film 2160p HDR10 HEVC"))
        assertEquals(emptyList(), badges("Clip 720p"))
    }
}
