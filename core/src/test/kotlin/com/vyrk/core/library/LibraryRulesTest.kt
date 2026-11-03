package com.vyrk.core.library

import com.vyrk.core.account.RemoteMeta
import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.Video
import com.vyrk.core.addon.ProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRulesTest {
    private val now = 1_750_000_000_000L
    private fun movie(offset: Long = 0, removed: Boolean = false, temp: Boolean = false, type: String = "movie") = LibraryItem(
        id = "tt0111161", name = "Test", type = type, removed = removed, temp = temp, mtimeMs = now,
        state = LibraryItemState(timeOffset = offset, duration = 100_000),
    )

    @Test
    fun continueWatchingRules() {
        assertTrue(movie(offset = 10).isInContinueWatching)
        assertFalse(movie(offset = 0).isInContinueWatching)
        assertFalse(movie(offset = 10, removed = true).isInContinueWatching)
        assertTrue(movie(offset = 10, removed = true, temp = true).isInContinueWatching)
        assertFalse(movie(offset = 10, type = "other").isInContinueWatching)
    }

    @Test
    fun watchedAfterSeventyPercentOfWatchedTime() {
        var item = movie()
        item = LibraryRules.onTimeChanged(item, "tt0111161", 1_000, 100_000, now).first // switch video
        var crossed = false
        var t = 1_000L
        while (t <= 72_000) {
            val (next, c) = LibraryRules.onTimeChanged(item, "tt0111161", t, 100_000, now)
            item = next; crossed = crossed || c; t += 1_000
        }
        assertTrue(crossed)
        assertEquals(1, item.state.flaggedWatched)
        assertEquals(1, item.state.timesWatched)
        assertEquals(72_000, item.state.timeOffset)
    }

    @Test
    fun seekingForwardDoesNotCountAsWatched() {
        var item = movie()
        item = LibraryRules.onTimeChanged(item, "tt0111161", 1_000, 100_000, now).first
        // jump straight to 95% via seek: the player reports time after the seek
        val (after, crossed) = LibraryRules.onTimeChanged(item.copy(state = item.state.copy(timeOffset = 95_000)), "tt0111161", 95_500, 100_000, now)
        assertFalse(crossed)
        assertEquals(500, after.state.timeWatched)
    }

    @Test
    fun creditsThresholdClearsResumeAndAdvances() {
        val item = movie(offset = 91_000)
        val next = Video(id = "tt1:1:2", season = 1, episode = 2)
        val ended = LibraryRules.onPlaybackEnded(item, next)
        assertEquals("tt1:1:2", ended.state.videoId)
        assertEquals(1, ended.state.timeOffset) // advance_to_video sets 1
        val ended2 = LibraryRules.onPlaybackEnded(movie(offset = 91_000), null)
        assertEquals(0, ended2.state.timeOffset)
        assertFalse(ended2.isInContinueWatching)
        val notEnded = LibraryRules.onPlaybackEnded(movie(offset = 50_000), next)
        assertEquals(50_000, notEnded.state.timeOffset)
    }

    @Test
    fun rewindRemovesFromContinueWatchingButKeepsHistory() {
        val item = movie(offset = 40_000).copy(state = LibraryItemState(timeOffset = 40_000, duration = 100_000, timesWatched = 2, watched = "x:1:eJwDAAAAAAE="))
        val r = LibraryRules.touch(LibraryRules.rewind(item), now + 5)
        assertFalse(r.isInContinueWatching)
        assertEquals(2, r.state.timesWatched)
        assertEquals("x:1:eJwDAAAAAAE=", r.state.watched)
        assertFalse(r.removed)
        assertEquals(now + 5, r.mtimeMs)
    }

    @Test
    fun liveItemsNeverKeepVodProgress() {
        val live = LibraryItem("ch1", "Channel", "tv", mtimeMs = now)
        val (after, _) = LibraryRules.onTimeChanged(live, "ch1:stream", 60_000, 0, now)
        assertEquals(0, after.state.timeOffset)
        assertFalse(after.isInContinueWatching)
    }

    @Test
    fun tempItemCreatedFromPlaybackStaysRemovedUntilWatched() {
        val meta = MetaItem(id = "tt9", type = "movie", name = "Nine").toPreview()
        val temp = LibraryItem.fromMeta(meta, now)
        assertTrue(temp.removed && temp.temp)
        val (afterTick, _) = LibraryRules.onTimeChanged(temp, "tt9", 5_000, 100_000, now)
        assertTrue(afterTick.isInContinueWatching)
        assertFalse(afterTick.inLibrary)
    }

    @Test
    fun jsonRoundTripMatchesStremioShape() {
        val json = """
            {"_id":"tt2934286","name":"Halo","type":"series","poster":"https://example.com/p.jpg","posterShape":"poster",
             "removed":false,"temp":false,"_ctime":"2024-03-01T10:00:00.000Z","_mtime":"2024-03-02T11:00:00Z",
             "state":{"lastWatched":"2024-03-02T10:59:00Z","timeWatched":120000,"timeOffset":130000,"overallTimeWatched":500000,
                      "timesWatched":1,"flaggedWatched":0,"duration":3000000,"video_id":"tt2934286:1:5",
                      "watched":"tt2934286:1:5:5:eJyTZwAAAEAAIA==","noNotif":false},
             "behaviorHints":{"defaultVideoId":null,"featuredVideoId":null,"hasScheduledVideos":false}}
        """.trimIndent()
        val item = LibraryItem.fromJson(ProtocolJson.parseToJsonElement(json))
        assertEquals("tt2934286:1:5", item.state.videoId)
        assertEquals(130_000, item.state.timeOffset)
        assertTrue(item.isInContinueWatching)
        val again = LibraryItem.fromJson(item.toJson())
        assertEquals(item, again)
        val out = item.toJson()
        assertEquals("tt2934286", (out["_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertNotNull(out["state"])
    }

    @Test
    fun nullAndEmptyFieldsParse() {
        val json = """{"_id":"tt1","name":"A","type":"movie","removed":true,"temp":true,"_ctime":null,"_mtime":"2024-01-01T00:00:00Z",
            "state":{"lastWatched":"","timeWatched":0,"timeOffset":0,"overallTimeWatched":0,"timesWatched":0,"flaggedWatched":0,"duration":0,"video_id":"","watched":""}}"""
        val item = LibraryItem.fromJson(ProtocolJson.parseToJsonElement(json))
        assertNull(item.ctimeMs); assertNull(item.state.videoId); assertNull(item.state.watched); assertNull(item.state.lastWatchedMs)
    }

    @Test
    fun mergeKeepsNewerOrEqualMtime() {
        val a = movie().copy(mtimeMs = 10)
        val b = movie(offset = 5).copy(mtimeMs = 20)
        assertEquals(20, LibraryRules.merge(mapOf(a.id to a), listOf(b))[a.id]!!.mtimeMs)
        assertEquals(20, LibraryRules.merge(mapOf(b.id to b), listOf(a))[a.id]!!.mtimeMs)
    }

    @Test
    fun syncPlanPullsNewerRemoteAndPushesNewerLocal() {
        val local = mapOf(
            "a" to movie().copy(id = "a", mtimeMs = 100),
            "b" to movie().copy(id = "b", mtimeMs = 300),
            "c" to movie().copy(id = "c", mtimeMs = 50),
            "o" to movie(type = "other").copy(id = "o", mtimeMs = 500),
        )
        val remote = listOf(RemoteMeta("a", 200), RemoteMeta("b", 100), RemoteMeta("d", 10))
        val plan = LibrarySync.plan(local, remote, now)
        assertEquals(listOf("a", "d"), plan.pull)
        assertEquals(listOf("b", "c"), plan.push)
    }

    @Test
    fun pendingChangesOnlyClearUnchangedEntries() {
        val p = PendingChanges()
        p.mark("a"); p.mark("b")
        val sent = p.snapshot()
        p.mark("b") // changed again while in flight
        p.confirm(sent)
        assertEquals(setOf("b"), p.ids())
    }

    @Test
    fun continueWatchingNewestFirst() {
        val a = movie(offset = 1).copy(id = "a", state = LibraryItemState(timeOffset = 1, lastWatchedMs = 10))
        val b = movie(offset = 1).copy(id = "b", state = LibraryItemState(timeOffset = 1, lastWatchedMs = 20))
        assertEquals(listOf("b", "a"), LibraryRules.continueWatching(listOf(a, b)).map { it.id })
    }

    @Test
    fun markVideosWatchedSetsBitsAndLastWatched() {
        val videos = (1..3).map { Video(id = "s:1:$it", season = 1, episode = it, released = "2024-01-0${it}T00:00:00Z") }
        val meta = MetaItem(id = "s", type = "series", videos = videos)
        val item = LibraryItem("s", "S", "series", mtimeMs = now)
        val marked = LibraryRules.markVideosWatched(item, meta, LibraryRules.videosUpTo(meta, videos[1]), true)
        val wb = marked.state.watchedBitField(videos)
        assertTrue(wb.getVideo("s:1:1")); assertTrue(wb.getVideo("s:1:2")); assertFalse(wb.getVideo("s:1:3"))
        assertEquals(LibraryItem.parseTime(kotlinx.serialization.json.JsonPrimitive("2024-01-02T00:00:00Z")), marked.state.lastWatchedMs)
    }

    @Test
    fun nextVideoSkipsSpecialsAndUnreleased() {
        val videos = listOf(
            Video("s:1:1", season = 1, episode = 1, released = "2020-01-01T00:00:00Z"),
            Video("s:1:2", season = 1, episode = 2, released = "2099-01-01T00:00:00Z"),
            Video("s:0:1", season = 0, episode = 1, released = "2020-01-01T00:00:00Z"),
        )
        val meta = MetaItem(id = "s", type = "series", videos = videos)
        assertNull(meta.nextVideo("s:1:1", now)) // next is unreleased
        assertNull(meta.nextVideo("s:1:2", now))
    }
}
