package com.vyrk.core.playback

import com.vyrk.core.addon.MetaItem
import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.addon.Video
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryItemState
import com.vyrk.core.library.LibraryRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodePlaybackGuardTest {
    private val minute = 60_000L
    private val episode = Video("show:1:1", season = 1, episode = 1, runtime = "45 min")
    private val nextEpisode = Video("show:1:2", season = 1, episode = 2)
    private val meta = MetaItem("show", "series", runtime = "45 min", videos = listOf(episode, nextEpisode))
    private fun guard(v: Video = episode, m: MetaItem = meta) = EpisodePlaybackGuard(EpisodePlaybackGuard.runtime(m, v))

    @Test fun fiveMinuteSourceBlocksBothAutoplayPaths() {
        val g = guard()
        assertTrue(g.isShort(5 * minute))
        assertFalse(g.upNextDue(5 * minute, 5 * minute, null))
        assertFalse(g.upNextDue(5 * minute, 5 * minute, 4 * minute))
        assertEquals(IncompleteReason.SHORT_STREAM, g.endReason(5 * minute, 5 * minute))
        assertEquals(45 * minute, g.progressDuration(5 * minute))
    }

    @Test fun normalStreamStillOffersUpNextAndEndsNormally() {
        val g = guard()
        assertFalse(g.isShort(45 * minute))
        assertFalse(g.upNextDue(20 * minute, 45 * minute, null))
        assertTrue(g.upNextDue(44 * minute, 45 * minute, null))
        assertNull(g.endReason(45 * minute, 45 * minute))
        assertNull(g.endReason(43 * minute, 43 * minute)) // Different release / omitted recap.
    }

    @Test fun episodeRuntimeOverridesShowAverageForGenuinelyShortEpisodes() {
        val g = guard(episode.copy(runtime = "5 min"))
        assertFalse(g.isShort(5 * minute))
        assertTrue(g.upNextDue(5 * minute - 10_000, 5 * minute, null))
        assertNull(g.endReason(5 * minute, 5 * minute))
    }

    @Test fun showAverageOnlyBlocksVeryLargeDiscrepancies() {
        val g = guard(episode.copy(runtime = null))
        assertEquals(EpisodeRuntime(45 * minute, false), g.expected)
        assertTrue(g.isShort(5 * minute))
        assertFalse(g.isShort(22 * minute))
        assertFalse(guard(episode.copy(season = 0, runtime = null)).isShort(5 * minute))
    }

    @Test fun unknownRuntimeDoesNotInventMinimumEpisodeLength() {
        val g = guard(episode.copy(runtime = null), meta.copy(runtime = null))
        assertNull(g.expected)
        assertFalse(g.isShort(5 * minute))
        assertNull(g.endReason(5 * minute, 5 * minute))
    }

    @Test fun earlyEndWithFullAdvertisedTimelineIsIncompleteEvenWithoutMetadata() {
        val g = EpisodePlaybackGuard(null)
        assertEquals(IncompleteReason.EARLY_END, g.endReason(5 * minute, 45 * minute))
    }

    @Test fun unknownOrUnsetTimelineRequiresUserChoiceAtEnd() {
        val g = EpisodePlaybackGuard(null)
        for (duration in listOf(0L, -9223372036854775807L)) {
            assertFalse(g.isShort(duration))
            assertFalse(g.upNextDue(5 * minute, duration, null))
            assertEquals(IncompleteReason.UNKNOWN_END, g.endReason(5 * minute, duration))
        }
    }

    @Test fun timelineThatShrinksAfterPlaybackStartsIsDetected() {
        val g = EpisodePlaybackGuard(null)
        g.observeDuration(45 * minute)
        g.observeDuration(5 * minute)
        assertTrue(g.isShort(5 * minute))
        assertEquals(45 * minute, g.progressDuration(5 * minute))
        assertFalse(g.upNextDue(5 * minute, 5 * minute, null))
        assertEquals(45 * minute, g.progressDuration(0)) // Timeline disappears at EOS: keep resume/undo ability.
    }

    @Test fun smallRuntimeDifferencesAndVeryShortEpisodesHaveTolerance() {
        assertFalse(guard().isShort(40 * minute))
        assertFalse(guard(episode.copy(runtime = "2 min")).isShort(80_000))
        assertTrue(guard().isShort(10 * minute))
        assertFalse(guard().isShort((45 * minute * 0.65).toLong()))
    }

    @Test fun creditsFromWrongReleaseCannotTriggerPrematureUpNext() {
        val g = guard()
        assertFalse(g.upNextDue(5 * minute, 45 * minute, 4 * minute))
        assertTrue(g.upNextDue(44 * minute, 45 * minute, 4 * minute))
        assertTrue(g.upNextDue(39 * minute, 45 * minute, 38 * minute))
    }

    @Test fun suspectsAreRememberedAcrossRetryButScopedToOneEpisode() {
        val g = guard()
        g.rememberSuspect("addon:sample")
        assertTrue(g.isSuspect("addon:sample"))
        assertFalse(g.isSuspect("addon:full"))
        assertFalse(guard().isSuspect("addon:sample"))
        // A successful manual retry may have a corrected timeline.
        assertFalse(g.isShort(45 * minute))
        assertNull(g.endReason(45 * minute, 45 * minute))
    }

    @Test fun timelineEvidenceBelongsToItsSourceAndSurvivesRetry() {
        val g = guard(episode.copy(runtime = "5 min"))
        g.beginSource("bad")
        g.observeDuration(45 * minute)
        assertTrue(g.isShort(5 * minute))
        g.beginSource("alternative")
        assertFalse(g.isShort(5 * minute)) // A wrong long source cannot poison a correct short one.
        g.beginSource("bad")
        assertTrue(g.isShort(5 * minute))
    }

    @Test fun episodeRuntimeSurvivesProtocolAndCacheRoundTrip() {
        for (raw in listOf("\"1h 5min\"", "65")) {
            val v = ProtocolJson.decodeFromString<Video>("""{"id":"show:1:1","runtime":$raw}""")
            assertEquals(65 * minute, EpisodePlaybackGuard.runtime(meta, v)?.durationMs)
            val cached = ProtocolJson.decodeFromString<Video>(ProtocolJson.encodeToString(Video.serializer(), v))
            assertEquals(v.runtime, cached.runtime)
        }
        assertNull(ProtocolJson.decodeFromString<Video>("""{"id":"show:1:1"}""").runtime)
    }

    @Test fun invalidOrAbsurdMetadataDoesNotSupplyRuntimeEvidence() {
        for (raw in listOf(null, "", "unknown", "0 min", "9999 min", "100h")) {
            assertNull(EpisodePlaybackGuard.runtime(meta.copy(runtime = raw), episode.copy(runtime = raw)))
        }
        assertNull(EpisodePlaybackGuard.runtime(meta.copy(type = "movie"), episode))
        assertNull(EpisodePlaybackGuard.runtime(null, episode))
    }

    @Test fun incompleteStreamKeepsResumeWithoutWatchedBitsOrNextEpisodePointer() {
        val g = guard()
        var item = LibraryItem("show", "Show", "series", state = LibraryItemState(videoId = episode.id))
        val duration = 5 * minute
        for (position in 1_000L..duration step 1_000L) {
            val (updated, crossed) = LibraryRules.onTimeChanged(item, episode.id, position,
                g.progressDuration(duration), position, item.watchedBitField(meta.videos), allowCompletion = !g.isShort(duration))
            assertFalse(crossed)
            item = updated
        }
        val ended = LibraryRules.onPlaybackEnded(item, nextEpisode, allowCompletion = g.endReason(duration, duration) == null)
        assertEquals(episode.id, ended.state.videoId)
        assertEquals(duration, ended.state.timeOffset)
        assertEquals(duration, LibraryRules.resumeMs(ended, episode.id))
        assertEquals(0, ended.state.flaggedWatched)
        assertEquals(0, ended.state.timesWatched)
        assertFalse(ended.watchedBitField(meta.videos).getVideo(episode.id))
        assertTrue(ended.isInContinueWatching)
    }

    @Test fun manualNextAfterIncompleteEpisodeDoesNotCarryItsPositionIntoNext() {
        val old = LibraryItem("show", "Show", "series", state = LibraryItemState(
            videoId = episode.id, timeOffset = 5 * minute, duration = 45 * minute))
        val next = LibraryRules.onTimeChanged(old, nextEpisode.id, 1_000, 45 * minute, 1_000, allowCompletion = false).first
        assertEquals(nextEpisode.id, next.state.videoId)
        assertEquals(1_000, next.state.timeOffset)
        assertEquals(0, next.state.flaggedWatched)
    }

    @Test fun completionGatePreventsAdvanceEvenWithTruncatedDurationSavedPreviously() {
        val item = LibraryItem("show", "Show", "series", state = LibraryItemState(
            videoId = episode.id, timeOffset = 5 * minute, duration = 5 * minute))
        assertEquals(item, LibraryRules.onPlaybackEnded(item, nextEpisode, allowCompletion = false))
    }

    @Test fun lateEarlyEndCanUndoOnlyThisSessionsWatchedMark() {
        val original = LibraryItem("show", "Show", "series", state = LibraryItemState(videoId = episode.id, timesWatched = 2))
        val marked = LibraryRules.onTimeChanged(original, episode.id, 35 * minute, 45 * minute, 1_000, original.watchedBitField(meta.videos)).first
        assertEquals(3, marked.state.timesWatched)
        assertTrue(marked.watchedBitField(meta.videos).getVideo(episode.id))
        assertEquals(IncompleteReason.EARLY_END, guard().endReason(35 * minute, 45 * minute))
        val restored = LibraryRules.undoPlaybackWatched(marked, episode.id, meta.videos, previouslyWatched = false)
        assertEquals(2, restored.state.timesWatched)
        assertEquals(0, restored.state.flaggedWatched)
        assertFalse(restored.watchedBitField(meta.videos).getVideo(episode.id))
        assertEquals(35 * minute, restored.state.timeOffset)
        assertTrue(LibraryRules.undoPlaybackWatched(marked, episode.id, meta.videos, previouslyWatched = true)
            .watchedBitField(meta.videos).getVideo(episode.id))
    }
}
