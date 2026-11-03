package com.vyrk.core.library

import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.ExtraProp
import com.vyrk.core.addon.Manifest
import com.vyrk.core.addon.ManifestCatalog
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewEpisodesTest {
    private val day = 24 * 3600 * 1000L
    private val now = 1_750_000_000_000L
    private fun series(id: String = "tt1", lastWatched: Long? = now - 10 * day, noNotif: Boolean = false, removed: Boolean = false, temp: Boolean = false) =
        LibraryItem(id, "S", "series", removed = removed, temp = temp, mtimeMs = now, state = LibraryItemState(lastWatchedMs = lastWatched, noNotif = noNotif))

    @Test
    fun eligibility() {
        assertTrue(series().shouldPullNotifications)
        assertFalse(series(noNotif = true).shouldPullNotifications)
        assertFalse(series(removed = true).shouldPullNotifications)
        assertFalse(series(temp = true).shouldPullNotifications)
        assertFalse(series().copy(type = "movie").shouldPullNotifications)
        assertFalse(series().copy(type = "tv").shouldPullNotifications)
        assertFalse(series().copy(behaviorHints = LibraryBehaviorHints(defaultVideoId = "x")).shouldPullNotifications)
    }

    @Test
    fun retainRules() {
        assertEquals(now - day, NewEpisodes.retainedRelease(now - 2 * day, now - day, now))
        assertNull(NewEpisodes.retainedRelease(now - day, now - 2 * day, now)) // released before last watched
        assertNull(NewEpisodes.retainedRelease(now - day, now + day, now)) // future
        assertEquals(now + day, NewEpisodes.retainedRelease(null, now + day, now)) // never watched: stremio-core keeps it
        assertNull(NewEpisodes.retainedRelease(now, null, now))
    }

    @Test
    fun newVideosFromMeta() {
        fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
        val meta = MetaItem(id = "tt1", type = "series", videos = listOf(
            Video("tt1:1:1", season = 1, episode = 1, released = iso(now - 20 * day)),
            Video("tt1:1:2", season = 1, episode = 2, released = iso(now - 2 * day)),
            Video("tt1:1:3", season = 1, episode = 3, released = iso(now + 5 * day)),
        ))
        assertEquals(setOf("tt1:1:2"), NewEpisodes.newVideoIds(series(), meta, now))
    }

    @Test
    fun requestIntervalAndImmediateOnNewSeries() {
        assertTrue(NewEpisodes.shouldRequest(null, now, false))
        assertFalse(NewEpisodes.shouldRequest(now - 5 * 3600_000L, now, false))
        assertTrue(NewEpisodes.shouldRequest(now - 6 * 3600_000L, now, false))
        assertTrue(NewEpisodes.shouldRequest(now - 1000, now, true))
    }

    @Test
    fun requestsOnlyCataloguesWithLastVideosIdsSortedIds() {
        val m = Manifest(id = "cm", name = "Cinemeta", types = listOf("series"), catalogs = listOf(
            ManifestCatalog("series", "last-videos", extra = listOf(ExtraProp("lastVideosIds", isRequired = true, optionsLimit = 2))),
            ManifestCatalog("series", "top"),
        ))
        val reqs = NewEpisodes.requests(listOf(Descriptor(m, "https://x/manifest.json")), listOf(series("tt9"), series("tt2"), series("tt5")))
        assertEquals(1, reqs.size)
        assertEquals("last-videos", reqs[0].catalogId)
        assertEquals("tt2,tt9", reqs[0].ids) // first two by order, then sorted
    }
}
