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
}
