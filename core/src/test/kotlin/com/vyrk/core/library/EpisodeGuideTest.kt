package com.vyrk.core.library

import com.vyrk.core.addon.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeGuideTest {
    private val now = 1_750_000_000_000L
    private val day = 24 * 3600 * 1000L
    private fun iso(ms: Long) = LibraryItem.iso(ms)
    private fun ep(s: Int, e: Int, released: Long? = now - 30 * day) = Video("tt1:$s:$e", season = s, episode = e, released = released?.let(::iso))

    // Listed out of order on purpose, with specials first, as some add-ons send them.
    private val videos = listOf(ep(0, 1), ep(2, 1), ep(1, 2), ep(1, 1), ep(2, 2, released = now + day))

    private fun watched(vararg ids: String): WatchedBitField {
        val all = videos.map { it.id }
        return WatchedBitField.fromArray(all.map { it in ids }, all)
    }

    private fun item(videoId: String? = null, offset: Long = 0) = LibraryItem(
        "tt1", "Show", "series", mtimeMs = now, state = LibraryItemState(videoId = videoId, timeOffset = offset, duration = 3_000_000),
    )

    @Test
    fun seasonsPutSpecialsLast() {
        assertEquals(listOf(1, 2, 0), EpisodeGuide.seasons(videos))
        assertEquals(emptyList(), EpisodeGuide.seasons(listOf(Video("x"))))
    }

    @Test
    fun releasedAtReadsIsoDates() {
        assertEquals(now - 30 * day, EpisodeGuide.releasedAt(ep(1, 1)))
        assertNull(EpisodeGuide.releasedAt(ep(1, 1, released = null)))
        assertNull(EpisodeGuide.releasedAt(Video("x", released = "soon")))
    }

    @Test
    fun upNextIsTheEpisodeInProgress() {
        assertEquals("tt1:1:2", EpisodeGuide.upNextId(videos, item("tt1:1:2", offset = 60_000), watched("tt1:1:1"), now))
    }

    @Test
    fun upNextIsTheFirstAiredUnwatchedEpisodeAfterTheLastWatched() {
        assertEquals("tt1:1:2", EpisodeGuide.upNextId(videos, item(), watched("tt1:1:1"), now))
        assertEquals("tt1:2:1", EpisodeGuide.upNextId(videos, item(), watched("tt1:1:1", "tt1:1:2"), now))
        // The next one has not aired yet.
        assertNull(EpisodeGuide.upNextId(videos, item(), watched("tt1:1:1", "tt1:1:2", "tt1:2:1"), now))
        // Nothing watched yet, and specials never count.
        assertNull(EpisodeGuide.upNextId(videos, item(), watched(), now))
        assertNull(EpisodeGuide.upNextId(videos, item(), watched("tt1:0:1"), now))
        assertNull(EpisodeGuide.upNextId(videos, null, null, now))
    }

    @Test
    fun startSeasonPrefersTheLastPlayedEpisode() {
        assertEquals(2, EpisodeGuide.startSeason(videos, item("tt1:2:1"), upNextId = "tt1:1:2"))
        assertEquals(1, EpisodeGuide.startSeason(videos, item(), upNextId = "tt1:1:2"))
        // A last played ID that matches no episode falls back to up next.
        assertEquals(2, EpisodeGuide.startSeason(videos, item("tt1:9:9"), upNextId = "tt1:2:1"))
        assertEquals(1, EpisodeGuide.startSeason(videos, null, null))
        assertEquals(0, EpisodeGuide.startSeason(listOf(ep(0, 1)), null, null))
        assertEquals(1, EpisodeGuide.startSeason(emptyList(), null, null))
    }

    @Test
    fun minutesLeft() {
        assertEquals(40, LibraryRules.minutesLeft(LibraryItemState(timeOffset = 600_000, duration = 3_000_000)))
        assertNull(LibraryRules.minutesLeft(LibraryItemState(timeOffset = 2_970_000, duration = 3_000_000)))
        assertNull(LibraryRules.minutesLeft(LibraryItemState(timeOffset = 600_000, duration = 0)))
        assertNull(LibraryRules.minutesLeft(LibraryItemState(timeOffset = 3_100_000, duration = 3_000_000)))
        assertNull(LibraryRules.minutesLeft(null))
    }
}
