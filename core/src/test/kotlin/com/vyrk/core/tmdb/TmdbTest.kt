package com.vyrk.core.tmdb

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures follow the response shapes in TMDB's v3 reference; values are made up. */
class TmdbTest {
    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s) as JsonObject

    @Test
    fun findPrefersTheRequestedType() {
        val o = obj("""{"movie_results":[{"id":11}],"tv_results":[{"id":22}],"person_results":[]}""")
        assertEquals(11L to "movie", TmdbClient.parseFind(o, preferTv = false))
        assertEquals(22L to "tv", TmdbClient.parseFind(o, preferTv = true))
        assertNull(TmdbClient.parseFind(obj("""{"movie_results":[],"tv_results":[]}"""), false))
    }

    @Test
    fun movieCreditsAndAggregateTvCredits() {
        val movie = TmdbClient.parseCredits(obj("""{"id":1,"cast":[{"id":7,"name":"B Actor","character":"Two","order":1,"profile_path":"/b.jpg"},{"id":6,"name":"A Actor","character":"One","order":0}],
            "crew":[{"id":9,"name":"Dee Rector","job":"Director","department":"Directing"},{"id":10,"name":"Wry Ter","job":"Screenplay","department":"Writing"},{"id":11,"name":"Grip","job":"Key Grip","department":"Crew"}]}"""))
        assertEquals(listOf("A Actor", "B Actor"), movie.cast.map { it.name })
        assertEquals(listOf("Director", "Screenplay"), movie.keyCrew.map { it.role })
        val tv = TmdbClient.parseCredits(obj("""{"cast":[{"id":5,"name":"Lead","roles":[{"character":"Hero","episode_count":10}],"order":0}],
            "crew":[{"id":8,"name":"Show Runner","department":"Writing","jobs":[{"job":"Creator"},{"job":"Writer"}]}]}"""))
        assertEquals("Hero", tv.cast[0].role)
        assertEquals("Creator", tv.keyCrew[0].role)
    }

    @Test
    fun personAndCreditsSortedByNotability() {
        val p = TmdbClient.parsePerson(obj("""{"id":3,"name":"Some One","biography":"Born somewhere.","birthday":"1970-01-02","deathday":null,"place_of_birth":"Town","known_for_department":"Acting","profile_path":"/p.jpg"}"""))!!
        assertEquals("Some One", p.name); assertNull(p.deathday); assertEquals("Acting", p.knownFor)
        val c = TmdbClient.parsePersonCredits(obj("""{"cast":[
            {"id":1,"media_type":"movie","title":"Small Film","character":"X","release_date":"2001-05-01","vote_count":10,"popularity":1.0},
            {"id":2,"media_type":"tv","name":"Big Show","character":"Y","first_air_date":"2010-01-01","vote_count":5000,"popularity":50.0},
            {"id":3,"media_type":"tv","name":"Late Night","character":"Self - Guest","vote_count":9000}],
            "crew":[{"id":1,"media_type":"movie","title":"Small Film","job":"Producer","department":"Production","vote_count":10},
                    {"id":4,"media_type":"movie","title":"Directed Thing","job":"Director","department":"Directing","release_date":"","vote_count":100}]}"""))
        assertEquals(listOf("Big Show", "Directed Thing", "Small Film"), c.map { it.title })
        assertEquals("series", c[0].type); assertEquals("2010", c[0].year); assertNull(c[1].year)
        assertEquals("Acting", c.first { it.title == "Small Film" }.department)
    }

    private class Fake : HttpTransport {
        val urls = mutableListOf<String>(); val headers = mutableListOf<Map<String, String>>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
            urls += url; this.headers += headers
            return HttpResult(200, """{"movie_results":[{"id":42}],"tv_results":[],"imdb_id":"tt0000042"}""")
        }
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(404, "")
    }

    @Test
    fun keyStylesAndDisabledWithoutKey() = runTest {
        val f = Fake()
        assertEquals(42L to "movie", TmdbClient(f, "v3key").findByImdb("tt0000042", false))
        assertTrue(f.urls[0].startsWith("https://api.themoviedb.org/3/find/tt0000042?external_source=imdb_id&api_key=v3key"))
        TmdbClient(f, "eyJhbGciOi.token").imdbId(42, "movie")
        assertFalse(f.urls[1].contains("api_key")); assertEquals("Bearer eyJhbGciOi.token", f.headers[1]["Authorization"])
        val off = TmdbClient(f, "")
        assertFalse(off.enabled); assertNull(off.person(1)); assertEquals(2, f.urls.size)
        assertEquals("https://image.tmdb.org/t/p/w185/p.jpg", TmdbClient.image("/p.jpg"))
    }

    @Test
    fun keysAreCleanedUp() = runTest {
        val v3 = "0123456789abcdef0123456789abcdef"
        assertEquals(v3, TmdbClient.normalizeKey("﻿$v3"))
        assertEquals(v3, TmdbClient.normalizeKey("  $v3 \n"))
        assertEquals(v3, TmdbClient.normalizeKey("TMDB_API_KEY=\"$v3\""))
        assertEquals("eyJhbGc.eyJzdWI.sig_-1", TmdbClient.normalizeKey("Bearer eyJhbGc.eyJzdWI.sig_-1\r\n"))
        assertEquals("v3key", TmdbClient.normalizeKey(" v3key "))
        assertEquals("", TmdbClient.normalizeKey("﻿ "))
        // The request carries the clean key.
        val f = Fake()
        TmdbClient(f, "﻿$v3").findByImdb("tt1", false)
        assertTrue(f.urls[0].endsWith("api_key=$v3"))
        assertFalse(TmdbClient(f, "﻿ ").enabled)
    }

    @Test
    fun failuresAreReportedWithoutTheKey() = runTest {
        val reports = mutableListOf<String>()
        val denied = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long) = HttpResult(401, """{"status_message":"Invalid API key"}""")
            override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(404, "")
        }
        assertNull(TmdbClient(denied, "secretkey123") { path, reason -> reports += "$path $reason" }.findByImdb("tt1", false))
        assertEquals(listOf("/find/tt1 HTTP 401"), reports)

        val broken = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult = throw java.io.IOException("failed to connect to $url")
            override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(404, "")
        }
        reports.clear()
        assertNull(TmdbClient(broken, "secretkey123") { path, reason -> reports += "$path $reason" }.credits(7, "movie"))
        assertEquals(1, reports.size)
        assertTrue(reports[0].startsWith("/movie/7/credits IOException"))
        assertFalse(reports[0].contains("secretkey123"))
    }

    private val imagesJson = """{"id":1399,
        "backdrops":[{"aspect_ratio":1.778,"height":2160,"iso_639_1":"en","file_path":"/en-bd.jpg","vote_average":5.9,"vote_count":20,"width":3840},
                     {"aspect_ratio":1.778,"height":1080,"iso_639_1":null,"file_path":"/textless-low.jpg","vote_average":5.1,"vote_count":3,"width":1920},
                     {"aspect_ratio":1.778,"height":2160,"iso_639_1":null,"file_path":"/textless.jpg","vote_average":5.6,"vote_count":12,"width":3840}],
        "logos":[{"aspect_ratio":2.6,"height":400,"iso_639_1":null,"file_path":"/nolang.png","vote_average":9.0,"vote_count":5,"width":1040},
                 {"aspect_ratio":2.6,"height":500,"iso_639_1":"en","file_path":"/en.svg","vote_average":9.9,"vote_count":9,"width":1300},
                 {"aspect_ratio":2.6,"height":400,"iso_639_1":"en","file_path":"/en-low.png","vote_average":5.2,"vote_count":2,"width":1040},
                 {"aspect_ratio":2.6,"height":600,"iso_639_1":"en","file_path":"/en.png","vote_average":5.4,"vote_count":4,"width":1560}],
        "posters":[{"aspect_ratio":0.667,"height":3000,"iso_639_1":"de","file_path":"/de.jpg","vote_average":6.0,"vote_count":9,"width":2000},
                   {"aspect_ratio":0.667,"height":3000,"iso_639_1":"en","file_path":"/en-p.jpg","vote_average":5.5,"vote_count":9,"width":2000}]}"""

    @Test
    fun artworkPrefersEnglishLogosTextlessBackdropsAndSkipsSvg() {
        val art = TmdbClient.parseArtwork(obj(imagesJson), "original")
        // English beats no-language even with fewer votes; the SVG is skipped; then by rating.
        assertEquals("https://image.tmdb.org/t/p/original/en.png", art.logo)
        // Textless first for backdrops (a logo goes on top), best rated among them.
        assertEquals("https://image.tmdb.org/t/p/original/textless.jpg", art.backdrop)
        assertEquals("https://image.tmdb.org/t/p/w500/en-p.jpg", art.poster)
        val empty = TmdbClient.parseArtwork(obj("""{"id":1,"backdrops":[],"logos":[],"posters":[]}"""), "w1280")
        assertEquals(TmdbArtwork(null, null, null), empty)
    }

    private class ImagesFake(private val images: String) : HttpTransport {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
            urls += url
            return if ("/find/" in url) HttpResult(200, """{"movie_results":[],"tv_results":[{"id":1399}]}""") else HttpResult(200, images)
        }
        override suspend fun postJson(url: String, body: String, timeoutMs: Long) = HttpResult(404, "")
    }

    @Test
    fun artworkLooksUpImdbIdsAndCachesTheAnswer() = runTest {
        val f = ImagesFake(imagesJson)
        val tmdb = TmdbClient(f, "v3key")
        val art = tmdb.artwork("tt0944947", "series", "w1280")
        assertEquals("https://image.tmdb.org/t/p/w1280/textless.jpg", art?.backdrop)
        assertTrue(f.urls[1].startsWith("https://api.themoviedb.org/3/tv/1399/images?include_image_language=en,null,ja&api_key="))
        tmdb.artwork("tt0944947", "series", "w1280")
        assertEquals(2, f.urls.size)
        // tmdb: ids skip /find; other ids are not looked up.
        tmdb.artwork("tmdb:603", "movie", "w1280")
        assertTrue(f.urls[2].startsWith("https://api.themoviedb.org/3/movie/603/images?"))
        assertNull(tmdb.artwork("kitsu:1", "series"))
        assertNull(TmdbClient(f, "").artwork("tt0944947", "series"))
        assertEquals(3, f.urls.size)
    }
}
