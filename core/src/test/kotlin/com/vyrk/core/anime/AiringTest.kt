package com.vyrk.core.anime

import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryItemState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiringTest {
    private val now = 1_763_500_000_000L
    private val day = 86_400_000L

    @Test fun queriesCarryIdsAndWindow() {
        val q = AniListAiring.mediaQuery(listOf(52991, 5114))
        assertTrue(q.contains("\"ids\":[52991,5114]"))
        assertTrue(q.contains("idMal_in:\$ids"))
        val r = AniListAiring.recentQuery(listOf(1), 10, 20)
        assertTrue(r.contains("\"from\":10") && r.contains("\"to\":20"))
    }

    @Test fun parsesMediaAndRecentSchedules() {
        val media = """{"data":{"Page":{"media":[
            {"id":154587,"idMal":52991,"status":"RELEASING","nextAiringEpisode":{"episode":8,"airingAt":1763600000}},
            {"id":5114,"idMal":5114,"status":"FINISHED","nextAiringEpisode":null}]}}}"""
        val m = AniListAiring.parseMedia(media)
        assertEquals(AiredEpisode(8, 1_763_600_000), m[0].next)
        assertEquals(null, m[1].next)
        val recent = """{"data":{"Page":{"airingSchedules":[
            {"episode":7,"airingAt":1763000000,"mediaId":154587},{"episode":6,"airingAt":1762400000,"mediaId":154587}]}}}"""
        assertEquals(mapOf(154587 to AiredEpisode(7, 1_763_000_000)), AniListAiring.parseRecent(recent))
    }

    private fun item(id: String, lastWatched: Long? = null, videoId: String? = null, removed: Boolean = false, temp: Boolean = false) =
        LibraryItem(id, "Show", "series", removed = removed, temp = temp, mtimeMs = now - day, state = LibraryItemState(lastWatchedMs = lastWatched, videoId = videoId))

    @Test fun followsLibraryAndRecentAnimeOnly() {
        val ids = Airing.followed(listOf(item("kitsu:1"), item("mal:2", removed = true, temp = true, lastWatched = now - day), item("tt3"),
            item("kitsu:4", removed = true, temp = true, lastWatched = now - 100 * day), item("kitsu:5", removed = true, lastWatched = now - day)), now).map { it.id }
        assertEquals(listOf("kitsu:1", "mal:2"), ids)
    }

    @Test fun newAndUpcoming() {
        val s = AiringStatus("52991", 1, AiredEpisode(7, (now - 2 * day) / 1000), AiredEpisode(8, (now + 5 * day) / 1000))
        assertTrue(Airing.isNew(item("kitsu:1", videoId = "kitsu:1:6"), s, now))
        assertFalse(Airing.isNew(item("kitsu:1", videoId = "kitsu:1:7"), s, now), "already played")
        assertFalse(Airing.isNew(item("kitsu:1"), s.copy(latest = AiredEpisode(7, (now - 9 * day) / 1000)), now), "over a week ago")
        assertTrue(Airing.upcoming(s, now))
        assertFalse(Airing.upcoming(s.copy(next = AiredEpisode(8, (now + 9 * day) / 1000)), now))
        assertEquals("kitsu:1:7", Airing.episodeId("kitsu:1", 7))
    }
}
