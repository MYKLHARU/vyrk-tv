package com.vyrk.core.trakt

import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shapes follow Trakt's API reference; values are made up. */
class TraktTest {
    private class Fake(val reply: (String, String) -> HttpResult) : HttpTransport {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = reply(url, "").also { calls += Triple(url, "", headers) }
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = error("headers are required")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Long) = reply(url, body).also { calls += Triple(url, body, headers) }
    }

    @Test fun itemsComeFromImdbIdsOnly() {
        assertEquals(TraktItem.Movie("tt1"), TraktItem.of("movie", "tt1", null))
        assertEquals(TraktItem.Episode("tt2", 3, 7), TraktItem.of("series", "tt2", "tt2:3:7"))
        assertNull(TraktItem.of("series", "tt2", null))
        assertNull(TraktItem.of("series", "kitsu:1", "kitsu:1:1"))
        assertNull(TraktItem.of("movie", "tmdb:5", null))
    }

    @Test fun devicePollAnswers() {
        assertIs<TokenPoll.Pending>(TraktClient.parseTokenPoll(HttpResult(400, ""), 0))
        assertIs<TokenPoll.SlowDown>(TraktClient.parseTokenPoll(HttpResult(429, ""), 0))
        assertIs<TokenPoll.Expired>(TraktClient.parseTokenPoll(HttpResult(410, ""), 0))
        assertIs<TokenPoll.Denied>(TraktClient.parseTokenPoll(HttpResult(418, ""), 0))
        val ok = TraktClient.parseTokenPoll(HttpResult(200, """{"access_token":"a","refresh_token":"r","expires_in":7776000,"created_at":1700000000}"""), 0)
        assertIs<TokenPoll.Approved>(ok)
        assertEquals(1_700_000_000_000L + 7_776_000_000L, ok.tokens.expiresAtMs)
    }

    @Test fun scrobbleSendsKeyVersionTokenAndEpisodeBody() = runTest {
        val f = Fake { _, _ -> HttpResult(201, "{}") }
        val t = TraktClient(f, "id", "secret")
        assertEquals(201, t.scrobble("stop", TraktItem.Episode("tt2", 1, 4), 91.256f, "tok"))
        val (url, body, headers) = f.calls.single()
        assertEquals("https://api.trakt.tv/scrobble/stop", url)
        assertEquals("""{"show":{"ids":{"imdb":"tt2"}},"episode":{"season":1,"number":4},"progress":91.26}""", body)
        assertEquals("2", headers["trakt-api-version"])
        assertEquals("id", headers["trakt-api-key"])
        assertEquals("Bearer tok", headers["Authorization"])
    }

    @Test fun historyGroupsEpisodesByShowAndSeason() {
        val body = TraktClient.historyBody(listOf(TraktItem.Movie("tt9"), TraktItem.Episode("tt2", 1, 1), TraktItem.Episode("tt2", 1, 2), TraktItem.Episode("tt2", 2, 1)))
        assertEquals("""{"movies":[{"ids":{"imdb":"tt9"}}],"shows":[{"ids":{"imdb":"tt2"},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]},{"number":2,"episodes":[{"number":1}]}]}]}""", body.toString())
    }

    @Test fun offWithoutKeys() {
        assertFalse(TraktClient(Fake { _, _ -> HttpResult(200, "") }, "", "x").enabled)
        assertTrue(TraktClient(Fake { _, _ -> HttpResult(200, "") }, "a", "b").enabled)
    }
}
