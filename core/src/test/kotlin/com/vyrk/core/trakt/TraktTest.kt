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
        assertIs<TokenPoll.Denied>(TraktClient.parseTokenPoll(HttpResult(409, ""), 0))
        assertIs<TokenPoll.NotAllowed>(TraktClient.parseTokenPoll(HttpResult(418, ""), 0))
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

    @Test fun offWithoutAClientIdAndOnWithoutASecret() {
        assertFalse(TraktClient(Fake { _, _ -> HttpResult(200, "") }, "", "x").enabled)
        assertTrue(TraktClient(Fake { _, _ -> HttpResult(200, "") }, "a", "b").enabled)
        assertTrue(TraktClient(Fake { _, _ -> HttpResult(200, "") }, "a").enabled)
    }

    @Test fun pkceMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals("", Pkce.base64Url(ByteArray(0)))
        assertEquals("__4", Pkce.base64Url(byteArrayOf(-1, -2)))
        val v = Pkce.verifier()
        assertEquals(64, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test fun deviceSignInWithoutASecretUsesPkceOnTheAuthHost() = runTest {
        val f = Fake { url, _ ->
            if (url.endsWith("/device/code")) HttpResult(200, """{"device_code":"dc","user_code":"ABCD1234","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""")
            else HttpResult(400, "")
        }
        val t = TraktClient(f, "6Jx2-id_x")
        val code = t.deviceCode()!!
        val (url, body, headers) = f.calls.single()
        assertEquals("https://auth.trakt.tv/oauth/device/code", url)
        assertEquals("6Jx2-id_x", headers["trakt-api-key"])
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        assertEquals("S256", sent["code_challenge_method"].toString().trim('"'))
        assertEquals(Pkce.challenge(code.codeVerifier), sent["code_challenge"].toString().trim('"'))

        assertIs<TokenPoll.Pending>(t.pollToken(code, 0))
        val poll = f.calls.last()
        assertEquals("https://auth.trakt.tv/oauth/device/token", poll.first)
        assertTrue("\"code_verifier\":\"${code.codeVerifier}\"" in poll.second)
        assertFalse("client_secret" in poll.second)
    }

    @Test fun refreshWithoutASecretSendsOnlyTheClientIdAndFallsBackToTheApiHost() = runTest {
        val f = Fake { url, _ ->
            if (url.startsWith(TraktClient.AUTH)) HttpResult(503, "")
            else HttpResult(200, """{"access_token":"a2","refresh_token":"r2","expires_in":604800,"created_at":1700000000}""")
        }
        val fresh = TraktClient(f, "id").refresh(TraktTokens("a", "r", 0, "me"), 0)
        assertEquals(TraktTokens("a2", "r2", 1_700_000_000_000L + 604_800_000L, "me"), fresh)
        assertEquals(listOf("https://auth.trakt.tv/oauth/token", "https://api.trakt.tv/oauth/token"), f.calls.map { it.first })
        val body = f.calls.last().second
        assertFalse("client_secret" in body); assertFalse("redirect_uri" in body)
        assertTrue("\"grant_type\":\"refresh_token\"" in body)
    }

    @Test fun olderAppsStillSendTheirSecret() = runTest {
        val f = Fake { _, _ -> HttpResult(400, "") }
        val t = TraktClient(f, "id", "sec")
        t.pollToken(DeviceCode("dc", "U", "https://trakt.tv/activate", 600, 5), 0)
        t.refresh(TraktTokens("a", "r", 0), 0)
        assertTrue(f.calls.all { "\"client_secret\":\"sec\"" in it.second })
        assertTrue("urn:ietf:wg:oauth:2.0:oob" in f.calls.last().second)
    }
}
