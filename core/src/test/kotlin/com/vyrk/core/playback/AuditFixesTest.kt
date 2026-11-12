package com.vyrk.core.playback

import com.vyrk.core.addon.*
import com.vyrk.core.net.*
import com.vyrk.core.subtitles.SubtitleParser
import com.vyrk.core.tmdb.TmdbClient
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AuditFixesTest {
    @Test fun lowerQualityPolicyAdvancesFromItsActualIndex() {
        val p = RecoveryPolicy(4, false, startIndex = 2)
        assertEquals(RecoveryAction.NextCandidate, p.onFirstFrameTimeout())
        assertEquals(3, p.candidateIndex)
        assertEquals(RecoveryAction.GiveUp, p.onFirstFrameTimeout())
    }
    @Test fun subtitlesSkipOverflowButKeepValidCues() {
        val text = "999999999999999999999:00:00,000 --> 999999999999999999999:00:01,000\nBad\n\n00:00:01,000 --> 00:00:02,000\nGood"
        assertEquals(listOf("Good"), SubtitleParser.parse(text).map { it.text })
        assertEquals(-1, SubtitleParser.parseTime("2562047788016:00:00.000"))
    }
    @Test fun originalLanguageUnderstandsAddonAndTmdbMetadata() = runBlocking {
        assertEquals("fr", ProtocolJson.decodeFromString(MetaItem.serializer(), """{"id":"tt1","type":"movie","original_language":"fr"}""").originalLanguage)
        val paths = mutableListOf<String>()
        val http = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
                paths += url
                return HttpResult(200, if ("/find/" in url) """{"tv_results":[{"id":7}]}""" else """{"original_language":"ja"}""")
            }
            override suspend fun postJson(url: String, body: String, timeoutMs: Long) = error("unexpected")
        }
        val tmdb = TmdbClient(http, "synthetic-key")
        assertEquals("ja", tmdb.originalLanguage("tt1", "series"))
        assertEquals("ja", tmdb.originalLanguage("tt1", "series"))
        assertEquals(2, paths.size)
        assertTrue(paths.last().contains("/tv/7"))
    }

    // ---- v0.1.36 audit ----

    @Test fun stallPromptIgnoresRebufferingWhileAStreamIsStillStarting() {
        val s = StallDetector()
        s.onFirstFrame(1_000)
        // A stream that has just started dips back into buffering while its buffer fills. Three of
        // those raised "Playback keeps pausing" within seconds of pressing Play.
        assertFalse(s.onBuffering(1_500))
        assertFalse(s.onBuffering(3_000))
        assertFalse(s.onBuffering(5_000))
        assertFalse(s.onBuffering(9_000))
        // Stalls once it is playing still count, and the start-up ones are not part of the three.
        assertFalse(s.onBuffering(20_000))
        assertFalse(s.onBuffering(30_000))
        assertTrue(s.onBuffering(40_000))
    }

    @Test fun stallPromptStartupGraceBeginsAgainForEachSource() {
        val s = StallDetector()
        s.onFirstFrame(0)
        assertFalse(s.onBuffering(20_000)); assertFalse(s.onBuffering(30_000))
        // Another source starts (the player resets the detector on its first frame).
        s.reset(); s.onFirstFrame(100_000)
        assertFalse(s.onBuffering(101_000)); assertFalse(s.onBuffering(102_000)); assertFalse(s.onBuffering(103_000))
    }

    @Test fun watchNextDigestChangesByTheMinuteNotEverySavedSecond() {
        fun item(id: String, offsetMs: Long, last: Long) = com.vyrk.core.library.LibraryItem.fromMeta(MetaPreview(id, "movie", "Film $id"), 0)
            .let { it.copy(state = it.state.copy(timeOffset = offsetMs, duration = 5_400_000, lastWatchedMs = last)) }
        val rules = com.vyrk.core.library.LibraryRules
        val a = rules.watchNextDigest(listOf(item("a", 61_000, 10), item("b", 30_000, 5)))
        // One second later, as saved during playback: same row.
        assertEquals(a, rules.watchNextDigest(listOf(item("a", 62_000, 11), item("b", 30_000, 5))))
        // The next minute, another order, or a title leaving the row: republish.
        assertNotEquals(a, rules.watchNextDigest(listOf(item("a", 121_000, 12), item("b", 30_000, 5))))
        assertNotEquals(a, rules.watchNextDigest(listOf(item("a", 61_000, 10), item("b", 30_000, 50))))
        assertNotEquals(a, rules.watchNextDigest(listOf(item("a", 61_000, 10), item("b", 0, 5))))
        assertEquals(1, rules.watchNextDigest(listOf(item("a", 61_000, 10), item("b", 30_000, 5)), limit = 1).size)
    }
}
