package com.vyrk.core.addon

import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonProtocolTest {

    @Test
    fun encodesIdsLikeEncodeUriComponent() {
        assertEquals("kitsu%3A1376", AddonUrls.encodeComponent("kitsu:1376"))
        assertEquals("tt0386676%3A5%3A1", AddonUrls.encodeComponent("tt0386676:5:1"))
        assertEquals("a-b_c.d!e~f*g'h(i)", AddonUrls.encodeComponent("a-b_c.d!e~f*g'h(i)"))
        assertEquals("the%20matrix", AddonUrls.encodeComponent("the matrix"))
        assertEquals("%C3%A9t%C3%A9", AddonUrls.encodeComponent("été"))
        assertEquals("a%2Fb%3Fc%26d", AddonUrls.encodeComponent("a/b?c&d"))
    }

    @Test
    fun buildsResourceUrls() {
        val base = "https://v3-cinemeta.strem.io/manifest.json"
        assertEquals("https://v3-cinemeta.strem.io/meta/series/kitsu%3A1.json", AddonUrls.resourceUrl(base, "meta", "series", "kitsu:1"))
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=the%20matrix&skip=100.json",
            AddonUrls.resourceUrl(base, "catalog", "movie", "top", listOf("search" to "the matrix", "skip" to "100")),
        )
        // Configured add-ons keep their path segments and query after the resource path.
        assertEquals(
            "https://addon.example/abc%3D/stream/movie/tt1.json?x=1",
            AddonUrls.resourceUrl("https://addon.example/abc%3D/manifest.json?x=1", "stream", "movie", "tt1"),
        )
    }

    @Test
    fun normalisesInstallInputs() {
        assertEquals("https://a.example/manifest.json", AddonUrls.normaliseInstallUrl("stremio://a.example/manifest.json"))
        assertEquals("https://a.example/manifest.json", AddonUrls.normaliseInstallUrl("a.example"))
        assertEquals("http://10.0.2.2:7000/manifest.json", AddonUrls.normaliseInstallUrl("vyrk://install?url=http%3A%2F%2F10.0.2.2%3A7000%2Fmanifest.json"))
        assertNull(AddonUrls.normaliseInstallUrl("ftp://x/manifest.json"))
        assertNull(AddonUrls.normaliseInstallUrl("   "))
    }

    @Test
    fun redactsUrlsAndTokens() {
        assertEquals("https://debrid.example/…", Redact.url("https://user:pass@debrid.example/APIKEY123/manifest.json?token=abc"))
        assertEquals("http://10.0.2.2:7000/…", Redact.url("http://10.0.2.2:7000/manifest.json"))
        assertEquals("https://host.example", Redact.url("https://host.example"))
        val t = Redact.text("failed https://x.example/secret/path {\"authKey\":\"abcdef\"}")
        assertFalse(t.contains("secret")); assertFalse(t.contains("abcdef"))
    }

    private val cinemetaLike = """
        {"id":"com.linvo.cinemeta","version":"3.0.14","name":"Cinemeta","resources":["catalog","meta","addon_catalog"],
         "types":["movie","series"],"idPrefixes":["tt"],
         "catalogs":[{"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action"]},{"name":"search"},{"name":"skip"}]},
                     {"type":"series","id":"last-videos","extra":[{"name":"lastVideosIds","isRequired":true}]}],
         "unknownField":{"a":1}}
    """.trimIndent()

    @Test
    fun parsesManifestAndSupportRules() {
        val m = ProtocolJson.decodeFromString(Manifest.serializer(), cinemetaLike)
        assertEquals(3, m.resources.size)
        assertTrue(m.supports("meta", "movie", "tt123"))
        assertFalse(m.supports("meta", "movie", "kitsu:1"))
        assertFalse(m.supports("stream", "movie", "tt1"))
        assertTrue(m.catalogs[0].supportsExtra("search"))
        assertEquals(listOf("lastVideosIds"), m.catalogs[1].requiredExtras)
        assertTrue(m.supportsCatalog("movie", "top", setOf("search")))
        assertFalse(m.supportsCatalog("series", "last-videos", emptySet()))

        val full = ProtocolJson.decodeFromString(Manifest.serializer(), """
            {"id":"x","name":"X","types":["movie"],"resources":[{"name":"stream","types":["series"],"idPrefixes":[]}]}
        """)
        assertTrue(full.supports("stream", "series", "kitsu:1")) // empty prefixes accept all
        assertFalse(full.supports("stream", "movie", "tt1")) // object types win, no fallback
    }

    @Test
    fun parsesStreamsWithUnknownFields() {
        val r = ProtocolJson.decodeFromString(StreamsResponse.serializer(), """
            {"streams":[{"name":"X 4K","title":"Movie.2160p.mkv","url":"https://h/f.mkv","behaviorHints":{"bingeGroup":"x-4k","notWebReady":true,"foo":1}},
                        {"infoHash":"abc","fileIdx":1}],"cacheMaxAge":3600}
        """)
        assertEquals(2, r.streams.size)
        assertEquals("x-4k", r.streams[0].behaviorHints.bingeGroup)
    }

    private class FakeHttp(val handler: suspend (String) -> HttpResult) : HttpTransport {
        val inFlight = AtomicInteger(); val maxInFlight = AtomicInteger()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
            val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            try { return handler(url) } finally { inFlight.decrementAndGet() }
        }
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(500, "")
    }

    private fun addon(id: String, url: String) = Descriptor(
        Manifest(id = id, name = id, types = listOf("movie"), rawResources = listOf(kotlinx.serialization.json.JsonPrimitive("stream"))),
        url,
    )

    @Test
    fun slowAddonTimesOutWithoutBlockingOthers() = runTest {
        val http = FakeHttp { url ->
            if (url.contains("slow")) delay(60_000)
            HttpResult(200, """{"streams":[{"url":"https://f/x.mp4","name":"ok"}]}""")
        }
        val client = AddonClient(http, timeoutMs = { _, _ -> 1_000 })
        val results = client.allStreams(listOf(addon("slow", "https://slow/manifest.json"), addon("fast", "https://fast/manifest.json")), "movie", "tt1")
        assertIs<AddonResult.Failed>(results[0].second)
        assertTrue((results[0].second as AddonResult.Failed).timedOut)
        assertIs<AddonResult.Ok<StreamsResponse>>(results[1].second)
    }

    @Test
    fun concurrencyIsLimitedToEight() = runTest {
        val http = FakeHttp { delay(100); HttpResult(200, """{"streams":[]}""") }
        val client = AddonClient(http)
        val addons = (1..20).map { addon("a$it", "https://a$it/manifest.json") }
        client.allStreams(addons, "movie", "tt1")
        assertTrue(http.maxInFlight.get() <= 8, "max in flight ${http.maxInFlight.get()}")
        assertEquals(8, http.maxInFlight.get())
    }

    @Test
    fun errorsAreRedacted() = runTest {
        val http = FakeHttp { throw java.io.IOException("connect failed https://secret.example/KEY/manifest.json") }
        val r = AddonClient(http).streams(addon("a", "https://secret.example/KEY/manifest.json"), "movie", "tt1")
        val f = assertIs<AddonResult.Failed>(r)
        assertFalse(f.reason.contains("KEY"))
    }
}
