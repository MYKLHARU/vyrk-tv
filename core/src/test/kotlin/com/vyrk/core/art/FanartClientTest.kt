package com.vyrk.core.art

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class FanartClientTest {
    private class Fake : HttpTransport {
        val urls = mutableListOf<String>()
        var headers: Map<String, String> = emptyMap()
        var response = HttpResult(200, """{"hdtvlogo":[{"url":"https://assets.fanart.tv/fanart/logo.png","lang":"en"}]}""")
        var failure: Exception? = null
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
            urls += url; this.headers = headers; failure?.let { throw it }; return response
        }
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(404, "")
    }
    private val key = "0123456789abcdef0123456789abcdef"

    @Test fun verifiedIdsUseExplicitSourceAndHeaderKeyWithSeparateMovieTvCaches() = runTest {
        val http = Fake(); val client = FanartClient(http, key)
        assertEquals(1, client.logos("tt2560140", "series").size)
        client.logos("tt2560140", "series")
        assertEquals(1, http.urls.size)
        assertEquals("https://webservice.fanart.tv/v3.2/tv/tt2560140?source=imdb", http.urls[0])
        assertEquals(key, http.headers["api-key"])
        assertTrue(http.urls.none { key in it })
        client.logos("tmdb:1399", "series")
        assertEquals("https://webservice.fanart.tv/v3.2/tv/1399?source=tmdb", http.urls.last())
        client.logos("tt2560140", "movie")
        assertTrue(http.urls.last().contains("/movies/"))
    }

    @Test fun unmappedAnimeAndDisabledKeysNeverRequestGuessedTitles() = runTest {
        val http = Fake(); val client = FanartClient(http, key)
        assertTrue(client.logos("kitsu:7442", "series").isEmpty())
        assertTrue(client.logos("Attack on Titan", "anime").isEmpty())
        assertTrue(client.logos("tt2560140", "tv").isEmpty())
        assertTrue(FanartClient(http, "").logos("tt2560140", "series").isEmpty())
        assertTrue(http.urls.isEmpty())
    }

    @Test fun missingArtExpiresAndAuthenticationOrRateLimitsBackOff() = runTest {
        val http = Fake(); var now = 0L; val client = FanartClient(http, key) { now }
        http.response = HttpResult(404, "")
        client.logos("tt2560140", "series"); client.logos("tt2560140", "series")
        assertEquals(1, http.urls.size)
        now += 16 * 60_000
        http.response = HttpResult(429, "")
        client.logos("tt2560140", "series"); client.logos("tt1234567", "series")
        assertEquals(2, http.urls.size)
        now += 61_000
        client.logos("tt1234567", "series")
        assertEquals(3, http.urls.size)
    }

    @Test fun networkErrorsAreNotCachedAndCancellationIsPreserved() = runTest {
        val http = Fake(); val client = FanartClient(http, key)
        http.failure = IllegalStateException("synthetic private header")
        assertTrue(client.logos("tt2560140", "series").isEmpty())
        http.failure = null
        assertEquals(1, client.logos("tt2560140", "series").size)
        http.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { client.logos("tt1234567", "series") }
    }

    @Test fun logosPreferEnglishHdThenJapaneseAndRejectNonImageHosts() {
        val json = ProtocolJson.parseToJsonElement("""{
          "hdtvlogo":[{"lang":"ja","likes":"20","url":"https://assets.fanart.tv/ja.png"},
            {"lang":"en","likes":"2","url":"https://assets.fanart.tv/en.png"},
            {"lang":"en","url":"https://untrusted.invalid/logo.png"}],
          "clearlogo":[{"lang":"en","likes":"90","url":"https://assets.fanart.tv/legacy.png"}],
          "hdmovielogo":[{"lang":"en","url":"https://assets.fanart.tv/movie.png"}]
        }""") as JsonObject
        assertEquals(listOf("https://assets.fanart.tv/en.png", "https://assets.fanart.tv/legacy.png", "https://assets.fanart.tv/ja.png"), FanartClient.parseLogos(json, false))
        assertEquals(listOf("https://assets.fanart.tv/movie.png"), FanartClient.parseLogos(json, true))
    }
}
