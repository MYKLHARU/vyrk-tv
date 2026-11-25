package com.vyrk.core.catalog

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import com.vyrk.core.tmdb.TmdbClient
import com.vyrk.core.tmdb.TmdbFacts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TMDB fixtures follow the shapes in TMDB's v3 reference (release_dates, content_ratings, external_ids); values are made up. */
class CardFactsTest {
    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s) as JsonObject

    @Test fun yearsComeFromReleaseInfoOnly() {
        assertEquals("2024", CardFacts.year("2024"))
        assertEquals("2019", CardFacts.year("2019–2023"))
        assertEquals("1999", CardFacts.year("1999-"))
        assertEquals("2026", CardFacts.year("Fall 2026"))
        assertNull(CardFacts.year(null)); assertNull(CardFacts.year("")); assertNull(CardFacts.year("TBA")); assertNull(CardFacts.year("12345"))
    }

    @Test fun imdbScoresAreOutOfTenAndZeroIsAScore() {
        assertEquals(8.2, CardFacts.imdb("8.2")); assertEquals(8.2, CardFacts.imdb(" 8,2 ")); assertEquals(7.0, CardFacts.imdb("7"))
        assertEquals(0.0, CardFacts.imdb("0.0"), "a real zero is kept"); assertEquals(10.0, CardFacts.imdb("10"))
        assertNull(CardFacts.imdb(null)); assertNull(CardFacts.imdb("")); assertNull(CardFacts.imdb("N/A"))
        assertNull(CardFacts.imdb("11")); assertNull(CardFacts.imdb("-1")); assertNull(CardFacts.imdb("NaN"))
        assertEquals("8.2", CardFacts.formatImdb(8.2, Locale.US)); assertEquals("0.0", CardFacts.formatImdb(0.0, Locale.US))
        assertEquals("8,2", CardFacts.formatImdb(8.2, Locale.GERMANY), "the reader's decimal separator")
        assertEquals("10.0", CardFacts.formatImdb(10.0, Locale.US))
    }

    @Test fun tomatometerIsAPercentageAndZeroIsAScore() {
        assertEquals(92, CardFacts.tomatometer(92)); assertEquals(0, CardFacts.tomatometer(0)); assertEquals(100, CardFacts.tomatometer(100))
        assertNull(CardFacts.tomatometer(null)); assertNull(CardFacts.tomatometer(-1)); assertNull(CardFacts.tomatometer(101))
        assertEquals("92%", CardFacts.formatPercent(92, Locale.US)); assertEquals("0%", CardFacts.formatPercent(0, Locale.US))
    }

    @Test fun onlyImdbKeyedTitlesTrustAnAddonsImdbRating() {
        assertTrue(CardFacts.addonRatingIsImdb("tt0944947"))
        assertTrue(CardFacts.addonRatingIsImdb("tt0944947:1:2"))
        assertFalse(CardFacts.addonRatingIsImdb("kitsu:12"), "Anime Kitsu sends Kitsu's average as imdbRating")
        assertFalse(CardFacts.addonRatingIsImdb("mal:21")); assertFalse(CardFacts.addonRatingIsImdb("tmdb:1399")); assertFalse(CardFacts.addonRatingIsImdb("vyrk-1"))
    }

    @Test fun certificatesAreTrimmedNeverInvented() {
        assertEquals("15", CardFacts.certificate(" 15 ")); assertEquals("TV-MA", CardFacts.certificate("TV-MA"))
        assertNull(CardFacts.certificate("")); assertNull(CardFacts.certificate(null)); assertNull(CardFacts.certificate("Not rated in this country at all"))
    }

    @Test fun theRowShowsWhatThereIs() {
        assertFalse(CardFacts().hasRow)
        assertFalse(CardFacts(certificate = "15").hasRow, "the certificate sits in the corner, not the row")
        assertTrue(CardFacts(imdb = 0.0).hasRow); assertTrue(CardFacts(rottenTomatoes = 0).hasRow); assertTrue(CardFacts(year = "2024").hasRow)
        assertEquals("2024, IMDb 8.2 out of 10, Rotten Tomatoes 92%, rated 15",
            CardFacts.spoken(CardFacts("2024", 8.2, 92, "15"), locale = Locale.US))
        assertEquals("IMDb 0.0 out of 10", CardFacts.spoken(CardFacts(imdb = 0.0), locale = Locale.US))
        assertEquals("", CardFacts.spoken(CardFacts()))
    }

    @Test fun cinemetasRatingIsReadWithoutTheEpisodeList() {
        val body = """{"meta":{"id":"tt0388629","type":"series","name":"One Piece","imdbRating":"9.0","links":[{"name":"9.0","category":"imdb"}],"videos":[{"id":"tt0388629:1:1","name":"x"}]}}"""
        assertEquals(9.0, CardFacts.imdbFromCinemeta(body))
        assertEquals(0.0, CardFacts.imdbFromCinemeta("""{"meta":{"imdbRating":"0.0"}}"""))
        assertNull(CardFacts.imdbFromCinemeta("""{"meta":{"id":"tt1","name":"No rating"}}"""))
        assertNull(CardFacts.imdbFromCinemeta("""{"meta":{"imdbRating":""}}"""))
    }

    @Test fun filmsTakeTheRegionsTheatricalCertificate() {
        val o = obj("""{"id":550,"imdb_id":"tt0137523","release_dates":{"results":[
            {"iso_3166_1":"US","release_dates":[{"certification":"R","type":3}]},
            {"iso_3166_1":"GB","release_dates":[{"certification":"","type":1},{"certification":"18","type":4},{"certification":"18 ","type":3},{"certification":"15","type":6}]},
            {"iso_3166_1":"DE","release_dates":[{"certification":"","type":3}]}
        ]},"external_ids":{"imdb_id":"tt0137523"}}""")
        assertEquals(TmdbFacts("18", "tt0137523"), TmdbClient.parseFacts(o, "movie", "GB"))
        assertEquals("R", TmdbClient.parseFacts(o, "movie", "us").certification, "region case doesn't matter")
        assertNull(TmdbClient.parseFacts(o, "movie", "DE").certification, "a blank certificate is no certificate")
        assertNull(TmdbClient.parseFacts(o, "movie", "FR").certification, "no other region's is borrowed")
    }

    @Test fun showsTakeTheRegionsRating() {
        val o = obj("""{"id":1399,"content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"},{"iso_3166_1":"GB","rating":"15"},{"iso_3166_1":"BR","rating":""}]},
            "external_ids":{"imdb_id":"tt0944947","tvdb_id":121361}}""")
        assertEquals(TmdbFacts("15", "tt0944947"), TmdbClient.parseFacts(o, "tv", "GB"))
        assertEquals("TV-MA", TmdbClient.parseFacts(o, "tv", "US").certification)
        assertNull(TmdbClient.parseFacts(o, "tv", "BR").certification)
        assertEquals(TmdbFacts(null, null), TmdbClient.parseFacts(obj("""{"id":1,"external_ids":{"imdb_id":""}}"""), "tv", "GB"))
    }

    @Test fun factsAreOneRequestAfterFindAndKeptForTheSession() = runTest {
        val urls = mutableListOf<String>()
        val http = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
                urls += url.substringBefore("api_key=").trimEnd('&', '?')
                return when {
                    "/find/tt0944947" in url -> HttpResult(200, """{"movie_results":[],"tv_results":[{"id":1399}]}""")
                    "/tv/1399" in url -> HttpResult(200, """{"content_ratings":{"results":[{"iso_3166_1":"GB","rating":"15"}]},"external_ids":{"imdb_id":"tt0944947"}}""")
                    "/movie/603" in url -> HttpResult(200, """{"release_dates":{"results":[]},"imdb_id":"tt0133093"}""")
                    else -> HttpResult(404, "{}")
                }
            }
            override suspend fun postJson(url: String, body: String, timeoutMs: Long) = error("unused")
        }
        val tmdb = TmdbClient(http, "0123456789abcdef0123456789abcdef")
        assertEquals(TmdbFacts("15", "tt0944947"), tmdb.facts("tt0944947", "series", "gb"))
        assertEquals(TmdbFacts("15", "tt0944947"), tmdb.facts("tt0944947", "series", "GB"))
        assertEquals(2, urls.size, "find, then details with ratings appended; then kept")
        assertTrue(urls[1].endsWith("/tv/1399?append_to_response=content_ratings,external_ids"), urls[1])
        assertEquals(TmdbFacts(null, "tt0133093"), tmdb.facts("tmdb:603", "movie", "GB"), "no find for TMDB ids")
        assertTrue(urls[2].endsWith("/movie/603?append_to_response=release_dates,external_ids"), urls[2])
        assertNull(tmdb.facts("kitsu:12", "series", "GB"), "only IMDb and TMDB ids")
        assertNull(TmdbClient(http, "").facts("tt0944947", "series", "GB"), "no key, no request")
        assertEquals(3, urls.size)
    }
}
