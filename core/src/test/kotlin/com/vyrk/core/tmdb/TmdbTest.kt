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
}
